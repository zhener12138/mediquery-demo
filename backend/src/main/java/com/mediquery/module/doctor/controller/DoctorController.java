package com.mediquery.module.doctor.controller;

import com.mediquery.common.exception.BusinessException;
import com.mediquery.common.dto.PageResult;
import com.mediquery.common.dto.Result;
import com.mediquery.module.doctor.entity.Doctor;
import com.mediquery.module.doctor.service.DoctorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.mediquery.module.operationlog.aspect.AuditLog;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 医生管理控制器：公开浏览 + 管理员维护(增删改)
 */
@Tag(name = "医生管理")
@RestController
@RequestMapping("/api/v1/doctors")
public class DoctorController {

    private final DoctorService doctorService;

    public DoctorController(DoctorService doctorService) { this.doctorService = doctorService; }

    @Operation(summary = "搜索医生列表")
    @GetMapping
    public Result<PageResult<Doctor>> list(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String department, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "9") int size) {
        return Result.success(doctorService.search(keyword, department, page, size));
    }

    @Operation(summary = "获取医生详情")
    @GetMapping("/{id}")
    public Result<Doctor> detail(@PathVariable Integer id) {
        var doctor = doctorService.getById(id);
        if (doctor == null) throw BusinessException.notFound("医生不存在");
        return Result.success(doctor);
    }

    @Operation(summary = "新增医生")
    @PostMapping @PreAuthorize("hasRole('ADMIN')")
    @AuditLog(value = "新增医生", module = "医生管理")
    public Result<Doctor> create(@RequestBody Doctor doctor) { return Result.success(doctorService.save(doctor)); }

    @Operation(summary = "更新医生信息")
    @PutMapping("/{id}") @PreAuthorize("hasRole('ADMIN')")
    @AuditLog(value = "修改医生", module = "医生管理")
    public Result<Doctor> update(@PathVariable Integer id, @RequestBody Doctor doctor) { doctor.setId(id); return Result.success(doctorService.update(doctor)); }

    @Operation(summary = "删除医生")
    @DeleteMapping("/{id}") @PreAuthorize("hasRole('ADMIN')")
    @AuditLog(value = "删除医生", module = "医生管理")
    public Result<?> delete(@PathVariable Integer id) { doctorService.delete(id); return Result.success(); }
}
