package com.mediquery.module.consultation.statemachine;

import com.mediquery.module.consultation.entity.ConsultationStatus;
import com.mediquery.module.consultation.entity.SummaryStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 状态机的穷举测试。
 *
 * <p>4 个状态 × 4 个目标 = 16 种组合，全部显式断言，不靠「跑一遍没抛异常就算过」。
 * 这样任何一条边的增删都会让具体的某个用例变红，而不是笼统地红一个。
 */
class ConsultationStateMachineTest {

    private static final List<Integer> ALL_STATUSES = List.of(
            ConsultationStatus.IN_PROGRESS,
            ConsultationStatus.COMPLETED,
            ConsultationStatus.CANCELLED,
            ConsultationStatus.TIMEOUT
    );

    private static final List<Integer> ALL_SUMMARY_STATUSES = List.of(
            SummaryStatus.NONE,
            SummaryStatus.GENERATING,
            SummaryStatus.DONE,
            SummaryStatus.FAILED
    );

    @Nested
    @DisplayName("主状态 16 种组合")
    class MainStatus {

        @Test
        @DisplayName("进行中 → 已完成 / 已取消 / 超时关闭 三条边都合法")
        void inProgressToTerminalsIsAllowed() {
            assertDoesNotThrow(() -> ConsultationStateMachine.checkTransition(
                    ConsultationStatus.IN_PROGRESS, ConsultationStatus.COMPLETED));
            assertDoesNotThrow(() -> ConsultationStateMachine.checkTransition(
                    ConsultationStatus.IN_PROGRESS, ConsultationStatus.CANCELLED));
            assertDoesNotThrow(() -> ConsultationStateMachine.checkTransition(
                    ConsultationStatus.IN_PROGRESS, ConsultationStatus.TIMEOUT));
        }

        @Test
        @DisplayName("进行中 → 进行中 不合法（不能原地踏步）")
        void inProgressToItselfIsRejected() {
            IllegalStateTransitionException e = assertThrows(IllegalStateTransitionException.class,
                    () -> ConsultationStateMachine.checkTransition(
                            ConsultationStatus.IN_PROGRESS, ConsultationStatus.IN_PROGRESS));
            assertEquals("问诊状态", e.getDimension());
            assertEquals(ConsultationStatus.IN_PROGRESS, e.getFrom());
            assertEquals(ConsultationStatus.IN_PROGRESS, e.getTo());
        }

        @Test
        @DisplayName("三个终态出发的 12 种转移全部不合法")
        void anyTransitionFromTerminalIsRejected() {
            List<Integer> terminals = List.of(
                    ConsultationStatus.COMPLETED,
                    ConsultationStatus.CANCELLED,
                    ConsultationStatus.TIMEOUT);
            int checked = 0;
            for (int from : terminals) {
                for (int to : ALL_STATUSES) {
                    assertThrows(IllegalStateTransitionException.class,
                            () -> ConsultationStateMachine.checkTransition(from, to),
                            "从终态 " + ConsultationStatus.nameOf(from)
                                    + " 到 " + ConsultationStatus.nameOf(to) + " 应当被拒绝");
                    checked++;
                }
            }
            assertEquals(12, checked, "终态 3 个 × 目标 4 个 = 12 种组合都要覆盖到");
        }

        @Test
        @DisplayName("16 种组合里恰好 3 种合法")
        void exactlyThreeTransitionsAreLegal() {
            int legal = 0;
            for (int from : ALL_STATUSES) {
                for (int to : ALL_STATUSES) {
                    try {
                        ConsultationStateMachine.checkTransition(from, to);
                        legal++;
                    } catch (IllegalStateTransitionException ignored) {
                        // 预期内
                    }
                }
            }
            assertEquals(3, legal, "合法边只有 进行中 → {已完成, 已取消, 超时关闭} 这三条");
        }

        @Test
        @DisplayName("终态判定与状态机一致")
        void terminalFlagMatchesTransitions() {
            assertFalse(ConsultationStateMachine.isTerminal(ConsultationStatus.IN_PROGRESS));
            assertTrue(ConsultationStateMachine.isTerminal(ConsultationStatus.COMPLETED));
            assertTrue(ConsultationStateMachine.isTerminal(ConsultationStatus.CANCELLED));
            assertTrue(ConsultationStateMachine.isTerminal(ConsultationStatus.TIMEOUT));
        }

        @Test
        @DisplayName("终态出发的异常带可读提示")
        void terminalTransitionCarriesHint() {
            IllegalStateTransitionException e = assertThrows(IllegalStateTransitionException.class,
                    () -> ConsultationStateMachine.checkTransition(
                            ConsultationStatus.COMPLETED, ConsultationStatus.CANCELLED));
            assertTrue(e.getMessage().contains("已经关闭"), "实际消息：" + e.getMessage());
        }
    }

    @Nested
    @DisplayName("小结状态 16 种组合")
    class SummaryState {

        @Test
        @DisplayName("未生成 → 生成中 合法")
        void noneToGeneratingIsAllowed() {
            assertDoesNotThrow(() -> ConsultationStateMachine.checkSummaryTransition(
                    SummaryStatus.NONE, SummaryStatus.GENERATING));
        }

        @Test
        @DisplayName("未生成 → 已生成/生成失败 不合法（不能跳步）")
        void noneCannotJumpToResult() {
            assertThrows(IllegalStateTransitionException.class,
                    () -> ConsultationStateMachine.checkSummaryTransition(
                            SummaryStatus.NONE, SummaryStatus.DONE));
            assertThrows(IllegalStateTransitionException.class,
                    () -> ConsultationStateMachine.checkSummaryTransition(
                            SummaryStatus.NONE, SummaryStatus.FAILED));
        }

        @Test
        @DisplayName("生成中 → 已生成 / 生成失败 都合法")
        void generatingCanFinishEitherWay() {
            assertDoesNotThrow(() -> ConsultationStateMachine.checkSummaryTransition(
                    SummaryStatus.GENERATING, SummaryStatus.DONE));
            assertDoesNotThrow(() -> ConsultationStateMachine.checkSummaryTransition(
                    SummaryStatus.GENERATING, SummaryStatus.FAILED));
        }

        @Test
        @DisplayName("生成失败 → 生成中 合法（可重试）")
        void failedCanRetry() {
            assertDoesNotThrow(() -> ConsultationStateMachine.checkSummaryTransition(
                    SummaryStatus.FAILED, SummaryStatus.GENERATING));
        }

        @Test
        @DisplayName("已生成是终态：出发的 4 种转移全部不合法")
        void doneIsTerminal() {
            for (int to : ALL_SUMMARY_STATUSES) {
                assertThrows(IllegalStateTransitionException.class,
                        () -> ConsultationStateMachine.checkSummaryTransition(SummaryStatus.DONE, to),
                        "已生成到 " + SummaryStatus.nameOf(to) + " 应当被拒绝");
            }
        }

        @Test
        @DisplayName("16 种组合里恰好 4 种合法")
        void exactlyFourTransitionsAreLegal() {
            int legal = 0;
            for (int from : ALL_SUMMARY_STATUSES) {
                for (int to : ALL_SUMMARY_STATUSES) {
                    try {
                        ConsultationStateMachine.checkSummaryTransition(from, to);
                        legal++;
                    } catch (IllegalStateTransitionException ignored) {
                        // 预期内
                    }
                }
            }
            assertEquals(4, legal,
                    "合法边：未生成→生成中、生成中→已生成、生成中→生成失败、生成失败→生成中");
        }

        @Test
        @DisplayName("已生成不可重生成，异常消息说明原因")
        void doneIsNotRegenerable() {
            IllegalStateTransitionException e = assertThrows(IllegalStateTransitionException.class,
                    () -> ConsultationStateMachine.checkSummaryTransition(
                            SummaryStatus.DONE, SummaryStatus.GENERATING));
            assertTrue(e.getMessage().contains("不能重新生成"), "实际消息：" + e.getMessage());
        }
    }

    @Test
    @DisplayName("转移表与 allowedTargets 暴露的集合一致")
    void exposedTablesMatchBehaviour() {
        assertEquals(Set.of(ConsultationStatus.COMPLETED,
                        ConsultationStatus.CANCELLED,
                        ConsultationStatus.TIMEOUT),
                ConsultationStateMachine.allowedTargets(ConsultationStatus.IN_PROGRESS));
        assertEquals(Set.of(), ConsultationStateMachine.allowedTargets(ConsultationStatus.COMPLETED));

        assertEquals(Set.of(SummaryStatus.GENERATING),
                ConsultationStateMachine.allowedSummaryTargets(SummaryStatus.NONE));
        assertEquals(Set.of(), ConsultationStateMachine.allowedSummaryTargets(SummaryStatus.DONE));
    }

    @Test
    @DisplayName("未知状态值不会误判为合法")
    void unknownStatusIsRejectedNotIgnored() {
        assertThrows(IllegalStateTransitionException.class,
                () -> ConsultationStateMachine.checkTransition(99, ConsultationStatus.COMPLETED));
        assertThrows(IllegalStateTransitionException.class,
                () -> ConsultationStateMachine.checkSummaryTransition(99, SummaryStatus.GENERATING));
    }
}
