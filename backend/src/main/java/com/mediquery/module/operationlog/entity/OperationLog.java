package com.mediquery.module.operationlog.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志。
 *
 * <p>这张表从项目第一天就建在 {@code init.sql} 里，但一直没有任何代码写过一行 ——
 * 是真正的死表。阶段三 3.7 把它接上。
 *
 * <h3>为什么记这几个字段，不记别的</h3>
 *
 * <p>审计日志的价值在于回答「谁、什么时候、对什么东西、做了什么」。
 * 这四个问题的答案就是 {@code user_id} / {@code create_time} / {@code target} / {@code action}。
 * 至于「改成什么值」不记 —— 那需要存前后快照，属于变更审计（data audit）的范畴，
 * 和操作审计（operation audit）是两件事，混在一起会让这张表无限膨胀。
 * 真需要看某个字段的历史值，应该给那张业务表加版本列，而不是往日志表里塞 JSON。
 *
 * <p>{@code username} 冗余存一份：用户被删掉之后，{@code user_id} 就查不到人了，
 * 但「2026-11-06 张三删了一条疾病」这件事仍然要能读出来。
 */
@Data
@TableName("sys_operation_log")
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private Integer userId;

    private String username;

    /** 操作模块，如「问诊」「疾病管理」。 */
    private String module;

    /** 操作动作，如「完结问诊」「删除」。 */
    private String action;

    /** 操作对象，如「POST /api/v1/consultations/12/complete」。 */
    private String target;

    private String ipAddress;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
