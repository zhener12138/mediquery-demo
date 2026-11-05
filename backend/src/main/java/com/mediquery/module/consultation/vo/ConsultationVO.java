package com.mediquery.module.consultation.vo;

import com.mediquery.module.consultation.entity.Consultation;
import com.mediquery.module.consultation.entity.ConsultationStatus;
import com.mediquery.module.consultation.entity.Severity;
import com.mediquery.module.consultation.entity.SummaryStatus;

import java.time.LocalDateTime;

/**
 * 问诊单的对外视图。
 *
 * <p><b>为什么不直接返回实体</b>：实体里有 {@code version}（内部并发控制细节）、
 * 以及将来可能加的敏感字段。VO 是一道显式的边界 —— 加字段要人主动写在这里，
 * 而不是把表结构变化自动泄露给前端。这与 1.4 的 {@code UserVO} 是同一套理由。
 */
public class ConsultationVO {

    private Integer id;
    private String sessionId;
    private String chiefComplaint;

    /** 状态码，前端用它判断按钮可用性。 */
    private Integer status;

    /** 状态中文名，避免前端维护一份码表导致两边不同步。 */
    private String statusText;

    private Integer severity;
    private String severityText;

    private Integer summaryStatus;
    private String summaryStatusText;

    /** 只有 {@code summaryStatus=DONE} 时非空。 */
    private SummaryVO summary;

    /** 完结时由小结落成的一句话结论，未完结为 null。 */
    private String conclusion;

    private LocalDateTime lastActiveAt;
    private LocalDateTime closedAt;
    private LocalDateTime createTime;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getChiefComplaint() { return chiefComplaint; }
    public void setChiefComplaint(String chiefComplaint) { this.chiefComplaint = chiefComplaint; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public String getStatusText() { return statusText; }
    public void setStatusText(String statusText) { this.statusText = statusText; }

    public Integer getSeverity() { return severity; }
    public void setSeverity(Integer severity) { this.severity = severity; }

    public String getSeverityText() { return severityText; }
    public void setSeverityText(String severityText) { this.severityText = severityText; }

    public Integer getSummaryStatus() { return summaryStatus; }
    public void setSummaryStatus(Integer summaryStatus) { this.summaryStatus = summaryStatus; }

    public String getSummaryStatusText() { return summaryStatusText; }
    public void setSummaryStatusText(String summaryStatusText) { this.summaryStatusText = summaryStatusText; }

    public SummaryVO getSummary() { return summary; }
    public void setSummary(SummaryVO summary) { this.summary = summary; }

    public String getConclusion() { return conclusion; }
    public void setConclusion(String conclusion) { this.conclusion = conclusion; }

    public LocalDateTime getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(LocalDateTime lastActiveAt) { this.lastActiveAt = lastActiveAt; }

    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    /**
     * 实体转 VO。
     *
     * <p>刻意不解析 {@code aiSummary}：JSON 解析可能失败，而这个方法在很多地方被调用
     * （包括列表接口）。列表只需要状态，不需要小结正文。解析交给
     * {@code ConsultationService} 在详情接口里做，失败也只影响那一个字段。
     */
    public static ConsultationVO from(Consultation c) {
        ConsultationVO vo = new ConsultationVO();
        vo.setId(c.getId());
        vo.setSessionId(c.getSessionId());
        vo.setChiefComplaint(c.getChiefComplaint());
        vo.setStatus(c.getStatus());
        vo.setStatusText(ConsultationStatus.nameOf(c.getStatus()));
        vo.setSeverity(Severity.normalize(c.getSeverity()));
        vo.setSeverityText(Severity.nameOf(vo.getSeverity()));
        vo.setSummaryStatus(c.getSummaryStatus());
        vo.setSummaryStatusText(SummaryStatus.nameOf(c.getSummaryStatus()));
        vo.setConclusion(c.getConclusion());
        vo.setLastActiveAt(c.getLastActiveAt());
        vo.setClosedAt(c.getClosedAt());
        vo.setCreateTime(c.getCreateTime());
        return vo;
    }
}
