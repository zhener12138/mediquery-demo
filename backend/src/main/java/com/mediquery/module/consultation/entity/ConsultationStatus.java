package com.mediquery.module.consultation.entity;

/**
 * 问诊单主状态。
 *
 * <p>刻意用 int 常量而不是 Java enum：现有 9 张表全是 TINYINT + Integer，
 * 混用会让人困惑（一半 enum 一半 int）。统一用 Integer + 常量类，与现有风格一致。
 * 另外修改取值不用改 DDL，加一个「已退号」状态只是加一行常量。
 */
public final class ConsultationStatus {

    /** 进行中。唯一可流动的起点。 */
    public static final int IN_PROGRESS = 0;

    /** 已完成。患者主动结束，会触发 AI 小结生成。 */
    public static final int COMPLETED = 1;

    /** 已取消。患者主动放弃，不生成小结。 */
    public static final int CANCELLED = 2;

    /** 超时关闭。定时任务判定 30 分钟无活动。 */
    public static final int TIMEOUT = 3;

    private ConsultationStatus() {}

    /**
     * 给日志和错误信息用的中文名。
     *
     * <p>状态机的异常要写「问诊状态 0 → 1 不合法」，但人看日志时需要「进行中 → 已完成」。
     */
    public static String nameOf(int status) {
        return switch (status) {
            case IN_PROGRESS -> "进行中";
            case COMPLETED -> "已完成";
            case CANCELLED -> "已取消";
            case TIMEOUT -> "超时关闭";
            default -> "未知状态(" + status + ")";
        };
    }
}
