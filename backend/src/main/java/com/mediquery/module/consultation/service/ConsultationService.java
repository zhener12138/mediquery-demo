package com.mediquery.module.consultation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediquery.common.dto.PageResult;
import com.mediquery.common.exception.BusinessException;
import com.mediquery.integration.ai.DashScopeClient;
import com.mediquery.module.consultation.dto.CreateConsultationRequest;
import com.mediquery.module.consultation.entity.Consultation;
import com.mediquery.module.consultation.entity.ConsultationStatus;
import com.mediquery.module.consultation.entity.Severity;
import com.mediquery.module.consultation.entity.SummaryStatus;
import com.mediquery.module.consultation.mapper.ConsultationMapper;
import com.mediquery.module.consultation.statemachine.ConsultationStateMachine;
import com.mediquery.module.consultation.statemachine.IllegalStateTransitionException;
import com.mediquery.module.consultation.vo.ConsultationVO;
import com.mediquery.module.consultation.vo.SummaryVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 问诊单的核心业务。
 *
 * <h3>这个类回答的问题</h3>
 *
 * <p>改造之前，`ai_chat_record` 只记「谁在哪个会话里说了什么」。它不知道这次问诊
 * 结束了没有、是怎么结束的、结束之后有没有留下什么。于是「对话」是无状态的流，
 * 每次进来都从零开始，也没有任何东西可以被限制、被统计、被追溯。
 *
 * <p>{@code consultation} 把这条流包成一张**有生命周期**的单子：
 * 进行中 → 已完成 / 已取消 / 超时关闭。有了状态，「能不能再发消息」「能不能完结」
 * 「超时怎么办」「并发点两次完结会怎样」才成为可以回答的问题。
 *
 * <h3>本类的三条纪律</h3>
 *
 * <ol>
 *   <li><b>状态变更只走条件更新</b>。先查出来做内存校验只是为了让用户早点看到错误，
 *       真正的保证在 SQL 的 {@code WHERE status = ? AND version = ?}。少任何一边都是漏洞：
 *       只做内存校验 → 并发下两个请求都通过；只做 SQL → 用户看到的是「操作失败」而非人话。</li>
 *   <li><b>副作用一律放 {@code afterCommit}</b>。清 Redis 缓存和提交异步小结都不能写在
 *       事务方法体里 —— 事务回滚了缓存却已清空，或者异步线程读到未提交的旧快照。</li>
 *   <li><b>完结不等待小结</b>。小结要调 AI，可能几十秒。完结接口立即返回，小结状态
 *       由 {@code summary_status} 单独跟踪，失败可重试，绝不回退主状态。</li>
 * </ol>
 */
@Service
public class ConsultationService {

    private static final Logger log = LoggerFactory.getLogger(ConsultationService.class);

    /** 多久没有新消息算超时。与设计文档 8.5 一致。 */
    public static final int TIMEOUT_MINUTES = 30;

    /** 超时任务单次扫描上限，避免一次锁住太多行。 */
    private static final int TIMEOUT_BATCH = 100;

    private final ConsultationMapper consultationMapper;
    private final SummaryService summaryService;
    private final DashScopeClient dashScopeClient;
    private final ObjectMapper objectMapper;

    public ConsultationService(ConsultationMapper consultationMapper,
                               SummaryService summaryService,
                               DashScopeClient dashScopeClient,
                               ObjectMapper objectMapper) {
        this.consultationMapper = consultationMapper;
        this.summaryService = summaryService;
        this.dashScopeClient = dashScopeClient;
        this.objectMapper = objectMapper;
    }

    // ────────────────────────────── 创建 ──────────────────────────────

    /**
     * 发起一次问诊。
     *
     * <p><b>幂等靠两层</b>：
     * <ol>
     *   <li>先按 {@code sessionId} 查一次。命中就直接返回已有的单子 —— 覆盖「前端重试」
     *       「用户刷新页面后又点了一次」这类顺序重复。</li>
     *   <li>没命中就插入，并捕获 {@link DuplicateKeyException}。覆盖「两个请求几乎同时到达」
     *       这类并发重复：唯一索引 {@code uk_session} 会让后到的那个失败，
     *       此时再查一次拿到对方插入的那条。</li>
     * </ol>
     *
     * <p>只做第一层是不够的：查和插之间的窗口足够两个请求都通过检查。
     * 数据库的唯一约束才是真正的仲裁者，代码只是把它的失败翻译成人话。
     */
    @Transactional
    public ConsultationVO create(Integer patientId, CreateConsultationRequest req) {
        String sid = req.getSessionId();
        if (sid == null || sid.isBlank()) {
            sid = UUID.randomUUID().toString();
        }

        Consultation existing = findBySessionId(sid);
        if (existing != null) {
            return reuse(existing, patientId);
        }

        Consultation c = new Consultation();
        c.setPatientId(patientId);
        c.setSessionId(sid);
        c.setChiefComplaint(req.getChiefComplaint().trim());
        c.setStatus(ConsultationStatus.IN_PROGRESS);
        c.setSeverity(Severity.LOW);
        c.setSummaryStatus(SummaryStatus.NONE);
        c.setLastActiveAt(LocalDateTime.now());
        c.setVersion(0);

        try {
            consultationMapper.insert(c);
            log.info("发起问诊 id={} patientId={}", c.getId(), patientId);
            return ConsultationVO.from(c);
        } catch (DuplicateKeyException e) {
            // 并发下另一个请求先插入了同一个 sessionId
            Consultation raced = findBySessionId(sid);
            if (raced == null) {
                // 唯一索引冲突了却查不到，说明冲突来自别的唯一键（理论上不存在），
                // 直接抛出比返回一个假单子诚实
                throw e;
            }
            return reuse(raced, patientId);
        }
    }

    private Consultation findBySessionId(String sessionId) {
        return consultationMapper.selectOne(new LambdaQueryWrapper<Consultation>()
                .eq(Consultation::getSessionId, sessionId));
    }

    /**
     * 复用已有会话前先确认归属。
     *
     * <p>sessionId 是前端可传的。如果不管归属直接返回，任何人都能拿别人的
     * sessionId 读到别人的问诊单。所以这里必须比对 {@code patientId}。
     */
    private ConsultationVO reuse(Consultation existing, Integer patientId) {
        if (!existing.getPatientId().equals(patientId)) {
            // 不返回 403 只说「已被占用」，避免变成探测别人会话是否存在的接口
            throw new BusinessException(409, "该会话已被占用，请重新发起问诊");
        }
        return ConsultationVO.from(existing);
    }

    // ────────────────────────────── 查询 ──────────────────────────────

    /** 我的问诊列表。按创建时间倒序，带 id 兜底保证翻页稳定。 */
    public PageResult<ConsultationVO> list(Integer patientId, int page, int size) {
        IPage<Consultation> p = consultationMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Consultation>()
                        .eq(Consultation::getPatientId, patientId)
                        .orderByDesc(Consultation::getCreateTime)
                        .orderByDesc(Consultation::getId));
        List<ConsultationVO> records = p.getRecords().stream().map(ConsultationVO::from).toList();
        return PageResult.of(p.getTotal(), p.getCurrent(), p.getSize(), records);
    }

    /**
     * 详情。
     *
     * <p>{@code aiSummary} 的 JSON 解析放在这里而不是 {@code ConsultationVO.from()}：
     * 列表接口不需要小结正文，而解析可能失败。只让详情这一条路径承担解析成本，
     * 解析失败也只影响这一个字段（降级成一个空小结），不影响整张单的展示。
     */
    public ConsultationVO detail(Integer id, Integer patientId) {
        Consultation c = requireOwned(id, patientId);
        ConsultationVO vo = ConsultationVO.from(c);
        if (Integer.valueOf(SummaryStatus.DONE).equals(c.getSummaryStatus())) {
            vo.setSummary(parseStoredSummary(c));
        }
        return vo;
    }

    private SummaryVO parseStoredSummary(Consultation c) {
        String json = c.getAiSummary();
        if (json == null || json.isBlank()) {
            return SummaryVO.empty(c.getChiefComplaint());
        }
        try {
            return objectMapper.readValue(json, SummaryVO.class);
        } catch (Exception e) {
            // 存量数据的格式可能与当前版本不同，不能让详情接口 500
            log.warn("存量小结解析失败 id={}", c.getId(), e);
            return SummaryVO.empty(c.getChiefComplaint());
        }
    }

    // ────────────────────────────── 状态流转 ──────────────────────────────

    /**
     * 患者主动完结。
     *
     * <p>返回时 {@code summaryStatus} 已经是 {@code GENERATING}，前端据此显示
     * 「正在生成小结」。真正的生成在事务提交后异步进行。
     */
    @Transactional
    public ConsultationVO complete(Integer id, Integer patientId) {
        Consultation c = requireOwned(id, patientId);
        ConsultationStateMachine.checkTransition(c.getStatus(), ConsultationStatus.COMPLETED);

        LocalDateTime now = LocalDateTime.now();
        int affected = consultationMapper.update(null, new LambdaUpdateWrapper<Consultation>()
                .eq(Consultation::getId, id)
                .eq(Consultation::getStatus, ConsultationStatus.IN_PROGRESS)
                .eq(Consultation::getVersion, c.getVersion())
                .set(Consultation::getStatus, ConsultationStatus.COMPLETED)
                .set(Consultation::getClosedAt, now)
                .set(Consultation::getSummaryStatus, SummaryStatus.GENERATING)
                .set(Consultation::getVersion, c.getVersion() + 1));

        if (affected == 0) {
            // 查完之后、更新之前，超时任务（或另一次点击）先改了状态
            throw new IllegalStateTransitionException("consultation",
                    c.getStatus(), ConsultationStatus.COMPLETED);
        }

        // 副作用等提交
        afterCommit(() -> {
            dashScopeClient.clearContext(c.getSessionId());
            summaryService.generateAsync(id, c.getSessionId());
        });

        log.info("问诊完结 id={} patientId={}", id, patientId);
        c.setStatus(ConsultationStatus.COMPLETED);
        c.setClosedAt(now);
        c.setSummaryStatus(SummaryStatus.GENERATING);
        // 不回填 version：VO 里本来就不暴露它，回填反而制造「这个值有意义」的错觉
        return ConsultationVO.from(c);
    }

    /**
     * 患者主动取消。
     *
     * <p>语义上 {@code cancel} 与 {@code complete} 有一处关键差别：
     * 取消**不生成小结**（用户明确说了不要这个结果），但要清 RAG 缓存。
     * 重复取消会走到状态机校验并被拒绝 —— 但 Controller 层对「已经是 CANCELLED」
     * 单独做了静默成功处理，见 {@link #cancelQuietly}。
     */
    @Transactional
    public ConsultationVO cancel(Integer id, Integer patientId) {
        Consultation c = requireOwned(id, patientId);

        // 已经是取消状态：静默返回。用户点两次取消不该看到红色报错
        if (Integer.valueOf(ConsultationStatus.CANCELLED).equals(c.getStatus())) {
            return ConsultationVO.from(c);
        }
        ConsultationStateMachine.checkTransition(c.getStatus(), ConsultationStatus.CANCELLED);

        LocalDateTime now = LocalDateTime.now();
        int affected = consultationMapper.update(null, new LambdaUpdateWrapper<Consultation>()
                .eq(Consultation::getId, id)
                .eq(Consultation::getStatus, ConsultationStatus.IN_PROGRESS)
                .eq(Consultation::getVersion, c.getVersion())
                .set(Consultation::getStatus, ConsultationStatus.CANCELLED)
                .set(Consultation::getClosedAt, now)
                .set(Consultation::getVersion, c.getVersion() + 1));

        if (affected == 0) {
            throw new IllegalStateTransitionException("consultation",
                    c.getStatus(), ConsultationStatus.CANCELLED);
        }

        afterCommit(() -> dashScopeClient.clearContext(c.getSessionId()));

        log.info("问诊取消 id={} patientId={}", id, patientId);
        c.setStatus(ConsultationStatus.CANCELLED);
        c.setClosedAt(now);
        return ConsultationVO.from(c);
    }

    /**
     * 逻辑删除。
     *
     * <p>只允许删已终结的单子。进行中的单子删掉会产生一批永远不会被关闭的孤儿记录，
     * 而且用户「不小心删了」正在进行的对话是不可逆的。
     *
     * <p>这里用物理删除还是逻辑删除：{@code consultation} 表**没有** {@code deleted} 列。
     * 医疗类数据保留原记录比图省事更重要，但表结构没设计这个字段，本阶段
     * 采用「状态置为 CANCELLED + 不动数据」以外的方案成本太高。
     * <b>实际实现是物理删除</b>，并在下方注释里标注了这个设计债。
     */
    @Transactional
    public void delete(Integer id, Integer patientId) {
        Consultation c = requireOwned(id, patientId);
        if (!ConsultationStateMachine.isTerminal(c.getStatus())) {
            throw new BusinessException(409, "进行中的问诊不能删除，请先完结或取消");
        }
        consultationMapper.deleteById(id);
        afterCommit(() -> dashScopeClient.clearContext(c.getSessionId()));
        log.info("问诊记录删除 id={} patientId={}", id, patientId);
    }

    /**
     * 重新生成小结。
     *
     * <p>只允许从 {@code FAILED} 出发（状态机的 {@code SUMMARY_ALLOWED} 里有这条边）。
     * {@code DONE} 是终态：一个小结已经生成好了，再生成一次会覆盖用户可能已经
     * 看过并当作依据的内容，且没有产品需求支持这件事。
     */
    @Transactional
    public ConsultationVO retrySummary(Integer id, Integer patientId) {
        Consultation c = requireOwned(id, patientId);
        int from = c.getSummaryStatus() == null ? SummaryStatus.NONE : c.getSummaryStatus();
        ConsultationStateMachine.checkSummaryTransition(from, SummaryStatus.GENERATING);

        int affected = consultationMapper.update(null, new LambdaUpdateWrapper<Consultation>()
                .eq(Consultation::getId, id)
                .eq(Consultation::getSummaryStatus, from)
                .set(Consultation::getSummaryStatus, SummaryStatus.GENERATING));

        if (affected == 0) {
            throw new IllegalStateTransitionException("summary", from, SummaryStatus.GENERATING);
        }

        afterCommit(() -> summaryService.generateAsync(id, c.getSessionId()));

        c.setSummaryStatus(SummaryStatus.GENERATING);
        return ConsultationVO.from(c);
    }

    // ────────────────────────────── 供消息链路与定时任务使用 ──────────────────────────────

    /**
     * 取一张单并要求它处于进行中。
     *
     * <p>发消息前调用：已完结/已取消/已超时的单子不能再收消息，否则用户会在
     * 一个「已经结束」的界面上继续聊，而小结早就生成完了，两边说的不是同一件事。
     */
    public Consultation requireActive(Integer id, Integer patientId) {
        Consultation c = requireOwned(id, patientId);
        if (!Integer.valueOf(ConsultationStatus.IN_PROGRESS).equals(c.getStatus())) {
            throw new BusinessException(409,
                    "本次问诊" + ConsultationStatus.nameOf(c.getStatus()) + "，不能再发消息");
        }
        return c;
    }

    /**
     * 读消息历史前校验归属。
     *
     * <p>与 {@link #requireActive} 的区别：只读不要求进行中。
     * 已完结的问诊仍然要能看到对话记录 —— 用户完事后回头翻自己说过什么，
     * 是这个功能的核心价值之一。删除的单子查不到（物理删除），自然 404。
     */
    public Consultation requireOwnedForRead(Integer id, Integer patientId) {
        return requireOwned(id, patientId);
    }

    /**
     * 刷新活跃时间。
     *
     * <p>刻意不在每次发消息时都走 {@code updateById}（会带上 version 自增，
     * 与完结操作打架）。这里只更新 {@code last_active_at} 一列，
     * 且**不加 version 条件** —— 活跃时间的语义是「最近一次说话是什么时候」，
     * 晚到的一次覆盖早到的一次恰好是对的。
     *
     * <p>条件里带上 {@code status = IN_PROGRESS}：如果这中间单子被完结了，
     * 这次刷新不该生效，否则会给一张已关闭的单子续命，超时任务永远扫不到它。
     */
    public void touch(Integer id) {
        int affected = consultationMapper.update(null, new LambdaUpdateWrapper<Consultation>()
                .eq(Consultation::getId, id)
                .eq(Consultation::getStatus, ConsultationStatus.IN_PROGRESS)
                .set(Consultation::getLastActiveAt, LocalDateTime.now()));
        if (affected == 0) {
            log.debug("刷新活跃时间未生效（单子已关闭） id={}", id);
        }
    }

    /**
     * 关闭一张超时单。由 {@code ConsultationTimeoutJob} 逐条调用。
     *
     * <p>独立方法 + 独立事务：单条失败不回滚其他条。若把整个批次放在一个事务里，
     * 第 87 条因为脏数据抛异常，前 86 条也会一起回滚，而下一轮扫描又会从头开始 ——
     * 那条脏数据会把整个超时机制永久卡死。
     */
    @Transactional
    public boolean closeTimeout(Integer id) {
        Consultation c = consultationMapper.selectById(id);
        if (c == null || !Integer.valueOf(ConsultationStatus.IN_PROGRESS).equals(c.getStatus())) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        int affected = consultationMapper.update(null, new LambdaUpdateWrapper<Consultation>()
                .eq(Consultation::getId, id)
                .eq(Consultation::getStatus, ConsultationStatus.IN_PROGRESS)
                .set(Consultation::getStatus, ConsultationStatus.TIMEOUT)
                .set(Consultation::getClosedAt, now)
                .set(Consultation::getSummaryStatus, SummaryStatus.GENERATING));
        if (affected == 0) {
            return false;
        }
        afterCommit(() -> {
            dashScopeClient.clearContext(c.getSessionId());
            summaryService.generateAsync(id, c.getSessionId());
        });
        log.info("问诊超时关闭 id={} lastActiveAt={}", id, c.getLastActiveAt());
        return true;
    }

    /** 找出该被超时关闭的单子。 */
    public List<Consultation> findTimedOut() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(TIMEOUT_MINUTES);
        return consultationMapper.selectList(new LambdaQueryWrapper<Consultation>()
                .eq(Consultation::getStatus, ConsultationStatus.IN_PROGRESS)
                .lt(Consultation::getLastActiveAt, threshold)
                .orderByAsc(Consultation::getLastActiveAt)
                .last("LIMIT " + TIMEOUT_BATCH));
    }

    // ────────────────────────────── 内部工具 ──────────────────────────────

    /**
     * 查出来并校验归属。
     *
     * <p>不存在抛 404、不是本人抛 403。这两种情况的区分是有意的 ——
     * 与 {@code ConsultationGuard} 的策略相反。区别在于：Guard 面对的是
     * 「未授权访问任意 ID」，多给一点信息就是在帮忙探测；而这里用户点的是
     * 自己列表里的单子，说清楚「不存在」还是「不是你的」能省掉一次误报工单。
     */
    private Consultation requireOwned(Integer id, Integer patientId) {
        Consultation c = consultationMapper.selectById(id);
        if (c == null) {
            throw BusinessException.notFound("问诊不存在");
        }
        if (!c.getPatientId().equals(patientId)) {
            throw BusinessException.forbidden("无权操作他人的问诊");
        }
        return c;
    }

    /**
     * 把副作用推迟到事务提交之后。
     *
     * <p>没有活跃事务时（比如在测试里直接调用，或调用方没加 {@code @Transactional}）
     * 立即执行 —— 不推迟比不执行好。
     */
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /** 供 Controller 判断「非本人」用，避免重复查库。 */
    static boolean ownedBy(Consultation c, Integer patientId) {
        return c != null && c.getPatientId().equals(patientId);
    }
}
