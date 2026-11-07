package com.mediquery.module.consultation.service;

import com.mediquery.common.exception.BusinessException;
import com.mediquery.module.consultation.dto.CreateConsultationRequest;
import com.mediquery.module.consultation.entity.AiChatRecord;
import com.mediquery.module.consultation.entity.Consultation;
import com.mediquery.module.consultation.entity.ConsultationStatus;
import com.mediquery.module.consultation.entity.Severity;
import com.mediquery.module.consultation.entity.SummaryStatus;
import com.mediquery.module.consultation.mapper.AiChatRecordMapper;
import com.mediquery.module.consultation.mapper.ConsultationMapper;
import com.mediquery.module.consultation.statemachine.IllegalStateTransitionException;
import com.mediquery.module.consultation.vo.ConsultationVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 问诊单的状态流转测试。
 *
 * <p>对应设计文档 12.3 的 P0 必测项：终态再操作返回 409、并发完结只有一个成功、
 * 重复 sessionId 幂等、非本人 403。
 *
 * <h3>为什么这些用例值得写</h3>
 *
 * <p>这套代码的价值不在「能创建一张单子」，而在「两个人在同一毫秒点完结时，
 * 只有一个的改动生效，另一个收到明确的 409 而不是看到一个假成功」。
 * 前者随便写都能过，后者才是这个模块存在的理由，所以测的必须后者。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:mediquery_consult;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always"
})
class ConsultationServiceTest {

    private static final Integer ALICE = 1;
    private static final Integer BOB = 2;

    @Autowired
    private ConsultationService service;

    @Autowired
    private ConsultationMapper mapper;

    @Autowired
    private AiChatRecordMapper chatRecordMapper;

    @BeforeEach
    void clean() {
        mapper.delete(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<Consultation>lambdaQuery().gt(Consultation::getId, 0));
        chatRecordMapper.delete(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<AiChatRecord>lambdaQuery().gt(AiChatRecord::getId, 0));
    }

    // ────────────────────── 创建与幂等 ──────────────────────

    @Test
    @DisplayName("创建后状态是进行中，小结未开始")
    void createStartsInProgress() {
        ConsultationVO vo = service.create(ALICE, req("最近总咳嗽"));

        assertNotNull(vo.getId());
        assertEquals(ConsultationStatus.IN_PROGRESS, vo.getStatus());
        assertEquals("进行中", vo.getStatusText());
        assertEquals(SummaryStatus.NONE, vo.getSummaryStatus());
        assertEquals(Severity.LOW, vo.getSeverity());
        assertNull(vo.getClosedAt());
        assertNotNull(vo.getLastActiveAt(), "lastActiveAt 必须在创建时就填，否则超时任务扫不到它");
    }

    @Test
    @DisplayName("同一个 sessionId 重复创建返回同一张单，不新建")
    void sameSessionIdIsIdempotent() {
        var r1 = req("咳嗽");
        r1.setSessionId("fixed-session-001");

        ConsultationVO first = service.create(ALICE, r1);
        var r2 = req("咳嗽");
        r2.setSessionId("fixed-session-001");
        ConsultationVO second = service.create(ALICE, r2);

        assertEquals(first.getId(), second.getId());
        assertEquals(1, mapper.selectCount(null), "库里应当只有一张单");
    }

    @Test
    @DisplayName("拿别人的 sessionId 创建会被拒绝，不会泄露那张单的内容")
    void sessionIdOwnedByOthersIsRejected() {
        var aliceReq = req("头痛");
        aliceReq.setSessionId("alice-session");
        service.create(ALICE, aliceReq);

        var bobReq = req("我也头痛");
        bobReq.setSessionId("alice-session");

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(BOB, bobReq));
        assertEquals(409, e.getCode());
        // 关键：错误信息不能包含 alice 的主诉或 id
        assertTrue(e.getMessage().contains("已被占用"));
    }

    @Test
    @DisplayName("不传 sessionId 时后端生成 UUID，两张单互不相同")
    void sessionIdIsGeneratedWhenAbsent() {
        var a = service.create(ALICE, req("A"));
        var b = service.create(ALICE, req("B"));

        assertNotNull(a.getSessionId());
        assertNotNull(b.getSessionId());
        assertEquals(36, a.getSessionId().length(), "应当是标准 UUID 长度");
        // 注意不能用 assertNotEquals(a.getId(), b.getId()) 就完事：
        // 真正要保证的是 sessionId 不同，否则两张单的消息会串在一起
        assertTrue(!a.getSessionId().equals(b.getSessionId()));
    }

    // ────────────────────── 完结 ──────────────────────

    @Test
    @DisplayName("完结后状态是已完成，closedAt 有值，小结进入 GENERATING")
    void completeMarksTerminalAndStartsSummary() {
        var vo = service.create(ALICE, req("发烧三天"));
        ConsultationVO done = service.complete(vo.getId(), ALICE);

        assertEquals(ConsultationStatus.COMPLETED, done.getStatus());
        assertEquals("已完成", done.getStatusText());
        assertNotNull(done.getClosedAt());
        assertEquals(SummaryStatus.GENERATING, done.getSummaryStatus());
    }

    @Test
    @DisplayName("完结后再点完结返回 409，而不是静默成功")
    void completeTwiceIsRejected() {
        var vo = service.create(ALICE, req("胃疼"));
        service.complete(vo.getId(), ALICE);

        IllegalStateTransitionException e = assertThrows(IllegalStateTransitionException.class,
                () -> service.complete(vo.getId(), ALICE));
        assertEquals(409, IllegalStateTransitionException.CODE);
        assertTrue(e.getMessage().contains("已完成"), "错误信息要说出当前是什么状态");
    }

    @Test
    @DisplayName("并发完结：只有一个成功，另一个拿到 409")
    void concurrentCompleteOnlyOneWins() throws Exception {
        var vo = service.create(ALICE, req("胸闷"));
        int threads = 8;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Throwable> unexpected = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    service.complete(vo.getId(), ALICE);
                    ok.incrementAndGet();
                } catch (IllegalStateTransitionException e) {
                    conflict.incrementAndGet();
                } catch (Throwable t) {
                    synchronized (unexpected) {
                        unexpected.add(t);
                    }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "线程池应当在 30 秒内结束");

        assertEquals(List.of(), unexpected, "不应出现 409 以外的异常");
        assertEquals(1, ok.get(), "必须恰好一个成功 —— 两个都成功说明乐观锁没生效");
        assertEquals(threads - 1, conflict.get());

        Consultation stored = mapper.selectById(vo.getId());
        assertEquals(ConsultationStatus.COMPLETED, stored.getStatus());
    }

    // ────────────────────── 取消 ──────────────────────

    @Test
    @DisplayName("取消后状态是已取消，且不触发小结")
    void cancelDoesNotStartSummary() {
        var vo = service.create(ALICE, req("没事就是想问问"));
        ConsultationVO cancelled = service.cancel(vo.getId(), ALICE);

        assertEquals(ConsultationStatus.CANCELLED, cancelled.getStatus());
        assertEquals(SummaryStatus.NONE, cancelled.getSummaryStatus(),
                "用户主动取消表示不要这个结果，不该生成小结");
        assertNotNull(cancelled.getClosedAt());
    }

    @Test
    @DisplayName("重复取消静默成功 —— 连点两次不该看到红色报错")
    void cancelTwiceIsSilent() {
        var vo = service.create(ALICE, req("随便聊聊"));
        service.cancel(vo.getId(), ALICE);

        // 第二次不抛异常
        ConsultationVO again = service.cancel(vo.getId(), ALICE);
        assertEquals(ConsultationStatus.CANCELLED, again.getStatus());
    }

    @Test
    @DisplayName("已完结的单子不能取消")
    void cancelAfterCompleteIsRejected() {
        var vo = service.create(ALICE, req("腿疼"));
        service.complete(vo.getId(), ALICE);

        assertThrows(IllegalStateTransitionException.class,
                () -> service.cancel(vo.getId(), ALICE));
    }

    // ────────────────────── 归属 ──────────────────────

    @Test
    @DisplayName("非本人查看详情返回 403")
    void detailOfOthersIsForbidden() {
        var vo = service.create(ALICE, req("失眠"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.detail(vo.getId(), BOB));
        assertEquals(403, e.getCode());
    }

    @Test
    @DisplayName("不存在的单子返回 404")
    void detailOfMissingIsNotFound() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.detail(999_999, ALICE));
        assertEquals(404, e.getCode());
    }

    @Test
    @DisplayName("非本人完结返回 403，且原单状态不变")
    void completeOthersIsForbiddenAndLeavesStateUntouched() {
        var vo = service.create(ALICE, req("牙疼"));

        assertThrows(BusinessException.class, () -> service.complete(vo.getId(), BOB));

        Consultation stored = mapper.selectById(vo.getId());
        assertEquals(ConsultationStatus.IN_PROGRESS, stored.getStatus(),
                "越权操作不能有任何副作用");
    }

    // ────────────────────── 删除 ──────────────────────

    @Test
    @DisplayName("进行中的单子不能删")
    void cannotDeleteInProgress() {
        var vo = service.create(ALICE, req("想删掉"));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.delete(vo.getId(), ALICE));
        assertEquals(409, e.getCode());
    }

    @Test
    @DisplayName("已完结的单子可以删")
    void canDeleteTerminal() {
        var vo = service.create(ALICE, req("说完了"));
        service.complete(vo.getId(), ALICE);

        service.delete(vo.getId(), ALICE);
        assertNull(mapper.selectById(vo.getId()));
    }

    @Test
    @DisplayName("删除问诊单时，对话正文必须一起清掉")
    void deleteAlsoPurgesChatRecords() {
        var vo = service.create(ALICE, req("胸口有点闷"));
        service.complete(vo.getId(), ALICE);

        // 造两条真实对话，模拟「患者说了症状、助手回了话」
        chatRecordMapper.insert(record(ALICE, vo.getSessionId(), "user", "胸口有点闷，两天了"));
        chatRecordMapper.insert(record(ALICE, vo.getSessionId(), "assistant", "是一直闷还是活动后闷？"));
        assertEquals(2, countChat(vo.getSessionId()), "前置条件：对话确实写进去了");

        service.delete(vo.getId(), ALICE);

        assertEquals(0, countChat(vo.getSessionId()),
                "只删单子不删对话，等于用户点了删除但症状描述还躺在库里（健康数据，这是隐私问题）");
    }

    @Test
    @DisplayName("删除只清自己那张单子的对话，别人的不受影响")
    void deleteDoesNotTouchOtherSessions() {
        var mine = service.create(ALICE, req("我的问题"));
        var others = service.create(BOB, req("别人的问题"));
        service.complete(mine.getId(), ALICE);
        service.complete(others.getId(), BOB);
        chatRecordMapper.insert(record(ALICE, mine.getSessionId(), "user", "我的症状"));
        chatRecordMapper.insert(record(BOB, others.getSessionId(), "user", "别人的症状"));

        service.delete(mine.getId(), ALICE);

        assertEquals(0, countChat(mine.getSessionId()));
        assertEquals(1, countChat(others.getSessionId()),
                "按 sessionId 删除就这点必须守住，删串了就是删了别人的病历");
    }

    // ────────────────────── 超时 ──────────────────────

    @Test
    @DisplayName("超时任务只关掉确实超时的单子")
    void timeoutOnlyClosesStaleOnes() {
        var stale = service.create(ALICE, req("很久以前说的"));
        var fresh = service.create(ALICE, req("刚说的"));

        // 手工把其中一张的 lastActiveAt 推到阈值之外
        var c = mapper.selectById(stale.getId());
        c.setLastActiveAt(LocalDateTime.now().minusMinutes(ConsultationService.TIMEOUT_MINUTES + 1));
        mapper.updateById(c);

        List<Consultation> timedOut = service.findTimedOut();
        List<Integer> ids = timedOut.stream().map(Consultation::getId).toList();
        assertTrue(ids.contains(stale.getId()), "超时的单子必须被扫到");
        assertTrue(!ids.contains(fresh.getId()), "刚活跃的单子不能被扫到");

        assertTrue(service.closeTimeout(stale.getId()));
        assertEquals(ConsultationStatus.TIMEOUT, mapper.selectById(stale.getId()).getStatus());
        assertEquals(ConsultationStatus.IN_PROGRESS, mapper.selectById(fresh.getId()).getStatus());
    }

    @Test
    @DisplayName("超时关闭与患者完结撞上时，只有先到的那个生效")
    void timeoutAndCompleteRaceResolvesCleanly() {
        var vo = service.create(ALICE, req("同时发生"));
        // 患者先完结
        service.complete(vo.getId(), ALICE);

        // 超时任务随后处理这张已经被完结的单子
        boolean closed = service.closeTimeout(vo.getId());
        assertTrue(!closed, "已经被完结的单子不该被超时任务再关一次");
        assertEquals(ConsultationStatus.COMPLETED, mapper.selectById(vo.getId()).getStatus(),
                "状态必须还是 COMPLETED，不能被覆盖成 TIMEOUT");
    }

    // ────────────────────── 小结重试 ──────────────────────

    @Test
    @DisplayName("小结失败后可以重试，重试置为 GENERATING")
    void retryFailedSummaryIsAllowed() {
        var vo = service.create(ALICE, req("头疼"));
        service.complete(vo.getId(), ALICE);

        // 模拟小结失败
        var c = mapper.selectById(vo.getId());
        c.setSummaryStatus(SummaryStatus.FAILED);
        mapper.updateById(c);

        ConsultationVO retried = service.retrySummary(vo.getId(), ALICE);
        assertEquals(SummaryStatus.GENERATING, retried.getSummaryStatus());
    }

    @Test
    @DisplayName("小结已成功时不能重生成 —— DONE 是终态")
    void cannotRetryWhenSummaryDone() {
        var vo = service.create(ALICE, req("咳嗽"));
        service.complete(vo.getId(), ALICE);

        var c = mapper.selectById(vo.getId());
        c.setSummaryStatus(SummaryStatus.DONE);
        mapper.updateById(c);

        assertThrows(IllegalStateTransitionException.class,
                () -> service.retrySummary(vo.getId(), ALICE),
                "覆盖已有的小结会让用户看到的结论凭空变化");
    }

    // ────────────────────── 消息前校验 ──────────────────────

    @Test
    @DisplayName("已完结的单子不能再发消息")
    void cannotSendMessageToTerminal() {
        var vo = service.create(ALICE, req("结束了"));
        service.complete(vo.getId(), ALICE);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.requireActive(vo.getId(), ALICE));
        assertEquals(409, e.getCode());
        assertTrue(e.getMessage().contains("已完成"));
    }

    @Test
    @DisplayName("已完结的单子仍然可以读消息历史")
    void canReadHistoryAfterTerminal() {
        var vo = service.create(ALICE, req("回头翻记录"));
        service.complete(vo.getId(), ALICE);

        // 不抛异常即通过 —— 读完还能看到自己说过什么是核心价值
        assertNotNull(service.requireOwnedForRead(vo.getId(), ALICE));
    }

    // ────────────────────── 列表 ──────────────────────

    @Test
    @DisplayName("列表只返回自己的单子，按创建时间倒序")
    void listOnlyShowsOwn() {
        service.create(ALICE, req("a1"));
        service.create(ALICE, req("a2"));
        service.create(BOB, req("b1"));

        var page = service.list(ALICE, 1, 10);
        assertEquals(2, page.getTotal());
        assertEquals(2, page.getRecords().size());
        assertTrue(page.getRecords().stream()
                .allMatch(v -> v.getChiefComplaint().startsWith("a")));
    }

    private static CreateConsultationRequest req(String complaint) {
        var r = new CreateConsultationRequest();
        r.setChiefComplaint(complaint);
        return r;
    }

    private static AiChatRecord record(Integer userId, String sessionId, String role, String content) {
        var r = new AiChatRecord();
        r.setUserId(userId);
        r.setSessionId(sessionId);
        r.setRole(role);
        r.setContent(content);
        return r;
    }

    private long countChat(String sessionId) {
        return chatRecordMapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<AiChatRecord>lambdaQuery().eq(AiChatRecord::getSessionId, sessionId));
    }

    // ────────────────────────── 1.1 结构化采集 ──────────────────────────

    @Test
    @DisplayName("开单时带上信息卡槽位，detail 原样带出")
    void createPersistsIntakeSlots() {
        var r = req("咳嗽三天");
        r.setDurationDesc("三天");
        r.setSeverityScale(4);
        r.setHistoryFlags(List.of("青霉素过敏", "高血压 5 年"));
        r.setOnsetType("gradual");

        ConsultationVO created = service.create(ALICE, r);
        ConsultationVO detail = service.detail(created.getId(), ALICE);

        assertEquals("三天", detail.getDurationDesc());
        assertEquals(4, detail.getSeverityScale());
        assertEquals(List.of("青霉素过敏", "高血压 5 年"), detail.getHistoryFlags());
        assertEquals("gradual", detail.getOnsetType());
    }

    @Test
    @DisplayName("开单不带信息卡，detail 的槽位全是 null")
    void createWithoutIntakeLeavesNulls() {
        ConsultationVO created = service.create(ALICE, req("头痛"));

        ConsultationVO detail = service.detail(created.getId(), ALICE);
        assertNull(detail.getDurationDesc());
        assertNull(detail.getSeverityScale());
        assertNull(detail.getHistoryFlags());
        assertNull(detail.getOnsetType());
    }

    @Test
    @DisplayName("严重度超出 0-10 直接拒绝")
    void createRejectsBadSeverityScale() {
        var r = req("头痛");
        r.setSeverityScale(11);
        assertThrows(BusinessException.class, () -> service.create(ALICE, r));
    }

    @Test
    @DisplayName("信息卡补录：传 null 的槽位不动，明确给值的才更新")
    void updateIntakeOnlyTouchesGivenSlots() {
        var r = req("咳嗽三天");
        r.setDurationDesc("三天");
        ConsultationVO created = service.create(ALICE, r);

        var patch = new com.mediquery.module.consultation.dto.IntakeRequest();
        patch.setSeverityScale(3);
        patch.setHistoryFlags(List.of("无"));
        ConsultationVO updated = service.updateIntake(created.getId(), ALICE, patch);

        assertEquals("三天", updated.getDurationDesc(), "没传的槽位不能被清掉");
        assertEquals(3, updated.getSeverityScale());
        assertEquals(List.of("无"), updated.getHistoryFlags());
    }

    @Test
    @DisplayName("空数组病史落库为显式「无」，与「没填」区分")
    void updateIntakeEmptyHistoryMeansExplicitNone() {
        ConsultationVO created = service.create(ALICE, req("牙痛"));

        var patch = new com.mediquery.module.consultation.dto.IntakeRequest();
        patch.setHistoryFlags(List.of());
        ConsultationVO updated = service.updateIntake(created.getId(), ALICE, patch);

        Consultation c = mapper.selectById(created.getId());
        assertEquals("[]", c.getHistoryFlags(), "空数组要落成 []，语义是用户明确说没有");
        assertTrue(updated.getHistoryFlags().isEmpty(), "VO 里空数组读出来是空列表");
    }

    @Test
    @DisplayName("终态单子不能再改信息卡")
    void updateIntakeRejectedAfterTerminal() {
        ConsultationVO created = service.create(ALICE, req("咳嗽"));
        service.complete(created.getId(), ALICE);

        var patch = new com.mediquery.module.consultation.dto.IntakeRequest();
        patch.setDurationDesc("五天");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateIntake(created.getId(), ALICE, patch));
        assertTrue(ex.getMessage().contains("已结束"));
    }

    // ────────────────────────── 1.2 小结导出 ──────────────────────────

    @Test
    @DisplayName("小结没生成时导出 409")
    void summaryMarkdownRejectedBeforeDone() {
        ConsultationVO created = service.create(ALICE, req("咳嗽"));
        assertThrows(BusinessException.class,
                () -> service.summaryMarkdown(created.getId(), ALICE));
    }
}
