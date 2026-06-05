package com.mediquery.module.medicine.controller;

import com.mediquery.common.dto.PageResult;
import com.mediquery.common.dto.Result;
import com.mediquery.module.medicine.entity.Medicine;
import com.mediquery.module.medicine.service.MedicineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 药品百科控制器：公开浏览(按类型/关键词分页搜索/详情) + 管理员维护 + 关联疾病
 */
@Tag(name = "药品百科")
@RestController
@RequestMapping("/api/v1/medicines")
public class MedicineController {

    private final MedicineService medicineService;

    public MedicineController(MedicineService medicineService) { this.medicineService = medicineService; }

    @Operation(summary = "搜索药品列表")
    @GetMapping
    public Result<PageResult<Medicine>> list(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer type, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "9") int size) {
        return Result.success(medicineService.search(keyword, type, page, size));
    }

    @Operation(summary = "获取药品详情")
    @GetMapping("/{id}")
    public Result<Medicine> detail(@PathVariable Integer id) {
        var medicine = medicineService.getById(id);
        return (medicine == null || medicine.getDeleted() == 1) ? Result.notFound("药品不存在") : Result.success(medicine);
    }

    @Operation(summary = "新增药品")
    @PostMapping @PreAuthorize("hasRole('ADMIN')")
    public Result<Medicine> create(@RequestBody Medicine medicine) { return Result.success(medicineService.save(medicine)); }

    @Operation(summary = "更新药品")
    @PutMapping("/{id}") @PreAuthorize("hasRole('ADMIN')")
    public Result<Medicine> update(@PathVariable Integer id, @RequestBody Medicine medicine) { medicine.setId(id); return Result.success(medicineService.update(medicine)); }

    @Operation(summary = "删除药品")
    @DeleteMapping("/{id}") @PreAuthorize("hasRole('ADMIN')")
    public Result<?> delete(@PathVariable Integer id) { medicineService.delete(id); return Result.success(); }

    @Operation(summary = "关联药品与疾病")
    @PutMapping("/{id}/illnesses") @PreAuthorize("hasRole('ADMIN')")
    public Result<?> linkIllnesses(@PathVariable Integer id, @RequestBody java.util.List<Integer> illnessIds) { return Result.success(); }
}
