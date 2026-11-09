package com.mediquery.module.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 疾病-症状关联：一条带权重的边。
 *
 * <p>三个属性各自解决一个具体问题，不是「加了显得专业」：
 * <ul>
 *   <li>{@code weight} —— 「发热」几乎每种病都有，区分度低；「骨擦音」只在骨折出现，
 *       区分度高。不加权重的话，感冒会永远排第一，因为它命中的词最多。</li>
 *   <li>{@code isCardinal} —— 核心症状。问「你有没有 A」时优先问核心症状，
 *       这是把问诊从「一轮接一轮瞎问」变成「有方向地问」的关键。</li>
 *   <li>{@code isRedFlag} —— 这个症状意味着需要立即就医。它是
 *       {@code TriageRuleEngine} 之外的第二层：
 *       规则层不查库、绝不会因为数据库挂了而静音；图谱这层则让模型
 *       能回答「哪些病自带红旗症状」。</li>
 * </ul>
 */
@Data
@TableName("disease_symptom")
public class DiseaseSymptom {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer illnessId;
    private Integer symptomId;
    /** 该症状对本病的提示权重 0.00-1.00 */
    private BigDecimal weight;
    /** 是否核心症状 */
    private Boolean isCardinal;
    /** 是否红旗症状 */
    private Boolean isRedFlag;
    private LocalDateTime createTime;
}
