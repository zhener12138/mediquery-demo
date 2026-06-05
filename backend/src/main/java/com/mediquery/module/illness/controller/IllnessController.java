package com.mediquery.module.illness.controller;

import com.mediquery.common.dto.PageResult;
import com.mediquery.common.dto.Result;
import com.mediquery.module.illness.entity.Illness;
import com.mediquery.module.illness.entity.IllnessCategory;
import com.mediquery.module.illness.service.IllnessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 疾病百科控制器：公开浏览(分页搜索/详情/分类) + 管理员维护(增删改)
 */
@Tag(name = "疾病百科")
@RestController
@RequestMapping("/api/v1/illnesses")
public class IllnessController {

    private final IllnessService illnessService;

    public IllnessController(IllnessService illnessService) { this.illnessService = illnessService; }

    @Operation(summary = "搜索疾病列表")
    @GetMapping
    public Result<PageResult<Illness>> list(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer categoryId, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "9") int size) {
        return Result.success(illnessService.search(keyword, categoryId, page, size));
    }

    @Operation(summary = "获取疾病详情")
    @GetMapping("/{id}")
    public Result<Illness> detail(@PathVariable Integer id) {
        var illness = illnessService.getById(id);
        return illness == null ? Result.notFound("疾病不存在") : Result.success(illness);
    }

    @Operation(summary = "获取所有疾病分类")
    @GetMapping("/categories")
    public Result<List<IllnessCategory>> categories() { return Result.success(illnessService.getAllCategories()); }

    @Operation(summary = "新增疾病")
    @PostMapping @PreAuthorize("hasRole('ADMIN')")
    public Result<Illness> create(@RequestBody Illness illness) { return Result.success(illnessService.save(illness)); }

    @Operation(summary = "更新疾病")
    @PutMapping("/{id}") @PreAuthorize("hasRole('ADMIN')")
    public Result<Illness> update(@PathVariable Integer id, @RequestBody Illness illness) { illness.setId(id); return Result.success(illnessService.update(illness)); }

    @Operation(summary = "删除疾病")
    @DeleteMapping("/{id}") @PreAuthorize("hasRole('ADMIN')")
    public Result<?> delete(@PathVariable Integer id) { illnessService.delete(id); return Result.success(); }
}
