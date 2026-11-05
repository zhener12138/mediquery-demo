package com.mediquery.module.dashboard.controller;

import com.mediquery.common.dto.Result;
import com.mediquery.module.doctor.mapper.DoctorMapper;
import com.mediquery.module.illness.entity.Illness;
import com.mediquery.module.illness.mapper.IllnessCategoryMapper;
import com.mediquery.module.illness.mapper.IllnessMapper;
import com.mediquery.module.medicine.entity.Medicine;
import com.mediquery.module.medicine.mapper.MedicineMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 公开统计接口：给首页那块「疾病 / 药品 / 医生」数字用。
 *
 * <p>为什么不复用 {@link DashboardController#overview()}：
 * 那个类带 {@code @PreAuthorize("hasRole('ADMIN')")}，未登录访客调它会被 403。
 * 首页是公开页，需要的只是几个可以公开展示的计数，没必要为了它开放整个大屏。
 *
 * <p>这里只返回能公开的三类资源计数，不含用户数、反馈数这类后台数据。
 * 三个计数都用 {@code count} 走索引，不做全表扫。
 */
@Tag(name = "公开统计")
@RestController
@RequestMapping("/api/v1/stats")
public class PublicStatsController {

    private final IllnessMapper illnessMapper;
    private final IllnessCategoryMapper categoryMapper;
    private final MedicineMapper medicineMapper;
    private final DoctorMapper doctorMapper;

    public PublicStatsController(IllnessMapper illnessMapper, IllnessCategoryMapper categoryMapper,
                                 MedicineMapper medicineMapper, DoctorMapper doctorMapper) {
        this.illnessMapper = illnessMapper;
        this.categoryMapper = categoryMapper;
        this.medicineMapper = medicineMapper;
        this.doctorMapper = doctorMapper;
    }

    @Operation(summary = "首页公开统计")
    @GetMapping
    public Result<Map<String, Object>> stats() {
        // LinkedHashMap 保证前端拿到的字段顺序稳定，调试时好读
        Map<String, Object> data = new LinkedHashMap<>();
        // 只统计已发布（status=1）且有分类的疾病，下架的不算数
        data.put("illnessCount", illnessMapper.selectCount(
                new LambdaQueryWrapper<Illness>().eq(Illness::getStatus, 1)));
        data.put("categoryCount", categoryMapper.selectCount(null));
        data.put("medicineCount", medicineMapper.selectCount(
                new LambdaQueryWrapper<Medicine>().eq(Medicine::getStatus, 1)));
        data.put("doctorCount", doctorMapper.selectCount(null));
        return Result.success(data);
    }
}
