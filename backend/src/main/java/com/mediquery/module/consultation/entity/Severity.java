package com.mediquery.module.consultation.entity;

/**
 * 问诊严重度。
 *
 * <p>纯 AI 方案下它不做转人工，只做两件事：
 * ① 打标存库，方便事后统计；
 * ② {@code HIGH} 时前端弹红色横幅「请立即就医或拨打 120」。
 *
 * <p>注意 {@code HIGH} 的判断是保守的 —— 宁可多提示一次，不能漏。
 * 但也不能因为解析失败就设成 HIGH，那会给用户发假的紧急告警，比漏报更糟。
 * 所以 {@code SummaryParser} 解析失败时一律回落 {@code LOW}。
 */
public final class Severity {

    /** 低。常规不适，可自助处理。 */
    public static final int LOW = 0;

    /** 中。建议就医，但不紧急。 */
    public static final int MEDIUM = 1;

    /** 高。疑似急症，前端必须展示紧急就医提示。 */
    public static final int HIGH = 2;

    private Severity() {}

    public static String nameOf(int severity) {
        return switch (severity) {
            case LOW -> "低";
            case MEDIUM -> "中";
            case HIGH -> "高";
            default -> "未知(" + severity + ")";
        };
    }

    /** 超出取值域的严重度一律按「低」处理，绝不因为脏数据发出假告警。 */
    public static int normalize(int severity) {
        return (severity >= LOW && severity <= HIGH) ? severity : LOW;
    }
}
