package com.mediquery.module.consultation.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mediquery.module.consultation.entity.Consultation;
import com.mediquery.module.consultation.mapper.ConsultationMapper;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 问诊单的归属校验，供 {@code @PreAuthorize} 使用。
 *
 * <p>用法：{@code @PreAuthorize("@consultGuard.isOwner(#id, authentication)")}
 *
 * <p><b>为什么单独一个 Bean 而不是在 Service 里判</b>：{@code @PreAuthorize} 在方法调用
 * 之前执行，能在进入业务逻辑前就把非本人请求挡掉。放在 Service 里意味着参数已经绑定、
 * 事务可能已经开启，挡在更外层省事也省资源。
 *
 * <p><b>为什么从 {@code auth.getDetails()} 取 userId 而不是 request attribute</b>：
 * {@code JwtAuthenticationFilter} 做的是 {@code auth.setDetails(userId)}
 * （见 {@code JwtAuthenticationFilter.java} 中 {@code UsernamePasswordAuthenticationToken}
 * 的 details 字段），不是 {@code request.setAttribute}。两者不是同一个东西 ——
 * 全项目 {@code @RequestAttribute} 那套曾经因此恒为 null。
 *
 * <p><b>性能提示</b>：每次校验会多查一次库（{@code @PreAuthorize} 一次 + Service 里
 * 再取一次）。本阶段刻意不优化 —— 一次主键查询在分页接口的量级下可以忽略，
 * 而正确性不能省。真到了需要优化时，把 {@code Consultation} 放进请求级缓存即可。
 */
@Component("consultGuard")
public class ConsultationGuard {

    private final ConsultationMapper consultationMapper;

    public ConsultationGuard(ConsultationMapper consultationMapper) {
        this.consultationMapper = consultationMapper;
    }

    /**
     * 当前用户是否拥有这张问诊单。
     *
     * @param consultationId 问诊单 ID
     * @param authentication Spring Security 注入的当前认证
     * @return true 表示是本人；false 表示不存在、或不是本人
     */
    public boolean isOwner(Integer consultationId, Authentication authentication) {
        Integer currentUserId = currentUserId(authentication);
        if (currentUserId == null || consultationId == null) {
            return false;
        }
        Consultation c = consultationMapper.selectOne(
                Wrappers.<Consultation>lambdaQuery()
                        .select(Consultation::getPatientId)
                        .eq(Consultation::getId, consultationId));
        // 不存在也返回 false：对调用方来说「看不到」和「不是你的」是同一件事，
        // 都应当是 403。区分二者反而会泄露「这个 ID 存在」的信息。
        return c != null && currentUserId.equals(c.getPatientId());
    }

    /**
     * 从认证信息里取当前用户 ID。
     *
     * <p>拿不到返回 {@code null} 而不是抛异常 —— 调用方（{@link #isOwner}）会把它当
     * 「不是本人」处理，最终表现为 403。这样匿名请求不会变成 500。
     */
    public static Integer currentUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object details = authentication.getDetails();
        // JwtAuthenticationFilter 塞进去的是 Long（JWT claim 反序列化出来的类型），
        // 不是 Integer。直接强转会 ClassCastException，必须判类型。
        if (details instanceof Long l) {
            return l.intValue();
        }
        if (details instanceof Integer i) {
            return i;
        }
        return null;
    }
}
