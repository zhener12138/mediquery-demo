package com.mediquery.module.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 症状同义词：把口语收敛到标准症状。
 *
 * <p>用户不会说「失眠」，他会说「睡不着」「躺下两小时都睡不着」「一晚上醒好几次」。
 * 这一层就是把任意说法映射回 {@link Symptom#getId()} 的地方。
 *
 * <p>{@code weight} 表示归一强度：标准名 1.00，「睡不着」接近 1.00，
 * 「睡不好」只有 0.70（它也可能指的是「睡眠质量差」而不是「失眠」）。
 * 一句话命中多个症状时，按 weight 排序取前面的，而不是一把全召回。
 */
@Data
@TableName("symptom_alias")
public class SymptomAlias {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer symptomId;
    /** 口语说法 / 近义词 / 常见错别字写法 */
    private String alias;
    /** 归一强度 0.00-1.00 */
    private BigDecimal weight;
    private LocalDateTime createTime;
}
