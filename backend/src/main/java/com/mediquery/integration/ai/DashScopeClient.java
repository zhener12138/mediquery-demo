package com.mediquery.integration.ai;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.mediquery.common.exception.AiServiceException;
import com.mediquery.module.consultation.entity.AiChatRecord;
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

    /**
     * 系统提示词。
     *
     * <h3>这段提示词的设计取向：像医生一样问，而不是像搜索一样答</h3>
     *
     * <p>上一版只有「你只回答医学问题 + 基于知识库」，结果模型的表现是：
     * 用户说「头疼」，它回一大段「头疼可能有很多原因，包括偏头痛、紧张性头痛、
     * 颈椎病、高血压……建议就医」。这段话每句都对，但**对用户毫无用处** ——
     * 他不知道自己是哪一种，也不知道下一步该说什么。
     *
     * <p>真正的门诊里，医生做的第一件事是追问：「疼多久了？一边还是两边？
     * 是跳着疼还是压着疼？有没有恶心、怕光？」问诊的价值在**收敛**，
     * 不在穷举可能性。所以这里把行为约束写死：
     *
     * <ol>
     *   <li><b>每轮最多问 2 个问题</b>。问 5 个问题的医生不是专业，是偷懒 ——
     *       他把整理信息的活推给了患者。而且用户对着输入框一次性回答 5 个问题，
     *       体验上就崩了。</li>
     *   <li><b>先问最关键的鉴别点</b>。同样是头疼，「有没有突然炸裂般的剧痛」
     *       优先级远高于「睡眠怎么样」—— 前者决定要不要立刻去急诊。</li>
     *   <li><b>信息够了就给方向，不要无休止地问</b>。问诊不是审讯，
     *       连续追问四轮还只问不答的助手会被用户关掉。</li>
     *   <li><b>不做确诊、不开处方</b>。可以说「更倾向于」「可能性较大」，
     *       不能说「你就是 xx 病」。剂量、疗程一律不给。</li>
     * </ol>
     *
     * <p>「不是医疗问题就引导回来」这条保留，但要温和 —— 用户可能只是顺口问了句
     * 「这个药医保报吗」，直接拒绝会显得很生硬。
     */
    private static final String SYSTEM_PROMPT = """
            你是「医问通」的在线问诊助手，正在和一位患者对话。

            【你的角色】
            你像一位有经验的门诊医生：先通过提问把模糊的症状收敛成清晰的问题，
            再给出有依据的判断方向。你不是百科全书，不要用「可能有很多种原因」
            这类话把问题还回去 —— 那对患者没有帮助。

            【每一轮的说话方式】
            1. 先用一两句话回应患者刚才说的内容，让他知道你听懂了。
            2. 然后最多提 2 个问题。只问这一轮最关键的、能区分不同方向的那些点
               （比如疼痛的性质、持续时间、伴随症状、诱因、既往病史）。
            3. 当信息已经足够时，直接给出你的判断方向和下一步建议，
               不要再问问题。连续追问超过三轮会让患者失去耐心。
            4. 回答保持简洁，两三段以内。不要写大段分点罗列，这是一次对话。

            【必须遵守的边界】
            - 不做确诊。用「更倾向于」「可能性较大」「需要排除」这类表述，
              不要说「你就是 xx 病」。
            - 不给具体用药剂量、疗程和处方。可以说某类药通常用于什么情况，
              但必须让患者去药店咨询药师或由医生开具。
            - 危险信号优先。如果患者描述里出现可能危及生命的情况
              （剧烈胸痛、呼吸困难、意识改变、大出血、抽搐等），
              第一句话就要让他立即就医或打 120，不要先做常规问诊。
            - 不确定就说不知道，不要编造检查结果、医院信息或药品价格。

            【关于知识库】
            下面可能提供了一些参考条目。它们是你判断的辅助材料，不是必须逐条复述的答案。
            如果知识库内容和患者描述对不上，以患者的描述为准；知识库没有覆盖的，
            用你的医学常识判断，但要说明这是常识性建议。
            """;

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
        String context = resolveContext(sessionId, knowledge);
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
     * 带历史的多轮流式对话 —— 边收边吐。
     *
     * <p>与 {@link #chatWithHistory} 的区别：那个是 {@code gen.call(param)} 一次性拿全文，
     * 这个走 {@code gen.streamCall(param)} + {@code incrementalOutput(true)}，
     * 模型每吐几个字就回调一次 {@code onChunk}。
     *
     * <h3>为什么返回累积文本而不是 void</h3>
     *
     * <p>流式调用有「已经吐了一半然后失败」的中间态，这是同步调用不存在的。
     * 返回累积文本，让调用方在 catch 里仍然知道自己已经发出去多少 ——
     * 可以据此决定「把半截回复存库」还是「整条丢弃」。
     * 没有这个返回值，调用方只能自己再维护一个 StringBuilder，重复一遍累积逻辑。
     *
     * <h3>调用方必须处理的两件事</h3>
     *
     * <ol>
     *   <li>{@code onChunk} 抛出的异常会原样透出，不会被吞成 {@link AiServiceException}。
     *       客户端断连时 SSE 的 {@code send} 会抛 IOException，这**不是** AI 故障，
     *       不能报成「AI 服务不可用」。</li>
     *   <li>失败前可能已经吐出部分内容，此时不能再发 {@code type=error} 让前端把
     *       已有的气泡整个替换掉 —— 前端应保留半截内容并追加错误提示。</li>
     * </ol>
     *
     * @param onChunk 每收到一片增量文本回调一次
     * @return 累积的完整文本（失败时是已经收到的部分）
     * @throws AiServiceException 上游调用失败
     * @throws ChunkConsumerException onChunk 自身抛错（如客户端断连）
     */
    public String chatWithHistoryStreaming(String sessionId, String userMessage,
                                           List<AiChatRecord> history, String knowledge,
                                           java.util.function.Consumer<String> onChunk) {
        String context = resolveContext(sessionId, knowledge);
        List<Message> messages = buildMessages(history, userMessage, context);

        var param = GenerationParam.builder()
                .apiKey(apiKey)
                .model(model)
                .messages(messages)
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                // 关键：不设 incrementalOutput 的话，SDK 每帧都会返回**从头到现在**的全文，
                // 直接拼到前端就是「你好」「你好，我」「你好，我是」层层重复。
                //
                // 实测（2026-11-06，真机 8092，qwen-plus，同一句问诊）：
                //   设 true → 拼起来 681 字，正文「咳嗽三天且有痰，是否"要紧"需结合…」
                //   不设    → 拼起来 37113 字（54 倍），正文「咳嗽咳嗽三天且咳嗽三天且有痰，
                //            咳嗽三天且有痰，是否"咳嗽三天且有痰，是否"要紧"…」
                // 这不是优化开关，漏了它前端看到的就是一坨逐层重复的文本。
                .incrementalOutput(true)
                .build();

        var gen = new Generation();
        var accumulated = new StringBuilder();

        try {
            // blockingIterable 把 rx 的 Flowable 转成普通 for 循环。
            // 不用 subscribe(...) 回调形式是为了让异常沿调用栈抛出，而不是掉进 rx 的 onError 黑洞。
            for (var result : gen.streamCall(param).blockingIterable()) {
                String piece = extractDelta(result);
                if (piece == null || piece.isEmpty()) continue;
                accumulated.append(piece);
                try {
                    onChunk.accept(piece);
                } catch (Exception consumerFail) {
                    // 客户端断连：停止拉流，但把已收到的东西还回去。
                    // 这里**不**翻译成 AiServiceException —— 那是上游的错，这不是。
                    throw new ChunkConsumerException(accumulated.toString(), consumerFail);
                }
            }
        } catch (NoApiKeyException e) {
            log.error("DashScope API Key 未配置");
            throw AiServiceException.authFailed("NoApiKey", "未配置 API Key");
        } catch (InputRequiredException e) {
            log.error("DashScope 请求参数不合法", e);
            throw AiServiceException.upstream(400, "InputRequired", "请求参数不合法");
        } catch (ApiException e) {
            throw translate(e);
        }

        return accumulated.toString();
    }

    /**
     * 从一帧流式结果里取出增量文本。
     *
     * <p>流式响应里 choices 可能为空（最后一帧只有 finish_reason），
     * 也可能 content 为 null。这些都不该报错，返回 null 让上层跳过即可。
     */
    private static String extractDelta(GenerationResult result) {
        if (result == null || result.getOutput() == null) return null;
        var choices = result.getOutput().getChoices();
        if (choices == null || choices.isEmpty()) return null;
        var msg = choices.get(0).getMessage();
        return msg == null ? null : msg.getContent();
    }

    /**
     * onChunk 自己抛错时的包装。
     *
     * <p>携带已经累积的文本：调用方要靠它决定「半截内容要不要存库」。
     */
    public static class ChunkConsumerException extends RuntimeException {
        private final String partialText;

        public ChunkConsumerException(String partialText, Throwable cause) {
            super("推送片段失败：" + cause.getMessage(), cause);
            this.partialText = partialText;
        }

        public String getPartialText() {
            return partialText;
        }
    }

    /**
     * 解析知识上下文：有本轮检索结果就写缓存，没有就回落到上次的。
     *
     * <p>回落是为了解决「咳嗽发烧」→「那吃什么药」这类追问：第二轮全是停用词，
     * 检索不出东西，但模型仍然需要知道第一轮聊到了什么病。
     */
    private String resolveContext(String sessionId, String knowledge) {
        if (StringUtils.hasText(knowledge)) {
            redisTemplate.opsForValue().set(CTX_KEY_PREFIX + sessionId, knowledge, CTX_TTL);
            return knowledge;
        }
        var cached = redisTemplate.opsForValue().get(CTX_KEY_PREFIX + sessionId);
        return cached != null ? cached.toString() : "";
    }

    /**
     * 拼装 messages：System Prompt → 知识上下文 → 历史对话 → 当前消息。
     */
    private String doChat(List<AiChatRecord> history, String userMessage, String knowledgeContext) {
        List<Message> messages = buildMessages(history, userMessage, knowledgeContext);

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
     * 拼装 messages（同步与流式共用，避免两条路径的消息结构漂移）。
     */
    private List<Message> buildMessages(List<AiChatRecord> history, String userMessage,
                                        String knowledgeContext) {
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

        return messages;
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
