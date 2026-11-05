package com.mediquery.module.consultation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediquery.common.exception.AiServiceException;
import com.mediquery.config.AsyncConfig;
import com.mediquery.integration.ai.DashScopeClient;
import com.mediquery.module.ai_doctor.entity.AiChatRecord;
import com.mediquery.module.ai_doctor.mapper.AiChatRecordMapper;
import com.mediquery.module.consultation.entity.Consultation;
import com.mediquery.module.consultation.entity.Severity;
import com.mediquery.module.consultation.entity.SummaryStatus;
import com.mediquery.module.consultation.mapper.ConsultationMapper;
import com.mediquery.module.consultation.statemachine.ConsultationStateMachine;
import com.mediquery.module.consultation.vo.SummaryVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 问诊小结的生成服务。
 *
 * <h3>为什么单独一个 Service，而不是塞进 ConsultationService</h3>
 *
 * <p>小结生成有三件事和主流程不同：① 它是异步的；② 它调外部 AI，会失败、会慢；
 * ③ 它的失败不能回滚主流程。把这三件事和「状态流转」放在同一个类里，
 * 会让「哪些代码在事务里、哪些在事务外」变得难以一眼看清 —— 而判断这一点
 * 恰恰是这类代码最容易出错的地方。
 *
 * <h3>绝对不要给这个类的方法加 @Transactional</h3>
 *
 * <p>{@code generateAsync} 里的 AI 调用可能持续 30 秒。如果包在事务里，
 * 那 30 秒内它会一直占着一个数据库连接（Druid {@code max-active: 20}）。
 * 十几个小结并发生成就能把连接池吃干，整个应用的其它请求全部阻塞在
 * 获取连接上 —— 一个后台功能把全站拖死。
 */
@Service
public class SummaryService {

    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);

    private static final String PROMPT_TEMPLATE = """
            你是医疗问诊助手。下面是一位患者与你的完整对话记录。请根据这段对话，
            输出一个 JSON 对象作为这次问诊的小结。只输出 JSON，不要任何解释文字、
            不要 markdown 代码块标记。

            JSON 结构必须是：
            {
              "chiefComplaint": "一句话概括患者主诉",
              "possibleDirections": ["可能的病因或疾病方向，最多3条"],
              "suggestedDepartments": ["建议挂号的科室，最多2个"],
              "medicationHints": ["生活或用药方面的建议，最多3条"],
              "severity": 0或1或2,
              "severityReason": "给出该严重度的简短理由",
              "redFlags": ["需要立即就医的危险信号，没有就空数组"]
            }

            severity 判定标准（必须严格遵守，宁低勿高）：
              0 = 轻微，可自行观察或药店购药处理
              1 = 中度，建议近期就医，但无紧急情况
              2 = 高度，存在需要立即就医的危险信号（如持续高热不退、呼吸困难、
                  意识改变、剧烈胸痛、大出血、剧烈腹痛、抽搐等）

            如果对话信息不足以判断，severity 必须是 0。

            对话记录：
            %s
            """;

    private final ConsultationMapper consultationMapper;
    private final AiChatRecordMapper chatRecordMapper;
    private final DashScopeClient dashScopeClient;
    private final ObjectMapper objectMapper;
    private final ThreadPoolExecutor summaryExecutor;

    public SummaryService(ConsultationMapper consultationMapper,
                          AiChatRecordMapper chatRecordMapper,
                          DashScopeClient dashScopeClient,
                          ObjectMapper objectMapper,
                          @Qualifier(AsyncConfig.SUMMARY_EXECUTOR) ThreadPoolExecutor summaryExecutor) {
        this.consultationMapper = consultationMapper;
        this.chatRecordMapper = chatRecordMapper;
        this.dashScopeClient = dashScopeClient;
        this.objectMapper = objectMapper;
        this.summaryExecutor = summaryExecutor;
    }

    /**
     * 提交一个异步小结生成任务。
     *
     * <p><b>调用时机必须是事务提交之后</b>（{@code afterCommit}）。
     * 在事务内提交的话，异步线程可能读到 {@code summary_status} 还是 NONE 的旧快照，
     * 于是它自己那次「置为 GENERATING」的条件更新会失败。
     *
     * <p>线程池满时任务被拒绝，此时把状态置为 FAILED，让用户能点「重新生成」，
     * 而不是永远卡在「生成中」。
     */
    public void generateAsync(Integer consultationId, String sessionId) {
        try {
            summaryExecutor.execute(() -> generate(consultationId, sessionId));
        } catch (RejectedExecutionException e) {
            log.warn("小结任务被线程池拒绝 consultationId={}，置为 FAILED", consultationId);
            markFailed(consultationId);
        }
    }

    /**
     * 真正干活的方法。注意：**没有 @Transactional**，理由见类注释。
     */
    void generate(Integer consultationId, String sessionId) {
        Consultation c = consultationMapper.selectById(consultationId);
        if (c == null) {
            log.warn("小结生成跳过：问诊单不存在 id={}", consultationId);
            return;
        }
        // 状态机校验：只有 GENERATING 状态才该走到这里。
        // 如果是用户连点两次「重新生成」，第二次到这里时状态已是 GENERATING，
        // 校验会抛异常 —— 但这不该污染日志，属于正常的并发收敛。
        if (!Integer.valueOf(SummaryStatus.GENERATING).equals(c.getSummaryStatus())) {
            log.debug("小结生成跳过：状态已变化 id={} summaryStatus={}", consultationId, c.getSummaryStatus());
            return;
        }

        try {
            String transcript = buildTranscript(c.getPatientId(), sessionId);
            if (transcript.isBlank()) {
                // 一句话都没说过就完结了，没有什么可总结的
                markDone(consultationId, SummaryVO.empty(c.getChiefComplaint()));
                return;
            }

            String raw = dashScopeClient.chatWithHistory(
                    sessionId, String.format(PROMPT_TEMPLATE, transcript), List.of(),
                    // 小结是「回顾」，不需要再检索知识库：对话里已经包含了当时检索到的内容。
                    // 传空字符串让 chatWithHistory 走缓存回落分支。
                    "");

            SummaryVO vo = parse(raw, c.getChiefComplaint());
            markDone(consultationId, vo);
            log.info("小结生成成功 consultationId={} severity={}", consultationId, vo.getSeverity());
        } catch (AiServiceException e) {
            // AI 故障：置 FAILED，用户可以重试。不抛出去 —— 这是后台线程，没人接。
            log.warn("小结生成失败（AI 侧） consultationId={} code={} msg={}",
                    consultationId, e.getUpstreamCode(), e.getMessage());
            markFailed(consultationId);
        } catch (Exception e) {
            log.error("小结生成失败 consultationId={}", consultationId, e);
            markFailed(consultationId);
        }
    }

    /**
     * 把对话记录拼成一段可读文本，喂给模型。
     *
     * <p>为什么不直接把历史消息当 messages 传：这里要的是「总结这段对话」，
     * 不是「继续这段对话」。做成一条带对话全文的 user message，模型更倾向于
     * 输出结构化结果，而不是接着聊。
     */
    private String buildTranscript(Integer patientId, String sessionId) {
        var records = chatRecordMapper.selectList(new LambdaQueryWrapper<AiChatRecord>()
                .eq(AiChatRecord::getUserId, patientId)
                .eq(AiChatRecord::getSessionId, sessionId)
                .orderByAsc(AiChatRecord::getCreateTime)
                .orderByAsc(AiChatRecord::getId)
                .last("LIMIT 200"));
        StringBuilder sb = new StringBuilder();
        for (var r : records) {
            String who = "user".equals(r.getRole()) ? "患者" : "助手";
            sb.append(who).append("：").append(r.getContent()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 解析模型输出。
     *
     * <p>模型经常不听话：可能包一层 ```json 代码块，可能在 JSON 前后加解释。
     * 所以先剥掉代码块标记，再截取第一个 { 到最后一个 } 之间的内容。
     *
     * <p><b>解析失败一律降级为 severity=LOW</b>，绝不猜成 HIGH。
     * 假的紧急告警比漏报更糟：它会让用户对告警脱敏，下次真出事就不当回事了。
     */
    SummaryVO parse(String raw, String fallbackComplaint) {
        if (raw == null || raw.isBlank()) {
            log.warn("小结内容为空，降级");
            return SummaryVO.empty(fallbackComplaint);
        }
        try {
            String json = stripCodeFence(raw);
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start < 0 || end <= start) {
                log.warn("小结中找不到 JSON 对象，降级 raw={}", truncate(raw));
                return SummaryVO.empty(fallbackComplaint);
            }
            var vo = objectMapper.readValue(json.substring(start, end + 1), SummaryVO.class);

            // 缺字段的补齐。模型可能只输出一半字段，不能因此让前端拿到 null 数组。
            if (vo.getChiefComplaint() == null || vo.getChiefComplaint().isBlank()) {
                vo.setChiefComplaint(fallbackComplaint);
            }
            if (vo.getPossibleDirections() == null) vo.setPossibleDirections(List.of());
            if (vo.getSuggestedDepartments() == null) vo.setSuggestedDepartments(List.of());
            if (vo.getMedicationHints() == null) vo.setMedicationHints(List.of());
            if (vo.getRedFlags() == null) vo.setRedFlags(List.of());
            if (vo.getSeverityReason() == null) vo.setSeverityReason("");

            // 严重度越界归一化
            vo.setSeverity(Severity.normalize(vo.getSeverity()));
            // 免责声明无条件覆盖，不信模型输出
            vo.setDisclaimer(SummaryVO.DISCLAIMER_TEXT);
            return vo;
        } catch (Exception e) {
            log.warn("小结 JSON 解析失败，降级 raw={}", truncate(raw), e);
            return SummaryVO.empty(fallbackComplaint);
        }
    }

    /** 剥掉 markdown 代码块标记（```json ... ```）。 */
    private static String stripCodeFence(String s) {
        String t = s.trim();
        if (!t.startsWith("```")) return t;
        int firstNewline = t.indexOf('\n');
        if (firstNewline < 0) return t;
        int closing = t.lastIndexOf("```");
        if (closing <= firstNewline) return t.substring(firstNewline + 1);
        return t.substring(firstNewline + 1, closing);
    }

    private static String truncate(String s) {
        if (s == null) return "null";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    /**
     * 写入成功结果。
     *
     * <p>用条件更新（{@code WHERE summary_status = GENERATING}）而不是 {@code updateById}：
     * 如果这期间用户点了取消，或者在极端情况下单子被删了，这次更新不该生效。
     */
    private void markDone(Integer consultationId, SummaryVO vo) {
        try {
            String json = objectMapper.writeValueAsString(vo);
            int affected = consultationMapper.update(null, Wrappers.<Consultation>lambdaUpdate()
                    .eq(Consultation::getId, consultationId)
                    .eq(Consultation::getSummaryStatus, SummaryStatus.GENERATING)
                    .set(Consultation::getSummaryStatus, SummaryStatus.DONE)
                    .set(Consultation::getAiSummary, json)
                    .set(Consultation::getSeverity, vo.getSeverity())
                    .set(Consultation::getConclusion, buildConclusion(vo)));
            if (affected == 0) {
                log.warn("小结写入未生效（状态已变化） consultationId={}", consultationId);
            }
        } catch (Exception e) {
            log.error("小结写入失败 consultationId={}", consultationId, e);
            markFailed(consultationId);
        }
    }

    /** 用可能的病因方向拼一句结论。空列表时给一句中性的话，不要留 null。 */
    private static String buildConclusion(SummaryVO vo) {
        if (vo.getPossibleDirections() == null || vo.getPossibleDirections().isEmpty()) {
            return "本次问诊未获得明确的方向性判断，建议就医面诊。";
        }
        return "可能的病因方向：" + String.join("、", vo.getPossibleDirections()) + "。";
    }

    private void markFailed(Integer consultationId) {
        try {
            consultationMapper.update(null, Wrappers.<Consultation>lambdaUpdate()
                    .eq(Consultation::getId, consultationId)
                    .eq(Consultation::getSummaryStatus, SummaryStatus.GENERATING)
                    .set(Consultation::getSummaryStatus, SummaryStatus.FAILED));
        } catch (Exception e) {
            // 连标记失败都失败了（数据库不可用），只能记日志等人工处理
            log.error("标记小结失败状态时出错 consultationId={}", consultationId, e);
        }
    }

    /** 供测试与重试接口使用：校验并返回可重试状态。 */
    static boolean isRetryable(int summaryStatus) {
        return summaryStatus == SummaryStatus.FAILED;
    }

    /** 是否已终结（终态或成功），用于避免重复生成。 */
    static boolean isSettled(int summaryStatus) {
        return summaryStatus == SummaryStatus.DONE;
    }

    // 让状态机在类内可被静态分析看到依赖，且防止误删 import
    @SuppressWarnings("unused")
    private static final Class<?> STATE_MACHINE_MARKER = ConsultationStateMachine.class;
}
