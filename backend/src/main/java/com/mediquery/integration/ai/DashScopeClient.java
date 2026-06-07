package com.mediquery.integration.ai;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 阿里通义千问客户端：封装DashScope SDK，支持 System Prompt + 知识上下文 + 用户消息多轮对话。
 * 知识上下文通过 Redis 按 sessionId 缓存，7天自动过期。
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
     * 普通对话（无上下文缓存，一次性注入检索结果）。
     */
    public String chat(String userMessage) {
        return doChat(null, userMessage, "");
    }

    /**
     * RAG 对话：使用 Redis 缓存的上下文，有新的检索结果时更新缓存。
     *
     * @param sessionId   会话ID，用作 Redis key
     * @param userMessage 用户消息
     * @param knowledge   检索到的知识上下文（首次检索非空时会缓存）
     */
    public String chatWithContext(String sessionId, String userMessage, String knowledge) {
        // 读缓存；如有新知识则更新
        String context;
        if (StringUtils.hasText(knowledge)) {
            context = knowledge;
            redisTemplate.opsForValue().set(CTX_KEY_PREFIX + sessionId, knowledge, CTX_TTL);
        } else {
            var cached = redisTemplate.opsForValue().get(CTX_KEY_PREFIX + sessionId);
            context = cached != null ? cached.toString() : "";
        }
        return doChat(sessionId, userMessage, context);
    }

    /**
     * 核心调用：System Prompt + 可选知识上下文 + 用户消息 → qwen-turbo → 回复。
     */
    private String doChat(String sessionId, String userMessage, String knowledgeContext) {
        try {
            List<Message> messages = new ArrayList<>();

            // System prompt（含角色设定）
            messages.add(Message.builder().role(Role.SYSTEM.getValue()).content(SYSTEM_PROMPT).build());

            // 知识上下文（检索到的疾病/药品信息）
            if (StringUtils.hasText(knowledgeContext)) {
                messages.add(Message.builder().role(Role.SYSTEM.getValue())
                        .content("请基于以下知识库信息回答用户问题，不要编造知识库中没有的内容：\n\n" + knowledgeContext)
                        .build());
            }

            // 用户消息
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
