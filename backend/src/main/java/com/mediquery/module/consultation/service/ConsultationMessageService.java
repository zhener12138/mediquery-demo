package com.mediquery.module.consultation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediquery.common.exception.AiServiceException;
import com.mediquery.common.exception.BusinessException;
import com.mediquery.config.AsyncConfig;
import com.mediquery.integration.ai.DashScopeClient;
import com.mediquery.integration.ai.KnowledgeRetrievalService;
import com.mediquery.module.consultation.entity.AiChatRecord;
import com.mediquery.module.consultation.mapper.AiChatRecordMapper;
import com.mediquery.module.consultation.entity.Consultation;
import com.mediquery.module.consultation.triage.TriageResult;
import com.mediquery.module.consultation.triage.TriageRuleEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 问诊对话的消息流。
 *
 * <h3>与 {@code AiDoctorService.chatStream} 的关系</h3>
 *
 * <p>这个类是它的演进版，不是复制品。三处实质差别：
 *
 * <ol>
 *   <li><b>消息归属一张有状态的单子</b>。发消息前必须过 {@code requireActive}：
 *       已完结的单子不能再聊。老的 `AiDoctorService` 没有任何状态概念，
 *       用户可以在一个「已经结束」的界面上继续说话，而小结早就生成完了。</li>
 *   <li><b>刷新 {@code last_active_at}</b>。超时判定靠这个字段，不刷新的话
 *       每张单子都会在创建 30 分钟后被误关。</li>
 *   <li><b>用专门的线程池</b>。老实现是 `new Thread(...).start()` —— 每次对话
 *       新建一个裸线程，没有上限、没有排队、线程名全是 `Thread-N`。
 *       并发一高就是 OOM 或者线程数打满。</li>
 * </ol>
 *
 * <h3>真流式（阶段三 3.5）</h3>
 *
 * <p>阶段二这里是「同步拿到全文 → 按 3 个字符切片 → {@code Thread.sleep(20)} 假装打字」。
 * 现在走 {@code DashScopeClient.chatWithHistoryStreaming}，模型吐一片就转发一片，
 * 首字节延迟从「整段生成完」降到「第一帧到达」。
 *
 * <p>改造带来两个同步调用不存在的状态，都在 {@code streamInWorker} 里单独处理了：
 * <ul>
 *   <li><b>客户端中途断开</b>：{@code send} 抛异常 → {@link DashScopeClient.ChunkConsumerException}。
 *       这不是 AI 故障，不能报成「AI 服务不可用」，也不能丢弃已经生成的内容。</li>
 *   <li><b>半截入库</b>：断开时把已收到的部分存下来，用户重新进来能看到真实生成过的内容。</li>
 * </ul>
 */
@Service
public class ConsultationMessageService {

    private static final Logger log = LoggerFactory.getLogger(ConsultationMessageService.class);

    /** SSE 连接超时。要大于 AI 调用的最坏耗时。 */
    private static final long SSE_TIMEOUT_MS = 120_000L;

    /** 一次对话带多少条历史给模型。 */
    private static final int HISTORY_LIMIT = 20;

    private final ConsultationService consultationService;
    private final DashScopeClient dashScopeClient;
    private final KnowledgeRetrievalService knowledgeService;
    private final AiChatRecordMapper chatRecordMapper;
    private final ObjectMapper objectMapper;
    private final ThreadPoolExecutor sseExecutor;
    private final TriageRuleEngine triageRuleEngine;

    public ConsultationMessageService(ConsultationService consultationService,
                                      DashScopeClient dashScopeClient,
                                      KnowledgeRetrievalService knowledgeService,
                                      AiChatRecordMapper chatRecordMapper,
                                      ObjectMapper objectMapper,
                                      @Qualifier(AsyncConfig.SSE_EXECUTOR) ThreadPoolExecutor sseExecutor,
                                      TriageRuleEngine triageRuleEngine) {
        this.consultationService = consultationService;
        this.dashScopeClient = dashScopeClient;
        this.knowledgeService = knowledgeService;
        this.chatRecordMapper = chatRecordMapper;
        this.objectMapper = objectMapper;
        this.sseExecutor = sseExecutor;
        this.triageRuleEngine = triageRuleEngine;
    }

    /**
     * 发一条消息并流式返回回答。
     *
     * <p>方法本身**同步**完成三件事（状态校验、存用户消息、刷新活跃时间）之后
     * 才把 AI 调用丢进线程池 —— 这样前端如果收到了 409，说明消息压根没入库，
     * 不存在「报错了但话已经记进去了」这种状态。
     *
     * <p>SSE 一旦开始推送，HTTP 状态码已经发出去了。此后的任何错误都只能通过
     * `type=error` 事件传递，不能再改状态码。所以状态校验必须在返回 emitter 之前做。
     */
    public SseEmitter stream(Integer consultationId, Integer patientId, String content) {
        if (content == null || content.isBlank()) {
            throw BusinessException.badRequest("消息内容不能为空");
        }

        // 这一步会抛 404/403/409 —— 都在 SSE 开始之前，前端能拿到正确状态码
        Consultation c = consultationService.requireActive(consultationId, patientId);
        String sid = c.getSessionId();

        // 预筛放在这里同步做：它是纯内存计算（微秒级），不需要数据库也不需要网络，
        // 但结果决定了第一帧要不要报高危。丢到线程池里做只会让高危提示晚到几毫秒。
        TriageResult triage = triageRuleEngine.triage(content);

        saveRecord(patientId, sid, "user", content.trim());
        consultationService.touch(consultationId);

        var emitter = new SseEmitter(SSE_TIMEOUT_MS);
        var secCtx = SecurityContextHolder.getContext();

        try {
            sseExecutor.execute(() -> streamInWorker(emitter, secCtx, patientId, sid, content, triage));
        } catch (RejectedExecutionException e) {
            // 线程池满：此时消息已经存了，但回答不会来。
            // 存了用户消息却回不了，比什么都不存更糟 —— 用户刷新会看到自己孤零零一句话。
            log.warn("SSE 线程池已满 consultationId={}，拒绝请求", consultationId);
            throw BusinessException.badRequest("当前问诊人数较多，请稍后再试");
        }
        return emitter;
    }

    private void streamInWorker(SseEmitter emitter, SecurityContext secCtx,
                                Integer patientId, String sid, String message, TriageResult triage) {
        SecurityContextHolder.setContext(secCtx);
        try {
            // start 事件先发：把 HTTP 响应头冲出去，避免异步分派被安全过滤器二次拦截
            // 同时把预筛结论第一时间送到前端 —— 高危提示要在第一个字吐出来之前就出现，
            // 不能让用户为了看到「快去医院」而先读完整段 AI 回复。
            var start = new HashMap<String, Object>();
            start.put("type", "start");
            start.put("sessionId", sid);
            start.put("triageSeverity", triage.severity());
            start.put("redFlags", triage.flags());
            start.put("suggestDept", triage.departments());
            start.put("advice", triage.advice());

            var history = recentHistory(patientId, sid);

            // 1.1 结构化采集：信息卡缺什么，直接告诉模型，让它主动去问，
            // 而不是靠用户自己想起来说「哦对了我对青霉素过敏」。
            // 已填的槽位作为 knownFacts 给模型当既有事实；全空时给一句空串不占 token。
            var intake = consultationService.getIntakeSlots(patientId, sid);
            String knownFacts = buildKnownFacts(intake, message);
            // 命中的条目名一起带出：AI 说「考虑病毒性感冒」时，用户能看到这句有出处，
            // 而不是一段无法核实的黑盒回答。来源标注放在 start 事件里，
            // 因为它不依赖模型 —— 检索命中那一刻就确定了，比等第一个 chunk 更早。
            var retrieved = knowledgeService.retrieveWithSources(message);
            var knowledge = triage.toPromptPrefix() + knownFacts + retrieved.context();
            start.put("sources", retrieved.sources());
            send(emitter, start);

            // 真流式：模型吐一片就转发一片，不再等全文。
            // onChunk 里 send 失败（客户端断开）会抛异常，被 chatWithHistoryStreaming
            // 包成 ChunkConsumerException 透出 —— 下面是单独的 catch 分支，不能当成 AI 故障。
            String reply = dashScopeClient.chatWithHistoryStreaming(
                    sid, message, history, knowledge,
                    piece -> {
                        try {
                            send(emitter, Map.of("sessionId", sid, "chunk", piece));
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    });

            // 空回复也要入库：用户刷新后应该看到「这条问过、AI 没答」，
            // 而不是一段凭空消失的对话。
            saveRecord(patientId, sid, "assistant", reply == null ? "" : reply);

            // meta 事件：severity 是「整段问诊结束后的回顾性判断」，这里仍然是 null，
            // 让前端不必为「有没有 meta」写两套解析。而本轮预筛的结论放在 triageSeverity，
            // 两者字段名不同、含义不同，前端也不会误把预筛当成最终结论。
            var meta = new HashMap<String, Object>();
            meta.put("type", "meta");
            meta.put("severity", null);
            meta.put("triageSeverity", triage.severity());
            meta.put("suggestDept", triage.departments());
            meta.put("note", "严重度在问诊完结、生成小结后确定");
            send(emitter, meta);

            send(emitter, Map.of("type", "end", "summarizing", false));
            emitter.complete();
        } catch (DashScopeClient.ChunkConsumerException e) {
            // 客户端断开：不是 AI 的错，也不该报 error 事件（没人收）。
            // 半截回复仍然入库 —— 用户重新进来能看到已经生成的部分，
            // 而不是一个空洞的会话。
            log.info("SSE 推送中断（客户端可能已断开）session={} partialLen={}",
                    sid, e.getPartialText().length());
            saveRecord(patientId, sid, "assistant", e.getPartialText());
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 连接已断，complete 失败是正常的
            }
        } catch (AiServiceException e) {
            log.warn("AI 服务不可用 consultationSession={} code={} msg={}",
                    sid, e.getUpstreamCode(), e.getMessage());
            sendError(emitter, sid, e);
        } catch (Exception e) {
            log.error("SSE 流式输出异常 session={}", sid, e);
            sendError(emitter, sid, null);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 取最近 N 条历史并按时间正序返回。
     *
     * <p>必须 desc + limit 再反转：直接 asc + limit 拿到的是**最早**的 N 条，
     * 长会话里模型永远看不到最新内容（这是老实现的真实 bug，已在本类修正）。
     * 加 id 作二级排序是因为 createTime 是秒级精度，同秒插入顺序不稳定。
     */
    private List<AiChatRecord> recentHistory(Integer patientId, String sid) {
        var recent = chatRecordMapper.selectList(new LambdaQueryWrapper<AiChatRecord>()
                .eq(AiChatRecord::getUserId, patientId)
                .eq(AiChatRecord::getSessionId, sid)
                .orderByDesc(AiChatRecord::getCreateTime)
                .orderByDesc(AiChatRecord::getId)
                .last("LIMIT " + HISTORY_LIMIT));
        var ordered = new ArrayList<>(recent);
        Collections.reverse(ordered);
        return ordered;
    }

    private void send(SseEmitter emitter, Map<String, ?> payload) throws Exception {
        emitter.send(SseEmitter.event().name("delta")
                .data(objectMapper.writeValueAsString(payload)));
    }

    /**
     * 下发错误事件后正常关闭。
     *
     * <p>不用 {@code completeWithError}：那会让 Spring 走异常解析流程，
     * 客户端只看到连接断开，拿不到可读原因。发一条 `type=error` 再 complete，
     * 前端能区分「AI 故障」和「AI 回答了」。
     */
    private void sendError(SseEmitter emitter, String sid, AiServiceException e) {
        try {
            var payload = new HashMap<String, Object>();
            payload.put("type", "error");
            payload.put("sessionId", sid);
            payload.put("message", e != null ? e.getMessage() : "AI 服务暂时不可用，请稍后再试");
            payload.put("code", e != null ? e.getUpstreamCode() : "UNKNOWN");
            payload.put("retryable", e != null && e.getUpstreamStatus() == 429);
            send(emitter, payload);
            emitter.complete();
        } catch (Exception sendFail) {
            log.warn("下发错误事件失败", sendFail);
            emitter.completeWithError(sendFail);
        }
    }

    private void saveRecord(Integer patientId, String sid, String role, String content) {
        var record = new AiChatRecord();
        record.setUserId(patientId);
        record.setSessionId(sid);
        record.setRole(role);
        record.setContent(content);
        chatRecordMapper.insert(record);
    }

    // ───────────── 2.5 附带：会话历史查询（供前端刷新后恢复对话） ─────────────

    /**
     * 拉取一张单子的全部消息。
     *
     * <p>上限 200 条：一次问诊不太可能更长了，超过的部分前端也用不上，
     * 而数据库里可能有几万条历史会话，不设限就是一次全表扫描。
     */
    public List<Map<String, Object>> history(Integer consultationId, Integer patientId) {
        Consultation c = consultationService.requireOwnedForRead(consultationId, patientId);
        var records = chatRecordMapper.selectList(new LambdaQueryWrapper<AiChatRecord>()
                .eq(AiChatRecord::getUserId, patientId)
                .eq(AiChatRecord::getSessionId, c.getSessionId())
                .orderByAsc(AiChatRecord::getCreateTime)
                .orderByAsc(AiChatRecord::getId)
                .last("LIMIT 200"));
        var out = new ArrayList<Map<String, Object>>(records.size());
        for (var r : records) {
            var m = new HashMap<String, Object>();
            m.put("role", r.getRole());
            m.put("content", r.getContent());
            m.put("createTime", r.getCreateTime());
            out.add(m);
        }
        return out;
    }

    /**
     * 1.1 结构化采集：把信息卡拼成模型能直接用的「已知事实 + 待追问清单」。
     *
     * <p>关键取舍：已填的写进 facts（模型不再重复问），没填的写进 todo
     * （模型在对话里自然追问，最多点一次，别变成查户口）。
     * 全空返回空串 —— 首轮消息用户刚开单就来问，信息卡一片空白是常态，
     * 这时拼一段「用户什么都没填」没有信息量，只浪费 token。
     *
     * <p>{@code historyFlags} 落库时是 JSON 数组字符串，这里解析失败就当没填，
     * 一个脏字段不应该打断一次问诊。
     */
    private String buildKnownFacts(Consultation c, String currentMessage) {
        List<String> facts = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        if (c.getDurationDesc() != null && !c.getDurationDesc().isBlank()) {
            facts.add("病程：" + c.getDurationDesc());
        } else {
            missing.add("症状持续多久了");
        }
        if (c.getSeverityScale() != null) {
            facts.add("用户自评严重度 " + c.getSeverityScale() + "/10");
        }
        if (c.getOnsetType() != null && !c.getOnsetType().isBlank()) {
            facts.add("突发".equals(c.getOnsetType()) || "sudden".equals(c.getOnsetType())
                    ? "起病方式：突然发作" : "起病方式：逐渐加重");
        }
        String hist = c.getHistoryFlags();
        if (hist != null && !hist.isBlank()) {
            try {
                List<String> flags = objectMapper.readValue(hist,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                if (!flags.isEmpty()) {
                    facts.add("既往史/过敏史/长期用药：" + String.join("；", flags));
                }
            } catch (Exception ignored) {
                // 脏数据当没填
            }
        } else if (hist == null) {
            missing.add("有没有过敏、慢性病或长期在吃的药");
        }

        if (facts.isEmpty() && missing.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (!facts.isEmpty()) {
            sb.append("【用户已提供的信息】\n");
            for (String f : facts) {
                sb.append("- ").append(f).append("\n");
            }
            sb.append("以下对话不要重复问这些已确认的信息。\n");
        }
        if (!missing.isEmpty()) {
            sb.append("【还需了解（自然地在对话中追问，本轮最多问一项，用户提到相关内容时才问）】\n");
            for (String m : missing) {
                sb.append("- ").append(m).append("\n");
            }
        }
        sb.append("\n");
        return sb.toString();
    }
}
