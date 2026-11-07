package com.mediquery.module.consultation.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问诊单。
 *
 * <p>这是「在线问诊」的核心实体：一次问诊 = 一张单，有明确的开始和结束。
 *
 * <p>与 {@code AiChatRecord} 的关系：那个只记「谁说了什么」，没有「这次问诊结束了没有」。
 * 消息流仍然存在 {@code ai_chat_record} 里（通过 {@code sessionId} 关联），
 * 本表只负责问诊单本身的生命周期。
 *
 * <p>三个状态字段互相正交，不要合并：
 * <ul>
 *   <li>{@code status} —— 单子本身的生老病死</li>
 *   <li>{@code summaryStatus} —— 异步小结的生成进度</li>
 *   <li>{@code severity} —— 病情严重度，纯 AI 下只用于展示紧急提示</li>
 * </ul>
 */
@Data
@TableName("consultation")
public class Consultation {

    @TableId(type = IdType.AUTO)
    private Integer id;

    /** 患者 ID。只有本人能读写自己的单（靠 ConsultationGuard.isOwner 校验）。 */
    private Integer patientId;

    /**
     * 会话 ID，与 {@code ai_chat_record.session_id} 对应，也是 RAG 上下文的 key。
     *
     * <p>数据库上是 UNIQUE：前端重试/双击不会创建两张单，靠 DuplicateKeyException 兜住。
     */
    private String sessionId;

    /** 主诉。用户开单时填的一句话描述，例如「咳嗽三天，有低烧」。 */
    private String chiefComplaint;

    /** 病程描述。可空；「三天」「从上周开始」这类，模型拿不到就会自己去问。 */
    private String durationDesc;

    /** 自评严重度 0-10，10 最重。可空；不做任何自动分诊决策，只给模型当参考。 */
    private Integer severityScale;

    /** 既往史/过敏史/长期用药，JSON 数组字符串，如 ["青霉素过敏","高血压 5 年"]。可空。 */
    private String historyFlags;

    /** 起病方式：sudden 突然 / gradual 渐进。可空。 */
    private String onsetType;

    /** @see ConsultationStatus */
    private Integer status;

    /** @see Severity */
    private Integer severity;

    /** @see SummaryStatus */
    private Integer summaryStatus;

    /** AI 结构化小结，JSON 字符串。结构见设计文档 6.5 节。 */
    private String aiSummary;

    /** 最终结论。完结时如果有小结，由小结落成一句话；没有则为 null。 */
    private String conclusion;

    /**
     * 最后活动时间。
     *
     * <p>刻意独立于 {@code updateTime}：{@code updateTime} 会被任何 UPDATE 刷新（含状态变更），
     * 而超时判定要看的是「最后一条消息时间」，语义不同。混用会导致
     * 「定时任务改了状态 → updateTime 变新 → 永远不超时」这类 bug。
     */
    private LocalDateTime lastActiveAt;

    /** 关闭时间。进入任一终态时写入。 */
    private LocalDateTime closedAt;

    /**
     * 乐观锁版本号。
     *
     * <p>并发完结（患者点完结 + 定时任务判超时同时发生）必须有版本保护。
     * 生效前提是 {@code MyBatisPlusConfig} 里注册了 {@code OptimisticLockerInnerInterceptor}。
     */
    @Version
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
