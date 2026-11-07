package com.mediquery.module.consultation.triage;

import java.util.List;

/**
 * 一次消息的预筛结果。
 *
 * <h3>为什么这东西不叫「诊断」</h3>
 *
 * <p>它输出的是三样东西：命中了哪些危险信号、建议去看哪个科、下一步该怎么办。
 * 三样都是「引导」，没有一样是「判断你得了什么病」。命名上刻意避开
 * diagnosis / condition 这类词，是为了让后来改代码的人一眼看出边界在哪。
 *
 * <h3>severity 与小结里的 severity 不是一回事</h3>
 *
 * <p>小结里的 {@code severity} 是对整段问诊的回顾性判断，在完结时才产生。
 * 这里的 {@code severity} 是**对当前这一句话**的即时预筛，用来决定要不要
 * 立刻弹红色提示。两者计算方式、产生时机、法律含义都不同，所以在 SSE 事件里
 * 用不同的字段名（{@code triageSeverity} vs {@code severity}），绝不混用。
 */
public final class TriageResult {

    /** 0 低 / 1 中 / 2 高。与 {@code Severity} 常量对齐，但语义是「预筛」。 */
    private final int severity;

    /** 命中的危险信号描述，如「剧烈胸痛」「意识改变」。空列表表示没命中。 */
    private final List<String> flags;

    /** 建议就诊科室。空列表表示这次消息里看不出明确的科室指向。 */
    private final List<String> departments;

    /** 一句话行动提示，直接给用户看。没有命中时为空串。 */
    private final String advice;

    public TriageResult(int severity, List<String> flags, List<String> departments, String advice) {
        this.severity = severity;
        this.flags = List.copyOf(flags);
        this.departments = List.copyOf(departments);
        this.advice = advice == null ? "" : advice;
    }

    /** 什么都没命中时的空结果。注意 severity 是 0 而不是「未知」。 */
    public static TriageResult none() {
        return new TriageResult(0, List.of(), List.of(), "");
    }

    public int severity() {
        return severity;
    }

    public List<String> flags() {
        return flags;
    }

    public List<String> departments() {
        return departments;
    }

    public String advice() {
        return advice;
    }

    /** 有没有命中危险信号。写这个而不是让调用方去比 severity，语义更直白。 */
    public boolean hasRedFlag() {
        return !flags.isEmpty();
    }

    /** 有没有值得注入给模型的额外指令（危险信号或科室指向）。 */
    public boolean hasGuidance() {
        return hasRedFlag() || !departments.isEmpty();
    }

    /**
     * 把预筛结果渲染成一段给模型看的指令，拼在知识库上下文最前面。
     *
     * <p><b>为什么要把规则结果反过来喂给模型</b>：规则引擎能确定地发现「患者说了胸痛」，
     * 但它不会追问、不会措辞。模型会措辞，却可能漏掉这句话。把规则的发现交给模型，
     * 等于让确定性负责发现、让语言能力负责表达 —— 两边各干自己擅长的事。
     *
     * <p>放在知识库前面而不是后面，因为这段是**硬约束**，不该被后面的资料稀释。
     */
    public String toPromptPrefix() {
        if (!hasGuidance()) {
            return "";
        }
        var sb = new StringBuilder();
        sb.append("【系统预筛 · 必须优先处理】\n");
        if (hasRedFlag()) {
            sb.append("本轮患者描述中检测到以下危险信号：")
                    .append(String.join("、", flags))
                    .append("。\n");
            sb.append("你必须在回复的第一句话就明确提示患者立即就医或拨打 120，")
                    .append("然后再简要说明原因。不要先做常规问诊，不要淡化这件事。\n");
        } else {
            sb.append("本轮症状指向这些科室：")
                    .append(String.join("、", departments))
                    .append("。可以在回复末尾提示患者，但不必强硬。\n");
        }
        sb.append("\n");
        return sb.toString();
    }
}
