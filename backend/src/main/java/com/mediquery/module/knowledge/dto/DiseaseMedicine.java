package com.mediquery.module.knowledge.dto;

import lombok.Data;

/**
 * 疾病-用药的一条边，连同药品本体一起取出。
 *
 * <p>为什么不复用 {@code Medicine} 实体：那上面没有 {@code indication / 治疗线 / 证据等级}
 * 这三列，而恰好是这三列决定了一句话该不该说、该怎么说。
 * 「阿莫西林」三个字单独出现是在暗示「感冒吃这个」；加上「仅确诊细菌感染时考虑」
 * 才是一句能对患者说的话。
 *
 * <p>为什么不查两次（先查关联再查药品）：候选疾病几十个，逐个查就是几十次往返。
 * 一条 JOIN 带 {@code IN} 就够，反正组装是在内存里做的。
 */
@Data
public class DiseaseMedicine {
    private Integer illnessId;
    private Integer medicineId;
    private String medicineName;
    private String effect;
    private String usageDosage;
    private String contraindication;
    private String interaction;
    /** 什么情况下才考虑用这个药 */
    private String indication;
    /** 治疗线: 0-未分级, 1-一线, 2-二线, 3-医生评估后考虑 */
    private Integer lineOfTreatment;
    /** 证据等级: 0-未标注, 1-指南推荐, 2-临床常用, 3-经验性参考 */
    private Integer evidenceLevel;
}
