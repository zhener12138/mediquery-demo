package com.mediquery.module.consultation.controller;

import com.mediquery.common.dto.PageResult;
import com.mediquery.common.dto.Result;
import com.mediquery.module.consultation.dto.CreateConsultationRequest;
import com.mediquery.module.consultation.security.ConsultationGuard;
import com.mediquery.module.consultation.service.ConsultationMessageService;
import com.mediquery.module.consultation.service.ConsultationService;
import com.mediquery.module.consultation.vo.ConsultationVO;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 问诊单接口。全部需要登录（{@code SecurityConfig} 的 {@code anyRequest().authenticated()} 兜底）。
 *
 * <h3>为什么每个方法都要 @PreAuthorize</h3>
 *
 * <p>「登录了」不等于「这是你的单子」。{@code anyRequest().authenticated()} 只能挡住
 * 匿名用户，任何登录用户都能拿 id=1,2,3… 去遍历别人的问诊记录 ——
 * 而这里存的是主诉、对话、小结，是这套系统里最敏感的数据。
 *
 * <p>归属校验用 {@code @PreAuthorize("@consultGuard.isOwner(#id, authentication)")}
 * 放在进入 Service 之前，好处是：Service 不必信任调用方，即使将来加了别的入口
 * （定时任务、管理端），也不会因为忘记校验而漏掉。Service 内部仍会再查一次做
 * 业务语义上的 404/403 区分，这两层不重复：一层是安全边界，一层是错误信息质量。
 */
@RestController
@RequestMapping("/api/v1/consultations")
public class ConsultationController {

    private final ConsultationService consultationService;
    private final ConsultationMessageService messageService;

    public ConsultationController(ConsultationService consultationService,
                                  ConsultationMessageService messageService) {
        this.consultationService = consultationService;
        this.messageService = messageService;
    }

    /**
     * 发起问诊。
     *
     * <p>不挂 {@code @PreAuthorize}：这里还没有 id 可以校验，只要登录了就能建自己的单子。
     */
    @PostMapping
    public Result<ConsultationVO> create(@RequestBody @Valid CreateConsultationRequest req,
                                         org.springframework.security.core.Authentication auth) {
        return Result.success(consultationService.create(ConsultationGuard.currentUserId(auth), req));
    }

    /** 我的问诊列表。分页 size 由 MyBatis-Plus 的 {@code setMaxLimit(100)} 兜底。 */
    @GetMapping
    public Result<PageResult<ConsultationVO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            org.springframework.security.core.Authentication auth) {
        return Result.success(
                consultationService.list(ConsultationGuard.currentUserId(auth), page, size));
    }

    /** 详情。小结已生成时一并返回。 */
    @GetMapping("/{id}")
    @PreAuthorize("@consultGuard.isOwner(#id, authentication)")
    public Result<ConsultationVO> detail(@PathVariable Integer id,
                                         org.springframework.security.core.Authentication auth) {
        return Result.success(consultationService.detail(id, ConsultationGuard.currentUserId(auth)));
    }

    /**
     * 发消息（SSE 流式）。
     *
     * <p>响应用 {@code TEXT_EVENT_STREAM}：不显式声明的话，某些代理会把它当普通
     * JSON 缓冲起来，前端看起来就是「一次性吐出全部内容」而不是逐字。
     *
     * <p>状态校验（是否存在 / 是否本人 / 是否进行中）都在 Service 里同步做完了，
     * 所以 409、403、404 都能正常返回。一旦开始推流，就只能靠 `type=error` 事件报错。
     */
    @PostMapping(value = "/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@consultGuard.isOwner(#id, authentication)")
    public SseEmitter streamMessage(@PathVariable Integer id,
                                    @RequestBody Map<String, String> body,
                                    org.springframework.security.core.Authentication auth) {
        return messageService.stream(id, ConsultationGuard.currentUserId(auth), body.get("content"));
    }

    /** 拉取这张单子的全部消息（前端刷新后恢复对话）。 */
    @GetMapping("/{id}/messages")
    @PreAuthorize("@consultGuard.isOwner(#id, authentication)")
    public Result<List<Map<String, Object>>> messages(@PathVariable Integer id,
                                                      org.springframework.security.core.Authentication auth) {
        return Result.success(
                messageService.history(id, ConsultationGuard.currentUserId(auth)));
    }

    /** 完结。立即返回，小结异步生成。 */
    @PutMapping("/{id}/complete")
    @PreAuthorize("@consultGuard.isOwner(#id, authentication)")
    public Result<ConsultationVO> complete(@PathVariable Integer id,
                                           org.springframework.security.core.Authentication auth) {
        return Result.success(consultationService.complete(id, ConsultationGuard.currentUserId(auth)));
    }

    /** 取消。重复取消静默成功（在 Service 里处理）。 */
    @PutMapping("/{id}/cancel")
    @PreAuthorize("@consultGuard.isOwner(#id, authentication)")
    public Result<ConsultationVO> cancel(@PathVariable Integer id,
                                         org.springframework.security.core.Authentication auth) {
        return Result.success(consultationService.cancel(id, ConsultationGuard.currentUserId(auth)));
    }

    /** 重新生成小结。只允许从 FAILED 出发。 */
    @PutMapping("/{id}/summary/retry")
    @PreAuthorize("@consultGuard.isOwner(#id, authentication)")
    public Result<ConsultationVO> retrySummary(@PathVariable Integer id,
                                               org.springframework.security.core.Authentication auth) {
        return Result.success(
                consultationService.retrySummary(id, ConsultationGuard.currentUserId(auth)));
    }

    /** 删除。只允许删已终结的单子。 */
    @DeleteMapping("/{id}")
    @PreAuthorize("@consultGuard.isOwner(#id, authentication)")
    public Result<Void> delete(@PathVariable Integer id,
                               org.springframework.security.core.Authentication auth) {
        consultationService.delete(id, ConsultationGuard.currentUserId(auth));
        return Result.success();
    }
}
