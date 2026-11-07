package com.mediquery.module.consultation.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * 问诊小结的返回结构。
 *
 * <p>这个类同时承担三个角色：① {@link com.mediquery.module.consultation.service.SummaryService}
 * 解析模型 JSON 的目标类型；② 存进 {@code consultation.ai_summary} 列序列化后的形态；
 * ③ 接口 {@code GET /consultations/{id}} 里 {@code summary} 字段的类型。
 *
 * <p>三者用同一个类是有意的：如果解析用一个 DTO、返回用另一个 VO，
 * 字段增减时必须改两处，漏一处就是线上返回 null。风险高于收益。
 *
 * <h3>为什么 disclaimer 不从模型读</h3>
 *
 * <p>{@link #DISCLAIMER_TEXT} 是**后端无条件覆盖**的常量，不信任模型输出。
 * AI 可能忘记写免责声明，也可能写出比这更强或更弱的措辞。
 * 医疗场景下这句话是合规底线，不能交给一个概率模型决定。
 */
public class SummaryVO {

    /**
     * 免责声明。任何小结都要带上，且由后端强制写入。
     *
     * <p>措辞刻意不含「诊断」「确诊」这类词 —— 这是一次信息整理，不是医疗行为。
     */
    public static final String DISCLAIMER_TEXT =
            "以上内容由 AI 根据你描述的对话自动整理，不构成医疗诊断，不能替代医生面诊。"
                    + "如有不适请及时就医，紧急情况请拨打 120。";

    /** 一句话概括的患者主诉。 */
    private String chiefComplaint;

    /**
     * 从对话里提取到的症状标签，如「干咳」「低烧」「夜间加重」。最多 6 个。
     *
     * <p>单独一个字段而不是塞进 chiefComplaint，是因为前端要拿它做标签渲染：
     * 一张问诊小结卡片上，一眼能看到几个症状词，比读一段描述快得多。
     */
    private List<String> symptoms;

    /**
     * 病程时长，如「约 3 天」「超过两周」。模型判断不出来时是空串。
     *
     * <p>这是分诊里最关键的变量之一：同样一个咳嗽，三天和三个月完全是两回事。
     */
    private String duration;

    /** 可能的病因或疾病方向，最多 3 条。 */
    private List<String> possibleDirections;

    /** 建议挂号的科室，最多 2 个。 */
    private List<String> suggestedDepartments;

    /**
     * 为什么建议这些科室。一句话。
     *
     * <p>光给科室名，用户会怀疑「它凭什么这么说」。给出理由，
     * 即使理由不完美，用户也能自己判断要不要采纳 —— 这是把决定权还给用户。
     */
    private String departmentReason;

    /** 生活或用药方面的建议，最多 3 条。 */
    private List<String> medicationHints;

    /**
     * 居家护理的具体做法，最多 4 条。
     *
     * <p>和 {@code medicationHints} 分开：用药建议必须谨慎（不能给剂量），
     * 而护理建议（多喝水、抬高患肢、保持通风）相对安全且更有可操作性。
     * 混在一起会导致要么护理部分过于保守，要么用药部分过于具体。
     */
    private List<String> careAdvice;

    /** 严重度：0 轻微 / 1 中度 / 2 高度。 */
    private Integer severity;

    /** 给出该严重度的理由。 */
    private String severityReason;

    /** 需要立即就医的危险信号。没有就是空数组，不是 null。 */
    private List<String> redFlags;

    /** 免责声明，后端无条件覆盖。 */
    private String disclaimer;

    public SummaryVO() {
    }

    /**
     * 造一个空壳小结。
     *
     * <p>用在两种降级场景：对话为空（没什么可总结）；模型输出解析失败
     * （宁可给用户一句「建议就医」，也不能把一整段 JSON 解析错误暴露给前端）。
     *
     * <p>注意 severity 是 {@code LOW} 不是 {@code HIGH} —— 解析失败不代表病情严重，
     * 猜成 HIGH 会让用户收到假的紧急告警。
     */
    public static SummaryVO empty(String chiefComplaint) {
        SummaryVO vo = new SummaryVO();
        vo.setChiefComplaint(chiefComplaint == null || chiefComplaint.isBlank()
                ? "（未填写主诉）" : chiefComplaint);
        vo.setSymptoms(new ArrayList<>());
        vo.setDuration("");
        vo.setPossibleDirections(new ArrayList<>());
        vo.setSuggestedDepartments(new ArrayList<>());
        vo.setDepartmentReason("");
        vo.setMedicationHints(new ArrayList<>());
        vo.setCareAdvice(new ArrayList<>());
        vo.setSeverity(0);
        vo.setSeverityReason("未能从对话中提取有效信息");
        vo.setRedFlags(new ArrayList<>());
        vo.setDisclaimer(DISCLAIMER_TEXT);
        return vo;
    }

    public String getChiefComplaint() { return chiefComplaint; }
    public void setChiefComplaint(String chiefComplaint) { this.chiefComplaint = chiefComplaint; }

    public List<String> getSymptoms() { return symptoms; }
    public void setSymptoms(List<String> symptoms) { this.symptoms = symptoms; }

    public String getDuration() { return duration; }
    public void setDuration(String duration) { this.duration = duration; }

    public List<String> getPossibleDirections() { return possibleDirections; }
    public void setPossibleDirections(List<String> possibleDirections) { this.possibleDirections = possibleDirections; }

    public List<String> getSuggestedDepartments() { return suggestedDepartments; }
    public void setSuggestedDepartments(List<String> suggestedDepartments) { this.suggestedDepartments = suggestedDepartments; }

    public String getDepartmentReason() { return departmentReason; }
    public void setDepartmentReason(String departmentReason) { this.departmentReason = departmentReason; }

    public List<String> getMedicationHints() { return medicationHints; }
    public void setMedicationHints(List<String> medicationHints) { this.medicationHints = medicationHints; }

    public List<String> getCareAdvice() { return careAdvice; }
    public void setCareAdvice(List<String> careAdvice) { this.careAdvice = careAdvice; }

    public Integer getSeverity() { return severity; }
    public void setSeverity(Integer severity) { this.severity = severity; }

    public String getSeverityReason() { return severityReason; }
    public void setSeverityReason(String severityReason) { this.severityReason = severityReason; }

    public List<String> getRedFlags() { return redFlags; }
    public void setRedFlags(List<String> redFlags) { this.redFlags = redFlags; }

    public String getDisclaimer() { return disclaimer; }
    public void setDisclaimer(String disclaimer) { this.disclaimer = disclaimer; }
}
