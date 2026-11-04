package com.mediquery.module.dashboard.controller;

import com.mediquery.common.dto.Result;
import com.mediquery.module.doctor.mapper.DoctorMapper;
import com.mediquery.module.feedback.mapper.FeedbackMapper;
import com.mediquery.module.illness.mapper.IllnessMapper;
import com.mediquery.module.medicine.mapper.MedicineMapper;
import com.mediquery.module.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mediquery.module.feedback.entity.Feedback;
import com.mediquery.module.illness.entity.Illness;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@Tag(name = "数据大屏")
/**
 * 数据大屏控制器：总量统计 + 热门疾病TOP10（仅管理员可访问）
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@PreAuthorize("hasRole('ADMIN')")
public class DashboardController {

    private final UserMapper userMapper;
    private final IllnessMapper illnessMapper;
    private final MedicineMapper medicineMapper;
    private final DoctorMapper doctorMapper;
    private final FeedbackMapper feedbackMapper;

    public DashboardController(UserMapper userMapper, IllnessMapper illnessMapper,
                                MedicineMapper medicineMapper, DoctorMapper doctorMapper,
                                FeedbackMapper feedbackMapper) {
        this.userMapper = userMapper;
        this.illnessMapper = illnessMapper;
        this.medicineMapper = medicineMapper;
        this.doctorMapper = doctorMapper;
        this.feedbackMapper = feedbackMapper;
    }

    // 数据总览：统计用户/疾病/药品/医生/反馈总量
    @Operation(summary = "数据总览")
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        var data = new HashMap<String, Object>();
        data.put("userCount", userMapper.selectCount(null));
        data.put("illnessCount", illnessMapper.selectCount(new LambdaQueryWrapper<Illness>().eq(Illness::getStatus, 1)));
        data.put("medicineCount", medicineMapper.selectCount(null));
        data.put("doctorCount", doctorMapper.selectCount(null));
        data.put("feedbackCount", feedbackMapper.selectCount(null));
        data.put("pendingFeedbackCount", feedbackMapper.selectCount(new LambdaQueryWrapper<Feedback>().eq(Feedback::getStatus, 0)));
        return Result.success(data);
    }

    // 热门疾病TOP10（按浏览量降序）
    @Operation(summary = "热门疾病排行")
    @GetMapping("/popular/illnesses")
    public Result<List<Map<String, Object>>> popularIllnesses() {
        var illnesses = illnessMapper.selectList(new LambdaQueryWrapper<Illness>()
                .eq(Illness::getStatus, 1).orderByDesc(Illness::getViewCount).last("LIMIT 10"));
        var result = new ArrayList<Map<String, Object>>();
        for (var i : illnesses) result.add(Map.of("name", i.getName(), "viewCount", i.getViewCount()));
        return Result.success(result);
    }
}
