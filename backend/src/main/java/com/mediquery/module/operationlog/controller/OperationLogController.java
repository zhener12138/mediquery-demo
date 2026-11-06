package com.mediquery.module.operationlog.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mediquery.common.dto.PageResult;
import com.mediquery.common.dto.Result;
import com.mediquery.module.operationlog.entity.OperationLog;
import com.mediquery.module.operationlog.mapper.OperationLogMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计日志查询（仅管理员）。
 *
 * <p>只读，没有写接口 —— 日志只应该由 {@code AuditLogAspect} 产生。
 * 提供写接口等于给了「伪造/删除审计痕迹」的能力，那这张表就没有审计价值了。
 */
@RestController
@RequestMapping("/api/v1/admin/operation-logs")
@PreAuthorize("hasRole('ADMIN')")
public class OperationLogController {

    private final OperationLogMapper operationLogMapper;

    public OperationLogController(OperationLogMapper operationLogMapper) {
        this.operationLogMapper = operationLogMapper;
    }

    /**
     * 分页查询。
     *
     * <p>四个过滤条件都可选，其中 {@code username} 做右模糊（{@code LIKE 'abc%'}）而不是
     * 前后都模糊 —— 后者用不上索引，日志表是增长最快的一张表，不能全表扫。
     */
    @GetMapping
    public Result<PageResult<OperationLog>> list(
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String username,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        var query = new LambdaQueryWrapper<OperationLog>();
        if (StringUtils.hasText(module)) {
            query.eq(OperationLog::getModule, module.trim());
        }
        if (StringUtils.hasText(username)) {
            query.likeRight(OperationLog::getUsername, username.trim());
        }
        query.orderByDesc(OperationLog::getCreateTime).orderByDesc(OperationLog::getId);

        var result = operationLogMapper.selectPage(new Page<>(page, size), query);
        return Result.success(
                PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords()));
    }
}
