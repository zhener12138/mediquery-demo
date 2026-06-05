package com.mediquery.module.ai_doctor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mediquery.integration.ai.DashScopeClient;
import com.mediquery.module.ai_doctor.entity.AiChatRecord;
import com.mediquery.module.ai_doctor.mapper.AiChatRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

/**
 * AI智能医生服务：普通对话、SSE流式推送(每30ms推送5字符)、对话历史查询(top50)
 */
@Service
public class AiDoctorService {
    private static final Logger log = LoggerFactory.getLogger(AiDoctorService.class);

    private final DashScopeClient dashScopeClient;
    private final AiChatRecordMapper chatRecordMapper;

    public AiDoctorService(DashScopeClient dashScopeClient, AiChatRecordMapper chatRecordMapper) {
        this.dashScopeClient = dashScopeClient;
        this.chatRecordMapper = chatRecordMapper;
    }

    // 普通对话：存用户消息 → 调AI → 存AI回复 → 返回
    public String chat(Integer userId, String message) {
        var sessionId = UUID.randomUUID().toString();
        saveRecord(userId, sessionId, "user", message);
        var reply = dashScopeClient.chat(message);
        saveRecord(userId, sessionId, "assistant", reply);
        return reply;
    }

    // SSE流式对话：开新线程 → 调AI → 每30ms推送5字符 → complete
    public SseEmitter chatStream(Integer userId, String message) {
        var emitter = new SseEmitter(60000L);
        var sessionId = UUID.randomUUID().toString();
        saveRecord(userId, sessionId, "user", message);
        new Thread(() -> {
            try {
                var reply = dashScopeClient.chat(message);
                saveRecord(userId, sessionId, "assistant", reply);
                for (int i = 0; i < reply.length(); i++) {
                    emitter.send(SseEmitter.event().data(reply.substring(i, Math.min(i + 5, reply.length()))));
                    Thread.sleep(30);
                }
                emitter.complete();
            } catch (Exception e) {
                log.error("SSE 流式输出异常", e);
                emitter.completeWithError(e);
            }
        }).start();
        return emitter;
    }

    // 查询当前用户最近50条对话历史
    public List<AiChatRecord> getHistory(Integer userId) {
        return chatRecordMapper.selectList(new LambdaQueryWrapper<AiChatRecord>()
                .eq(AiChatRecord::getUserId, userId)
                .orderByDesc(AiChatRecord::getCreateTime)
                .last("LIMIT 50"));
    }

    // 持久化对话记录（user/assistant）
    private void saveRecord(Integer userId, String sessionId, String role, String content) {
        var record = new AiChatRecord();
        record.setUserId(userId);
        record.setSessionId(sessionId);
        record.setRole(role);
        record.setContent(content);
        chatRecordMapper.insert(record);
    }
}
