package com.mediquery.module.knowledge.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 一个候选疾病的<b>全部</b>症状表现，带症状名。
 *
 * <p>与 {@link DiseaseSymptomEdge} 的区别是查询方向：
 * 那个是「用户说了什么症状 → 哪些病沾边」，这个是
 * 「已经是候选了的那几个病 → 它们各自还写了哪些症状」。
 *
 * <p>后者是主动追问的前提。要问出「有没有吞咽困难」，系统得先知道
 * 扁桃体炎写了吞咽困难、而病毒性感冒没写 —— 如果只知道用户提过的症状，
 * 那永远只能复述用户自己说过的话。
 */
@Data
public class DiseaseSymptomView {

    private Integer illnessId;
    private String illnessName;

    private Integer symptomId;
    private String symptomName;

    /** 是否核心症状：核心症状的鉴别价值高于边缘症状 */
    private Boolean isCardinal;

    /** 该症状对本病的典型程度 */
    private BigDecimal weight;
}
