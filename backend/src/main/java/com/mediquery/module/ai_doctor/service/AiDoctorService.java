package com.mediquery.module.ai_doctor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mediquery.integration.ai.DashScopeClient;
import com.mediquery.integration.ai.KnowledgeRetrievalService;
import com.mediquery.module.ai_doctor.entity.AiChatRecord;
import com.mediquery.module.ai_doctor.mapper.AiChatRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;


import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI智能医生服务：RAG 检索增强 + 多轮对话历史 + SSE流式。
 */
@Service
public class AiDoctorService {
    private static final Logger log = LoggerFactory.getLogger(AiDoctorService.class);

    private final DashScopeClient dashScopeClient;
    private final KnowledgeRetrievalService knowledgeService;
    private final AiChatRecordMapper chatRecordMapper;

    public AiDoctorService(DashScopeClient dashScopeClient,
                           KnowledgeRetrievalService knowledgeService,
                           AiChatRecordMapper chatRecordMapper) {
        this.dashScopeClient = dashScopeClient;
        this.knowledgeService = knowledgeService;
        this.chatRecordMapper = chatRecordMapper;
    }

    /**
     * RAG + 多轮对话：
     * 1. 存用户消息
     * 2. 查当前 session 历史（最近20条）
     * 3. 检索疾病/药品知识
     * 4. 调千问（带历史+知识上下文）
     * 5. 缓存知识上下文到 Redis
     * 6. 存 AI 回复
     */
    public String chat(Integer userId, String sessionId, String message) {
        saveRecord(userId, sessionId, "user", message);

        // 获取当前 session 的对话历史
        var history = getSessionHistory(userId, sessionId);

        // 检索知识库
        var knowledge = knowledgeService.retrieve(message);

        // 调用大模型（带历史对话 + 知识上下文）
        var reply = dashScopeClient.chatWithHistory(sessionId, message, history, knowledge);

        saveRecord(userId, sessionId, "assistant", reply);
        return reply;
    }

    /**
     * 获取同一 session 的最近 20 条对话历史，按时间正序排列。
     */
    private List<AiChatRecord> getSessionHistory(Integer userId, String sessionId) {
        return chatRecordMapper.selectList(new LambdaQueryWrapper<AiChatRecord>()
                .eq(AiChatRecord::getUserId, userId)
                .eq(AiChatRecord::getSessionId, sessionId)
                .orderByAsc(AiChatRecord::getCreateTime)
                .last("LIMIT 20"));
    }

    /** SSE 流式对话（RAG + 多轮） */
    public SseEmitter chatStream(Integer userId, String sessionId, String message) {
        var emitter = new SseEmitter(120000L);
        var sid = sessionId != null ? sessionId : UUID.randomUUID().toString();
        saveRecord(userId, sid, "user", message);
        new Thread(() -> {
            try {
                var history = getSessionHistory(userId, sid);
                var knowledge = knowledgeService.retrieve(message);
                var reply = dashScopeClient.chatWithHistory(sid, message, history, knowledge);
                saveRecord(userId, sid, "assistant", reply);
                // 逐字推送
                for (int i = 0; i < reply.length(); i++) {
                    int end = Math.min(i + 3, reply.length());
                    emitter.send(SseEmitter.event()
                            .name("delta")
                            .data(Map.of("chunk", reply.substring(i, end), "sessionId", sid)));
                    Thread.sleep(25);
                }
                emitter.complete();
            } catch (Exception e) {
                log.error("SSE 流式输出异常", e);
                emitter.completeWithError(e);
            }
        }).start();
        return emitter;
    }

    public List<AiChatRecord> getHistory(Integer userId) {
        return chatRecordMapper.selectList(new LambdaQueryWrapper<AiChatRecord>()
                .eq(AiChatRecord::getUserId, userId)
                .orderByDesc(AiChatRecord::getCreateTime)
                .last("LIMIT 50"));
    }

    private void saveRecord(Integer userId, String sessionId, String role, String content) {
        var record = new AiChatRecord();
        record.setUserId(userId);
        record.setSessionId(sessionId);
        record.setRole(role);
        record.setContent(content);
        chatRecordMapper.insert(record);
    }
}
