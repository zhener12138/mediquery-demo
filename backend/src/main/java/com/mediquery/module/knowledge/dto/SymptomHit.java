package com.mediquery.module.knowledge.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 症状反向召回的一行：疾病本体 + 聚合分数。
 *
 * <p>{@code score} 是 {@code SUM(weight)}，用来粗排；
 * {@code cardinalHits} 是命中几个核心症状，同分时它决定先后 ——
 * 「命中两个核心症状」比「命中五个边缘症状」更像这个病，光看 weight 总和看不出来。
 *
 * <p>{@code hasRedFlag} 单独带出，不参与打分。理由见 mapper 里的注释：
 * 红旗是「要不要立刻就医」，不是「更像哪种病」。
 */
@Data
public class SymptomHit {
    private Integer illnessId;
    private String illnessName;
    private Integer categoryId;
    private String cause;
    private String symptom;
    private String specialSymptom;
    /** SUM(weight)，粗排用 */
    private BigDecimal score;
    /** MAX(is_red_flag) */
    private Boolean hasRedFlag;
    /** 命中几个核心症状 */
    private Integer cardinalHits;
}
