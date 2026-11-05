package com.mediquery.module.consultation.entity;

/**
 * 问诊小结的生成状态。
 *
 * <p>为什么单独一个维度、不和 {@link ConsultationStatus} 混在一起：
 * 小结生成是异步的，可能失败、可能重试。{@code status=COMPLETED} 且
 * {@code summary_status=FAILED} 是合法状态 —— 问诊已经结束了，只是总结没生成出来。
 * 如果要求两者同步，状态机就会纠缠：完结接口到底该等小结还是不等？
 */
public final class SummaryStatus {

    /** 未生成。新建问诊单、以及取消/超时的单都是这个状态。 */
    public static final int NONE = 0;

    /** 生成中。完结接口返回时立即置为这个值，前端据此显示 loading。 */
    public static final int GENERATING = 1;

    /** 已生成。终态，不允许重新生成 —— 医疗记录不能被反复覆盖。 */
    public static final int DONE = 2;

    /** 生成失败。可以重试（GENERATING ← FAILED）。 */
    public static final int FAILED = 3;

    private SummaryStatus() {}

    public static String nameOf(int status) {
        return switch (status) {
            case NONE -> "未生成";
            case GENERATING -> "生成中";
            case DONE -> "已生成";
            case FAILED -> "生成失败";
            default -> "未知状态(" + status + ")";
        };
    }
}
