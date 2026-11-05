package com.mediquery.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：分页插件 + Mapper扫描
 *
 * <h3>为什么必须给分页设上限</h3>
 *
 * <p>改之前 {@code new PaginationInnerInterceptor(DbType.MYSQL)} 没配 maxLimit。
 * 这意味着 {@code GET /api/v1/illnesses?page=1&size=1000000} 是合法请求，
 * 会真的去 {@code LIMIT 1000000}——一次请求把整张表拉进内存再序列化成 JSON。
 *
 * <p>这个漏洞的触发门槛低到什么程度：浏览器地址栏改个数字，或者爬虫扫到接口自己拼。
 * 不需要任何攻击工具。
 *
 * <p>同时它也是"自伤"型问题——前端管理页现在硬编码 {@code size: 100} 拉全量，
 * 一旦数据涨到几万条，页面会先卡死，而不是慢慢变慢。
 *
 * <p>设成 100 的理由：现有所有列表页最多用 100（管理页），
 * 公开列表用 9，AI 检索用 50。100 覆盖全部现有场景，且单次响应体可控。
 */
@Configuration
@MapperScan("com.mediquery.module.*.mapper")
public class MyBatisPlusConfig {

    /**
     * 单页最大条数。
     *
     * <p>超过这个值的 {@code size} 会被 MyBatis-Plus 静默改成 100（不是报错，
     * 这点要注意：调用方不会知道自己请求被缩水了）。选择静默截断而不是抛异常，
     * 是因为分页参数通常来自前端控件，报错体验更差；真正的防线是"拉不爆内存"。
     */
    private static final long MAX_PAGE_SIZE = 100L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        var pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 这一行是本次改动的内容：没有它，size 参数可以任意大。
        pagination.setMaxLimit(MAX_PAGE_SIZE);
        // 页码溢出时不回到第一页，而是返回空结果。
        // 理由：用户翻到第 999 页发现回到第 1 页会以为页面出 bug，
        // 返回空至少语义是对的（"没有更多了"）。
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        return interceptor;
    }
}
