package com.mediquery.module.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 症状主表：一个症状 = 一个独立概念。
 *
 * <p>为什么不继续用 {@code illness.symptom} 那个顿号串：
 * 字符串只能做「包含」匹配，做不了归一。用户说「睡不着」，
 * 库里写的是「失眠」，{@code LIKE '%睡不着%'} 永远命中不了 ——
 * 这就是改造前实测「失眠召回 0 条」的直接原因。
 */
@Data
@TableName("symptom")
public class Symptom {
    @TableId(type = IdType.AUTO)
    private Integer id;
    /** 标准症状名，如 失眠/咳嗽/胸痛 */
    private String name;
    /** 部位，如 头面部/胸部/腹部，用于粗分诊 */
    private String bodyPart;
    /** 类别，如 睡眠/呼吸/消化/疼痛，用于聚合展示 */
    private String category;
    /** 通俗解释，给模型做措辞参考 */
    private String description;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
