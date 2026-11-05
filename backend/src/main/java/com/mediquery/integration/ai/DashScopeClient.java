package com.mediquery.integration.ai;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.mediquery.common.exception.AiServiceException;
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
 *
 * <p>模型名通过 {@code ai.dashscope.model} 配置，默认 qwen-turbo。
 * 不同账号对不同模型的配额/开通状态不同，写死在代码里会导致换模型必须改代码重打包。
 *
 * <p>异常处理：不再把上游故障伪装成一句正常回复，而是抛出
 * {@link AiServiceException}，由上层转成 SSE error 事件，前端可给出可操作提示。
 */
@Component
public class DashScopeClient {
    private static final Logger log = LoggerFactory.getLogger(DashScopeClient.class);

    @Value("${ai.dashscope.api-key}")
    private String apiKey;

    @Value("${ai.dashscope.model:qwen-turbo}")
    private String model;

    private static final String SYSTEM_PROMPT = "你是医问通(MediQuery)的智能医生助手，你的职责是为用户提供专业、准确的医疗健康建议。"
            + "请注意：你只回答与医学、健康相关的问题。对于非医疗问题，请礼貌地引导用户提问医疗相关问题。"
            + "回答时应严格基于提供的知识库内容，当知识库没有相关信息时应建议用户咨询专业医生。";

    private static final String CTX_KEY_PREFIX = "rag:ctx:";

    /**
     * 知识上下文缓存的存活时间。
     *
     * <p><b>从 7 天改成 24 小时</b>（阶段二 2.11b）。理由：
     * <ul>
     *   <li>缓存里存的是用户症状描述命中的疾病/药品知识，属于健康信息。
     *       留 7 天等于让一份「他最近咳嗽发烧」的记录在 Redis 里躺一周。</li>
     *   <li>一次问诊很少跨越一天。24 小时覆盖了「晚上聊到一半去睡觉，第二天接着问」
     *       这个真实场景，再长就没有业务价值了。</li>
     *   <li>真正该控制生命周期的是问诊单本身 —— 完结/取消/超时三条路径都会
     *       主动调 {@link #clearContext}，TTL 只是兜底（进程崩溃时来不及清）。</li>
     * </ul>
     */
    private static final Duration CTX_TTL = Duration.ofHours(24);

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
     * @throws AiServiceException 上游调用失败（限流/鉴权/未开通等）
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
     * 清除某个会话的知识上下文缓存。
     *
     * <p>由问诊单关闭时调用（完结 / 取消 / 超时三条路径）。给用户一个明确的
     * 「这条会话的知识窗口结束了」语义，而不是让它自然过期。
     *
     * <p><b>为什么这个方法在这个类里</b>：{@code CTX_KEY_PREFIX} 是本类的私有常量。
     * 让业务层去拼 {@code "rag:ctx:" + sessionId}，等于把这个前缀同时定义在两个地方 ——
     * 改一处漏一处，缓存就永远清不掉。键的创建与销毁必须同属一个类。
     *
     * <p>删不存在的 key 不报错，天然幂等。
     */
    public void clearContext(String sessionId) {
        if (!StringUtils.hasText(sessionId)) return;
        try {
            redisTemplate.delete(CTX_KEY_PREFIX + sessionId);
        } catch (Exception e) {
            // 清缓存失败不该阻断问诊单关闭 —— Redis 故障时更应该让业务继续。
            // TTL 24 小时会兜住这次遗漏。
            log.warn("清除 RAG 上下文失败 sessionId={}", sessionId, e);
        }
    }

    /**
     * 拼装 messages：System Prompt → 知识上下文 → 历史对话 → 当前消息。
     */
    private String doChat(List<AiChatRecord> history, String userMessage, String knowledgeContext) {
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
                .model(model)
                .messages(messages)
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .build();

        try {
            var result = gen.call(param);
            return result.getOutput().getChoices().get(0).getMessage().getContent();
        } catch (NoApiKeyException e) {
            log.error("DashScope API Key 未配置");
            throw AiServiceException.authFailed("NoApiKey", "未配置 API Key");
        } catch (ApiException e) {
            throw translate(e);
        } catch (InputRequiredException e) {
            log.error("DashScope 请求参数不合法", e);
            throw AiServiceException.upstream(400, "InputRequired", "请求参数不合法");
        }
    }

    /**
     * 把 SDK 的 ApiException 翻译成带语义的业务异常。
     * 依据上游返回的 statusCode / code 分流，便于前端给出「稍后重试」还是「找管理员」。
     */
    private AiServiceException translate(ApiException e) {
        var status = e.getStatus();
        int httpStatus = status == null ? 0 : status.getStatusCode();
        String code = status == null ? "" : status.getCode();
        String msg = status == null ? e.getMessage() : status.getMessage();
        String requestId = status == null ? "" : status.getRequestId();

        log.error("DashScope 调用失败 model={} httpStatus={} code={} requestId={} msg={}",
                model, httpStatus, code, requestId, msg);

        if (httpStatus == 429 || "Throttling.RateQuota".equals(code)) {
            return AiServiceException.rateLimited(msg);
        }
        if (httpStatus == 401 || "InvalidApiKey".equals(code)) {
            return AiServiceException.authFailed(code, msg);
        }
        if (httpStatus == 403 || "AccessDenied".equals(code)) {
            return AiServiceException.modelDenied(model, code);
        }
        return AiServiceException.upstream(httpStatus, code, msg == null ? "未知错误" : msg);
    }
}
