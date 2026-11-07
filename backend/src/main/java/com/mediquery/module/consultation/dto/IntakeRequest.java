package com.mediquery.module.consultation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 问诊信息卡（1.1 结构化采集）的读写载体。
 *
 * <p>四个槽位全部可空：用户愿意填多少就填多少，剩下的交给 AI 在对话里自己问。
 * 这里不做任何「必填校验」——逼用户填表是问诊体验的第一杀手，
 * 宁可空着，也不能让用户为了发起一次问诊先过五道关卡。
 *
 * <p>校验只拦「明显是胡填」的：
 * <ul>
 *   <li>{@code severityScale} 0-10 之外直接拒（null 允许，代表「没填」）；</li>
 *   <li>{@code onsetType} 只认 sudden/gradual/null；</li>
 *   <li>{@code historyFlags} 每条走 @Size 限制。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IntakeRequest {

    /** 病程描述，如「三天」「从上周开始」。可空。 */
    private String durationDesc;

    /** 自评严重度 0-10，10 最重。null = 没填。 */
    private Integer severityScale;

    /** 既往史/过敏史/长期用药。null = 没填；空数组 = 用户明确表示没有。 */
    private List<String> historyFlags;

    /** 起病方式：sudden 突然 / gradual 渐进。null = 没填。 */
    private String onsetType;

    public String getDurationDesc() { return durationDesc; }
    public void setDurationDesc(String durationDesc) { this.durationDesc = durationDesc; }

    public Integer getSeverityScale() { return severityScale; }
    public void setSeverityScale(Integer severityScale) { this.severityScale = severityScale; }

    public List<String> getHistoryFlags() { return historyFlags; }
    public void setHistoryFlags(List<String> historyFlags) { this.historyFlags = historyFlags; }

    public String getOnsetType() { return onsetType; }
    public void setOnsetType(String onsetType) { this.onsetType = onsetType; }
}
