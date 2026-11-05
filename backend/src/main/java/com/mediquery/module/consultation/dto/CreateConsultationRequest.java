package com.mediquery.module.consultation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getChiefComplaint() { return chiefComplaint; }
    public void setChiefComplaint(String chiefComplaint) { this.chiefComplaint = chiefComplaint; }
}
