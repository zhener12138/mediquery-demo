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
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 阿里通义千问客户端：封装DashScope SDK，发送System Prompt + 用户消息，返回AI回复
 */
@Component
public class DashScopeClient {
    private static final Logger log = LoggerFactory.getLogger(DashScopeClient.class);

    @Value("${ai.dashscope.api-key}")
    private String apiKey;

    private static final String SYSTEM_PROMPT = "你是医问通(MediQuery)的智能医生助手，你的职责是为用户提供专业、准确的医疗健康建议。"
            + "请注意：你只回答与医学、健康相关的问题。对于非医疗问题，请礼貌地引导用户提问医疗相关问题。"
            + "回答时应基于循证医学，当不确定时应建议用户咨询专业医生。";

    // 调用qwen-turbo模型：System Prompt设定角色 → 发送用户消息 → 返回AI回复
    public String chat(String userMessage) {
        try {
            Message systemMsg = Message.builder().role(Role.SYSTEM.getValue()).content(SYSTEM_PROMPT).build();
            Message userMsg = Message.builder().role(Role.USER.getValue()).content(userMessage).build();

            var gen = new Generation();
            var param = GenerationParam.builder()
                    .apiKey(apiKey)
                    .model("qwen-turbo")
                    .messages(Arrays.asList(systemMsg, userMsg))
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
