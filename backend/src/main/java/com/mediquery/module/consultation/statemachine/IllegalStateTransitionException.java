package com.mediquery.module.consultation.statemachine;

import com.mediquery.module.consultation.entity.ConsultationStatus;
import com.mediquery.module.consultation.entity.SummaryStatus;

/**
 * 状态转移不合法。
 *
 * <p>继承 {@code BusinessException} 是为了让 {@code GlobalExceptionHandler} 统一处理；
 * 携带 code=409 让 HTTP 状态语义正确 —— 这不是参数错误(400)，是「你要求的操作
 * 与当前资源状态冲突」。
 *
 * <p>为什么单独建一个类而不是直接 {@code throw BusinessException(409, ...)}：
 * ① 调用方可以 {@code catch (IllegalStateTransitionException e)} 做补偿（比如超时任务
 * 撞上完结后应该跳过而不是报错）；② 全局处理器能对它做专门映射，不用靠消息文本判断。
 */
public class IllegalStateTransitionException extends RuntimeException {

    /** 与 BusinessException 的 409 对齐，由 GlobalExceptionHandler 映射成 HTTP 409。 */
    public static final int CODE = 409;

    private final String dimension;
    private final int from;
    private final int to;

    public IllegalStateTransitionException(String dimension, int from, int to) {
        this(dimension, from, to, null);
    }

    public IllegalStateTransitionException(String dimension, int from, int to, String hint) {
        super(buildMessage(dimension, from, to, hint));
        this.dimension = dimension;
        this.from = from;
        this.to = to;
    }

    public String getDimension() { return dimension; }

    public int getFrom() { return from; }

    public int getTo() { return to; }

    /**
     * 拼用户能看懂的错误信息。
     *
     * <p><b>不要写成「问诊状态不能从「已完成」流转到「已完成」」</b> —— 这是最初版本，
     * 实跑时发现的：用户点了第二次完结，看到的是一句自相矛盾的话（他明明没成功，
     * 系统却说「已完成到已完成」）。技术上没错（客户端重发的确是一次非法转移），
     * 但对用户没有任何帮助。
     *
     * <p>真实场景几乎总是「终态再操作」，所以这里按这个前提给一句话：
     * 单子已经结束了，你刚才那个操作没生效。to 状态只在不涉及终态时才提。
     */
    private static String buildMessage(String dimension, int from, int to, String hint) {
        boolean mainStatus = "问诊状态".equals(dimension);
        String fromName = mainStatus ? ConsultationStatus.nameOf(from) : SummaryStatus.nameOf(from);
        String toName = mainStatus ? ConsultationStatus.nameOf(to) : SummaryStatus.nameOf(to);

        boolean fromTerminal = mainStatus
                ? ConsultationStateMachine.isTerminal(from)
                : from == SummaryStatus.DONE;

        String base;
        boolean hintAlreadyCovered;
        if (fromTerminal) {
            // 最常见的情况：单子已经结束了。
            // 注意 nameOf 返回的已经是「已完成」「已取消」这种完成态，
            // 前面再接「已经」会变成「已经已完成」—— 所以用「本次问诊」+ 状态名。
            base = mainStatus
                    ? "本次问诊" + fromName + "，这张单已经关闭，不能再操作"
                    : "小结" + fromName + "，不能重新生成";
            // 调用方通常也会传「这张单已经关闭」这类 hint，与 base 重复了就不再追加
            hintAlreadyCovered = true;
        } else {
            // 非终态出发的非法转移：这是程序 bug 或前端拼错了请求，
            // 保留 from/to 便于排查，用户看到也无妨
            base = dimension + "不能从「" + fromName + "」变为「" + toName + "」";
            hintAlreadyCovered = false;
        }
        return (hint == null || hint.isBlank() || hintAlreadyCovered) ? base : base + "：" + hint;
    }
}
