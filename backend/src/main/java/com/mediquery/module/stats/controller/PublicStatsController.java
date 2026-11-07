package com.mediquery.module.stats.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mediquery.common.dto.Result;
import com.mediquery.module.consultation.entity.Consultation;
import com.mediquery.module.consultation.entity.ConsultationStatus;
import com.mediquery.module.consultation.mapper.ConsultationMapper;
import com.mediquery.module.illness.entity.Illness;
import com.mediquery.module.illness.mapper.IllnessMapper;
import com.mediquery.module.medicine.entity.Medicine;
import com.mediquery.module.medicine.mapper.MedicineMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 公开统计接口：给登录页／首页展示「这个问诊机器人攒了多少东西」。
 *
 * <p>口径只保留三类，全部是聚合计数，不涉及任何患者数据：
 * <ul>
 *   <li>{@code consultationCount} —— 累计完成的问诊单数（只数 COMPLETED，进行中的不算）；</li>
 *   <li>{@code illnessCount} —— 知识库收录的疾病条目（RAG 召回的底座）；</li>
 *   <li>{@code medicineCount} —— 用药知识库条目。</li>
 * </ul>
 *
 * <p>为什么不复用后台大屏：那个接口带 {@code hasRole('ADMIN')}，未登录访客调它会被 403；
 * 而首页是公开页，只需要几个可以公开展示的数字。
 *
 * <p>三个计数都是走索引的 {@code count}，不做全表扫。
 */
@Tag(name = "公开统计")
@RestController
@RequestMapping("/api/v1/stats")
public class PublicStatsController {

    private final ConsultationMapper consultationMapper;
    private final IllnessMapper illnessMapper;
    private final MedicineMapper medicineMapper;

    public PublicStatsController(ConsultationMapper consultationMapper, IllnessMapper illnessMapper,
                                 MedicineMapper medicineMapper) {
        this.consultationMapper = consultationMapper;
        this.illnessMapper = illnessMapper;
        this.medicineMapper = medicineMapper;
    }

    @Operation(summary = "公开展示统计")
    @GetMapping
    public Result<Map<String, Object>> stats() {
        // LinkedHashMap 保证前端拿到的字段顺序稳定，调试时好读
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("consultationCount", consultationMapper.selectCount(
                new LambdaQueryWrapper<Consultation>()
                        .eq(Consultation::getStatus, ConsultationStatus.COMPLETED)));
        // 只统计已发布（status=1）的疾病，下架的不算数
        data.put("illnessCount", illnessMapper.selectCount(
                new LambdaQueryWrapper<Illness>().eq(Illness::getStatus, 1)));
        data.put("medicineCount", medicineMapper.selectCount(
                new LambdaQueryWrapper<Medicine>().eq(Medicine::getStatus, 1)));
        data.put("serverTime", LocalDateTime.now());
        return Result.success(data);
    }
}
