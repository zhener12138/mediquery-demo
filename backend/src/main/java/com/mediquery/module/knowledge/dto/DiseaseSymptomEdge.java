package com.mediquery.module.knowledge.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 一条「疾病—症状」边的原始形态，连同疾病本体一起带出。
 *
 * <p>为什么不直接在 SQL 里 {@code SUM(weight)} 然后拿回来：那样拿到的
 * 是一个已经压扁的数字，内存里再也没有任何信息可以重新加权。
 * IDF 要按「每个症状各自的区分度」去乘它自己的那条权重，
 * 所以必须在拿到边之后再算 —— 先聚合就再也拆不开了。
 *
 * <p>代价是同一种病的元数据会跟着它的每条边重复一点，十来个病、
 * 五六个症状的量级下这个重复完全可以忽略；换来的是打分逻辑待在
 * 一个能单测、能解释、能改的 Java 方法里，而不是藏在一条 SQL 的
 * {@code GROUP BY} 后面。
 */
@Data
public class DiseaseSymptomEdge {

    /** 疾病本体 */
    private Integer illnessId;
    private String illnessName;
    private Integer categoryId;
    private String cause;
    private String symptom;
    private String specialSymptom;

    /** 这条边挂的症状 */
    private Integer symptomId;

    /** 该症状对本病的典型程度，0 到 1 */
    private BigDecimal weight;

    /** 是否核心症状：命中核心症状比命中边缘症状更像这个病 */
    private Boolean isCardinal;

    /** 是否红旗症状：只影响「要不要立刻就医」，不参与「更像哪种病」的打分 */
    private Boolean isRedFlag;
}
