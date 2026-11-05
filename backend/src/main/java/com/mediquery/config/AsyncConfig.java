package com.mediquery.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程池配置。
 *
 * <h3>为什么要有两个池，而不是共用一个</h3>
 *
 * <p>SSE 对话和小结生成是两类完全不同的任务：
 * <ul>
 *   <li><b>SSE</b> —— 每轮对话占一个线程，短则几秒长则几十秒，用户正盯着屏幕等。</li>
 *   <li><b>小结</b> —— 完结后触发，调 AI 生成结构化 JSON，一次可能 10–30 秒，
 *       用户不在等（完结接口已经立即返回了）。</li>
 * </ul>
 *
 * <p>如果共用一个池：几个慢小结能把队列占满，新来的对话请求进不去，
 * 用户看到的是「聊天框转圈不出字」。一个后台任务把前台功能拖死，
 * 是最典型也最容易忽视的故障域耦合。分池之后，小结最多把自己的池撑爆，对话不受影响。
 *
 * <h3>为什么用 AbortPolicy 而不是 CallerRunsPolicy</h3>
 *
 * <p>{@code CallerRunsPolicy} 会让提交任务的线程亲自去跑 —— 对小结来说，
 * 那就是 Tomcat 的请求线程被拖住 30 秒。宁可让任务被拒绝、返回「小结排队中，请稍后刷新」，
 * 也不能把 Web 容器线程池吃掉。
 */
@Configuration
public class AsyncConfig {

    /** SSE 对话线程池的 bean 名。 */
    public static final String SSE_EXECUTOR = "sseExecutor";

    /** 小结生成线程池的 bean 名。 */
    public static final String SUMMARY_EXECUTOR = "summaryExecutor";

    /**
     * SSE 对话线程池。
     *
     * <p>core 8 / max 32 / queue 64：并发对话通常个位数，8 个常驻够用；
     * 突发时扩到 32，再多就排队。队列满了直接拒绝 —— 让用户收到「服务繁忙」
     * 比让所有人一起卡住好。
     */
    @Bean(SSE_EXECUTOR)
    public ThreadPoolExecutor sseExecutor() {
        return new ThreadPoolExecutor(
                8, 32, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                namedFactory("sse-"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 小结生成线程池。
     *
     * <p>core 2 / max 4 / queue 32：小结不是高频操作，但每个都很慢（AI 调用）。
     * 池子刻意开小 —— 真正限制并发的是外部 AI 接口的限流，本地开再大也只是
     * 把请求堆在网络上。队列 32 之后拒绝，由 Service 把 summary_status 置为 FAILED，
     * 用户可以手动重试。
     */
    @Bean(SUMMARY_EXECUTOR)
    public ThreadPoolExecutor summaryExecutor() {
        return new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32),
                namedFactory("summary-"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 带名字前缀的线程工厂。
     *
     * <p>线程名会进日志。默认的 {@code pool-3-thread-1} 在排查问题时毫无信息量，
     * {@code summary-2} 一眼就知道是谁在占 CPU。
     */
    private static java.util.concurrent.ThreadFactory namedFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, prefix + seq.getAndIncrement());
            t.setDaemon(false);
            return t;
        };
    }
}
