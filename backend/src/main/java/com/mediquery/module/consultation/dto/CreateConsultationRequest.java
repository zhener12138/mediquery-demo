package com.mediquery.module.consultation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 发起问诊的请求体。
 *
 * <p>{@code sessionId} 是可选的：前端首次进入可以不传，后端生成；
 * 传了就用传的 —— 这样前端可以在「刷新后重连同一张单」时复用。
 */
public class CreateConsultationRequest {

    /**
     * 会话 ID。幂等键。
     *
     * <p>不传则后端生成 UUID。传了就必须是同一张单 —— 前端重试、双击都不会多开单。
     * 长度限制 36 与数据库 {@code VARCHAR(36)} 对齐，防止超长字符串走到数据库才报错。
     */
    @Size(max = 36, message = "sessionId 长度不能超过 36")
    private String sessionId;

    @NotBlank(message = "请描述你的症状")
    @Size(max = 500, message = "主诉不能超过 500 字")
    private String chiefComplaint;

    // ==== 问诊信息卡（1.1 结构化采集）：全部可选，随开单一并提交 ====

    /** 病程描述，如「三天」。可空。 */
    @Size(max = 100, message = "病程描述不能超过 100 字")
    private String durationDesc;

    /** 自评严重度 0-10。可空。 */
    @Min(value = 0, message = "严重度要在 0-10 之间")
    @Max(value = 10, message = "严重度要在 0-10 之间")
    private Integer severityScale;

    /** 既往史/过敏史/长期用药，每条不超过 100 字，最多 10 条。可空。 */
    @Size(max = 10, message = "病史条目最多 10 条")
    private List<@Size(max = 100, message = "单条病史不能超过 100 字") String> historyFlags;

    /** 起病方式：sudden（突然）/ gradual（渐进）。可空。 */
    @Pattern(regexp = "sudden|gradual", message = "起病方式只支持 sudden（突然）或 gradual（渐进）")
    private String onsetType;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getChiefComplaint() { return chiefComplaint; }
    public void setChiefComplaint(String chiefComplaint) { this.chiefComplaint = chiefComplaint; }

    public String getDurationDesc() { return durationDesc; }
    public void setDurationDesc(String durationDesc) { this.durationDesc = durationDesc; }

    public Integer getSeverityScale() { return severityScale; }
    public void setSeverityScale(Integer severityScale) { this.severityScale = severityScale; }

    public List<String> getHistoryFlags() { return historyFlags; }
    public void setHistoryFlags(List<String> historyFlags) { this.historyFlags = historyFlags; }

    public String getOnsetType() { return onsetType; }
    public void setOnsetType(String onsetType) { this.onsetType = onsetType; }
}
