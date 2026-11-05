package com.mediquery.module.consultation.statemachine;

import com.mediquery.module.consultation.entity.ConsultationStatus;
import com.mediquery.module.consultation.entity.SummaryStatus;

import java.util.Map;
import java.util.Set;

/**
 * 问诊单的状态机。
 *
 * <p>两个独立的状态维度，两张转移表：
 * <ul>
 *   <li>主状态：只有 {@code IN_PROGRESS} 能出去，出去就是三条终态边之一。</li>
 *   <li>小结状态：{@code NONE → GENERATING → DONE|FAILED}，{@code FAILED} 可重试，{@code DONE} 是终态。</li>
 * </ul>
 *
 * <p><b>为什么用静态类而不是 Spring Bean</b>：状态机是无状态的纯函数，不需要注入任何东西。
 * 做成 Bean 只会让每一个用到它的测试都得起 Spring 上下文，慢且没必要。
 *
 * <p><b>为什么不用 Spring StateMachine</b>：4 个状态、3 条边、无嵌套状态、无并发区域，
 * 手写转移表 30 行就完了。引框架是为了解决复杂度，不是为了炫。
 */
public final class ConsultationStateMachine {

    /**
     * 主状态合法转移表：只有 IN_PROGRESS 能出去。
     *
     * <p>用 {@code Map.of} 而不是 {@code HashMap}：这是不可变常量，不需要被改。
     * 三个终态都刻意不出现在 key 里 —— {@code getOrDefault(terminal, Set.of())} 返回空集合，
     * 于是任何从终态出发的转移都会被判非法，不需要额外写三个空集合。
     */
    private static final Map<Integer, Set<Integer>> ALLOWED = Map.of(
            ConsultationStatus.IN_PROGRESS, Set.of(
                    ConsultationStatus.COMPLETED,
                    ConsultationStatus.CANCELLED,
                    ConsultationStatus.TIMEOUT
            )
    );

    /**
     * 小结状态合法转移表。
     *
     * <p>{@code DONE} 刻意不作为 key —— 已生成的小结不能重新生成，
     * 避免用户反复覆盖医疗记录。要换结论只能新建问诊单。
     */
    private static final Map<Integer, Set<Integer>> SUMMARY_ALLOWED = Map.of(
            SummaryStatus.NONE, Set.of(SummaryStatus.GENERATING),
            SummaryStatus.GENERATING, Set.of(SummaryStatus.DONE, SummaryStatus.FAILED),
            SummaryStatus.FAILED, Set.of(SummaryStatus.GENERATING)
    );

    private ConsultationStateMachine() {}

    /**
     * 校验主状态转移，不合法直接抛异常。
     *
     * <p>只做校验，不改状态。改状态由调用方用条件 UPDATE 完成 ——
     * 内存里的校验挡不住并发，真正的互斥靠数据库的 {@code WHERE status = 0} + {@code version}。
     */
    public static void checkTransition(int from, int to) {
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalStateTransitionException("问诊状态", from, to,
                    isTerminal(from) ? "这张单已经关闭，不能再操作" : null);
        }
    }

    /** 校验小结状态转移。 */
    public static void checkSummaryTransition(int from, int to) {
        if (!SUMMARY_ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalStateTransitionException("小结状态", from, to,
                    from == SummaryStatus.DONE ? "已生成的小结不能重新生成，如需新结论请新建问诊" : null);
        }
    }

    /** 是否已是终态。终态的单只读，任何写操作都应该返回 409。 */
    public static boolean isTerminal(int status) {
        return status != ConsultationStatus.IN_PROGRESS;
    }

    /** 供测试与可观测性使用：某状态下允许去的目标集合（空集合表示终态）。 */
    static Set<Integer> allowedTargets(int from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }

    /** 供测试使用：某小结状态下允许去的目标集合。 */
    static Set<Integer> allowedSummaryTargets(int from) {
        return SUMMARY_ALLOWED.getOrDefault(from, Set.of());
    }
}
