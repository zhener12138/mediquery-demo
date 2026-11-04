package com.mediquery.integration.ai;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.mediquery.module.ai_doctor.entity.AiChatRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 阿里通义千问客户端：多轮对话 + RAG 知识上下文 + Redis 缓存。
 */
@Component
public class DashScopeClient {
    private static final Logger log = LoggerFactory.getLogger(DashScopeClient.class);

    @Value("${ai.dashscope.api-key}")
    private String apiKey;

    private static final String SYSTEM_PROMPT = "你是医问通(MediQuery)的智能医生助手，你的职责是为用户提供专业、准确的医疗健康建议。"
            + "请注意：你只回答与医学、健康相关的问题。对于非医疗问题，请礼貌地引导用户提问医疗相关问题。"
            + "回答时应严格基于提供的知识库内容，当知识库没有相关信息时应建议用户咨询专业医生。";

    private static final String CTX_KEY_PREFIX = "rag:ctx:";
    private static final Duration CTX_TTL = Duration.ofDays(7);

    private final RedisTemplate<String, Object> redisTemplate;

    public DashScopeClient(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 带历史的多轮 RAG 对话。
     * @param sessionId 会话ID
     * @param userMessage 当前用户消息
     * @param history 同一 session 的历史对话
     * @param knowledge 检索到的知识上下文
     */
    public String chatWithHistory(String sessionId, String userMessage,
                                   List<AiChatRecord> history, String knowledge) {
        // 管理知识上下文缓存
        String context;
        if (StringUtils.hasText(knowledge)) {
            context = knowledge;
            redisTemplate.opsForValue().set(CTX_KEY_PREFIX + sessionId, knowledge, CTX_TTL);
        } else {
            var cached = redisTemplate.opsForValue().get(CTX_KEY_PREFIX + sessionId);
            context = cached != null ? cached.toString() : "";
        }
        return doChat(history, userMessage, context);
    }

    /**
     * 拼装 messages：System Prompt → 知识上下文 → 历史对话 → 当前消息。
     */
    private String doChat(List<AiChatRecord> history, String userMessage, String knowledgeContext) {
        try {
            List<Message> messages = new ArrayList<>();

            // 1. System prompt
            messages.add(Message.builder().role(Role.SYSTEM.getValue()).content(SYSTEM_PROMPT).build());

            // 2. 知识上下文
            if (StringUtils.hasText(knowledgeContext)) {
                messages.add(Message.builder().role(Role.SYSTEM.getValue())
                        .content("请基于以下知识库信息回答用户问题：\n\n" + knowledgeContext)
                        .build());
            }

            // 3. 历史对话（排除当前这条）
            for (var record : history) {
                messages.add(Message.builder()
                        .role(record.getRole())
                        .content(record.getContent())
                        .build());
            }

            // 4. 当前用户消息
            messages.add(Message.builder().role(Role.USER.getValue()).content(userMessage).build());

            var gen = new Generation();
            var param = GenerationParam.builder()
                    .apiKey(apiKey)
                    .model("qwen-turbo")
                    .messages(messages)
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .build();

            var result = gen.call(param);
            return result.getOutput().getChoices().get(0).getMessage().getContent();
        } catch (NoApiKeyException e) {
            log.error("DashScope API Key 未配置", e);
            return "智能医生服务暂未配置，请联系管理员设置API Key。";
        } catch (ApiException | InputRequiredException e) {
            log.error("DashScope API 调用异常", e);
            return "智能医生现在不在线，请稍后再试。";
        }
    }
}
