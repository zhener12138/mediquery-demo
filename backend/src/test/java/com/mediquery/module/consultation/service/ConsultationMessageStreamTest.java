package com.mediquery.module.consultation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediquery.config.AsyncConfig;
import com.mediquery.integration.ai.DashScopeClient;
import com.mediquery.integration.ai.KnowledgeRetrievalService;
import com.mediquery.module.consultation.entity.AiChatRecord;
import com.mediquery.module.consultation.mapper.AiChatRecordMapper;
import com.mediquery.module.consultation.entity.Consultation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 真流式改造后新增的两条路径 —— 同步调用里根本不存在、只有流式才有的状态。
 *
 * <p>这个测试刻意不碰网络：{@link DashScopeClient} 被 mock 掉，
 * 由测试自己决定「吐几片然后成功」还是「吐几片然后断开」。
 * 真正需要真 API 的那件事（{@code incrementalOutput(true)} 是否让帧变成增量而不是全文）
 * 单测证明不了，必须在真机上验。
 */
class ConsultationMessageStreamTest {

    private static final Integer PATIENT_ID = 7;
    private static final String SESSION_ID = "sess-stream-test";

    private ConsultationService consultationService;
    private DashScopeClient dashScopeClient;
    private KnowledgeRetrievalService knowledgeService;
    private AiChatRecordMapper chatRecordMapper;
    private ThreadPoolExecutor executor;
    private ConsultationMessageService service;

    @BeforeEach
    void setUp() {
        consultationService = mock(ConsultationService.class);
        dashScopeClient = mock(DashScopeClient.class);
        knowledgeService = mock(KnowledgeRetrievalService.class);
        chatRecordMapper = mock(AiChatRecordMapper.class);

        // 单线程直通池：让 streamInWorker 在测试线程上同步跑完，省掉 sleep 等待。
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(4), new ThreadPoolExecutor.AbortPolicy());

        service = new ConsultationMessageService(
                consultationService, dashScopeClient, knowledgeService,
                chatRecordMapper, new ObjectMapper(), executor,
                new com.mediquery.module.consultation.triage.TriageRuleEngine());

        Consultation c = new Consultation();
        c.setId(1);
        c.setPatientId(PATIENT_ID);
        c.setSessionId(SESSION_ID);
        c.setStatus(0);
        when(consultationService.requireActive(anyInt(), anyInt())).thenReturn(c);
        when(knowledgeService.retrieve(anyString())).thenReturn("");

        // 让 saveRecord 拿到的主键有值，便于断言
        when(chatRecordMapper.insert(any(AiChatRecord.class))).thenReturn(1);
    }

    @Test
    @DisplayName("流式成功：每一片都原样转发，整段文本入库")
    void chunksAreForwardedAndFullTextIsSaved() {
        String[] pieces = {"你好", "，我是", "智能医生"};

        when(dashScopeClient.chatWithHistoryStreaming(
                eq(SESSION_ID), eq("我头疼"), any(), any(), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    var onChunk = (java.util.function.Consumer<String>) inv.getArgument(4);
                    for (String p : pieces) {
                        onChunk.accept(p);
                    }
                    return String.join("", pieces);
                });

        SseEmitter emitter = service.stream(1, PATIENT_ID, "我头疼");
        assertThat(emitter).isNotNull();

        // 用户消息 + 助手回复，两条都要入库
        ArgumentCaptor<AiChatRecord> captor = ArgumentCaptor.forClass(AiChatRecord.class);
        verify(chatRecordMapper, timeout(3000).times(2)).insert(captor.capture());

        List<AiChatRecord> saved = captor.getAllValues();
        assertThat(saved.get(0).getRole()).isEqualTo("user");
        assertThat(saved.get(0).getContent()).isEqualTo("我头疼");
        assertThat(saved.get(1).getRole()).isEqualTo("assistant");
        assertThat(saved.get(1).getContent()).isEqualTo("你好，我是智能医生");

        executor.shutdownNow();
    }

    @Test
    @DisplayName("客户端中途断开：半截内容照样入库，不丢弃")
    void partialTextIsSavedWhenClientDisconnects() {
        // 模拟「吐了两片之后客户端断连」：onChunk 内部抛 IOException，
        // DashScopeClient 会把它包成 ChunkConsumerException 带上已累积的文本。
        when(dashScopeClient.chatWithHistoryStreaming(
                eq(SESSION_ID), eq("我头疼"), any(), any(), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    var onChunk = (java.util.function.Consumer<String>) inv.getArgument(4);
                    onChunk.accept("你好，");
                    onChunk.accept("我是");
                    // 第三片推送失败 —— 由 ChatWithHistoryStreaming 包装后抛出
                    throw new DashScopeClient.ChunkConsumerException(
                            "你好，我是", new IOException("Broken pipe"));
                });

        service.stream(1, PATIENT_ID, "我头疼");

        ArgumentCaptor<AiChatRecord> captor = ArgumentCaptor.forClass(AiChatRecord.class);
        verify(chatRecordMapper, timeout(3000).times(2)).insert(captor.capture());

        List<AiChatRecord> saved = captor.getAllValues();
        assertThat(saved.get(1).getRole()).isEqualTo("assistant");
        // 关键断言：断连时存的是已生成的部分，而不是空串、也不是整条消失
        assertThat(saved.get(1).getContent()).isEqualTo("你好，我是");

        executor.shutdownNow();
    }

    @Test
    @DisplayName("AI 上游故障：错误事件下发前不能把兜底文案当正常回答入库")
    void upstreamFailureDoesNotPersistFakeAnswer() {
        when(dashScopeClient.chatWithHistoryStreaming(
                eq(SESSION_ID), eq("我头疼"), any(), any(), any()))
                .thenThrow(com.mediquery.common.exception.AiServiceException
                        .rateLimited("限流"));

        service.stream(1, PATIENT_ID, "我头疼");

        // 只有用户那条消息入库；助手侧一条都不该有 ——
        // 老实现的坑正是把「服务不在线」写进了历史，用户下次带上下文提问会被这句话污染。
        ArgumentCaptor<AiChatRecord> captor = ArgumentCaptor.forClass(AiChatRecord.class);
        verify(chatRecordMapper, timeout(3000).times(1)).insert(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo("user");

        executor.shutdownNow();
    }

    @Test
    @DisplayName("空回复也入库：用户刷新要能看到「问过、没答」")
    void emptyReplyIsStillPersisted() {
        when(dashScopeClient.chatWithHistoryStreaming(
                eq(SESSION_ID), eq("我头疼"), any(), any(), any()))
                .thenReturn("");

        service.stream(1, PATIENT_ID, "我头疼");

        ArgumentCaptor<AiChatRecord> captor = ArgumentCaptor.forClass(AiChatRecord.class);
        verify(chatRecordMapper, timeout(3000).times(2)).insert(captor.capture());
        assertThat(captor.getAllValues().get(1).getRole()).isEqualTo("assistant");
        assertThat(captor.getAllValues().get(1).getContent()).isEmpty();

        executor.shutdownNow();
    }
}
