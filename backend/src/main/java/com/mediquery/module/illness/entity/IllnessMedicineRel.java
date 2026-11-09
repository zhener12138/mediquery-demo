package com.mediquery.module.illness.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 疾病-药品关联：一条带条件的边。
 *
 * <p>原表只有 {@code (illness_id, medicine_id)} 两个 ID。它的致命问题不是「信息少」，
 * 而是「信息少到会误导人」：阿莫西林（抗生素）被关联在病毒性感冒（病毒感染）上，
 * 模型照着念就是在给用户错误的用药暗示。
 *
 * <p>三个新字段让同一条边必须自己解释得通：
 * <ul>
 *   <li>{@code indication} —— 什么情况下才考虑用这个药。写不出这一句的关联，
 *       通常就是错关联；</li>
 *   <li>{@code lineOfTreatment} —— 0 未分级 / 1 一线 / 2 二线 / 3 医生评估后考虑；</li>
 *   <li>{@code evidenceLevel} —— 0 未标注 / 1 指南推荐 / 2 临床常用 / 3 经验性参考。</li>
 * </ul>
 *
 * <p>默认值给 0（未分级 / 未标注），是为了让「老数据没写」和「明确未分级」
 * 在语义上一致，不假装老数据有一线二线的结论。
 */
@Data
@TableName("illness_medicine_rel")
public class IllnessMedicineRel {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer illnessId;
    private Integer medicineId;
    /** 适应症说明：什么情况下才考虑用这个药 */
    private String indication;
    /** 治疗线: 0-未分级, 1-一线, 2-二线, 3-医生评估后考虑 */
    private Integer lineOfTreatment;
    /** 证据等级: 0-未标注, 1-指南推荐, 2-临床常用, 3-经验性参考 */
    private Integer evidenceLevel;
}
