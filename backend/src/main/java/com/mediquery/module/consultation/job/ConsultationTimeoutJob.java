package com.mediquery.module.consultation.job;

import com.mediquery.module.consultation.entity.Consultation;
import com.mediquery.module.consultation.service.ConsultationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 超时关闭任务。
 *
 * <h3>它存在的理由</h3>
 *
 * <p>没有它，一张「进行中」的单子会永远进行中。用户开了个头就走了，
 * 过两天再登进来看，那张单子还在列表最上面，状态是「进行中」，
 * 但他自己都忘了当时说了什么 —— 而且它会一直占着「进行中」的计数，
 * 让数据大屏上的数字慢慢失真。
 *
 * <h3>为什么不用分布式调度 / 不加分布式锁</h3>
 *
 * <p>当前部署是单实例（{@code docker-compose} 里 backend 只起一个容器）。
 * 用 {@code @Scheduled} 就够了。加 ShedLock / XXL-Job 是为多实例准备的，
 * 代价是一个新依赖、一张锁表、一套配置，而收益是「将来可能有第二个实例」——
 * 那是真实的将来，但现在没有。写在这里是为了让接手的人知道这是有意为之，
 * 而不是忘了。
 *
 * <p>真要多实例时，改法的关键是：并发安全**已经**由
 * {@code WHERE status = IN_PROGRESS} 条件更新保证了，即使两个实例同时扫到同一批单子，
 * 也只有一个能更新成功（另一个 affected=0 直接返回 false）。所以升级只是
 * 「少扫一点」的优化，不是「否则会出错」的修复。
 *
 * <h3>为什么 fixedDelay 而不是 fixedRate</h3>
 *
 * <p>{@code fixedDelay} 是「上一轮结束后再等 60 秒」。超时扫描要遍历数据库、
 * 逐条开事务，耗时不定。用 {@code fixedRate} 的话，如果某一轮因为数据库慢
 * 跑了 3 分钟，后面会立刻堆起 3 个待执行的轮次，变成雪崩。
 */
@Component
public class ConsultationTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(ConsultationTimeoutJob.class);

    private final ConsultationService consultationService;

    public ConsultationTimeoutJob(ConsultationService consultationService) {
        this.consultationService = consultationService;
    }

    /**
     * 每分钟扫一次。
     *
     * <p>逐条调用 {@code closeTimeout}，每条独立事务 —— 一条出错不影响其余。
     * 这个循环里**不能**包 {@code @Transactional}：那样整批要么全成要么全败，
     * 一条脏数据会让所有超时单子都关不掉，而且每轮都卡在同一条上。
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    public void closeTimedOut() {
        List<Consultation> candidates;
        try {
            candidates = consultationService.findTimedOut();
        } catch (Exception e) {
            // 查询失败（数据库不可用）不该让调度线程死掉
            log.error("超时扫描失败", e);
            return;
        }
        if (candidates.isEmpty()) {
            return;
        }

        int closed = 0;
        for (Consultation c : candidates) {
            try {
                if (consultationService.closeTimeout(c.getId())) {
                    closed++;
                }
            } catch (Exception e) {
                // 单条失败：记下来，继续下一条。下一轮它还会被扫到
                log.error("关闭超时问诊失败 id={}", c.getId(), e);
            }
        }
        if (closed > 0) {
            log.info("超时关闭 {} 张问诊单（候选 {} 张）", closed, candidates.size());
        }
    }
}
