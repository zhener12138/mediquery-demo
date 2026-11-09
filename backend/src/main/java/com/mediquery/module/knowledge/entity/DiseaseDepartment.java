package com.mediquery.module.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 疾病-科室关联：一个病可以挂多个科，且该有优先级。
 *
 * <p>老结构只有 {@code illness.category_id}，一个病一个科，而且那是「分类」
 * 不是「就诊科室」。真实主诉经常跨科：「胸痛放射到左肩」先心内科、急诊兜底；
 * 「反复口腔溃疡伴皮疹」口腔科加风湿免疫科。
 *
 * <p>{@code priority} 越小越优先，1 = 首选科室。前端只渲染前两个，
 * 库里可以留三个，方便模型判断「还有别的可能」。
 */
@Data
@TableName("disease_department")
public class DiseaseDepartment {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer illnessId;
    /** 科室名，取值与 illness_category.name 对齐 */
    private String department;
    /** 优先级：1-首选，数字越大越次要 */
    private Integer priority;
    /** 为什么建议挂这个科，给模型做解释用 */
    private String reason;
    private LocalDateTime createTime;
}
