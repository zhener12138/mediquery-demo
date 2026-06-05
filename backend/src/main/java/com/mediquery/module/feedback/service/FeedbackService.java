package com.mediquery.module.feedback.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mediquery.module.feedback.entity.Feedback;
import com.mediquery.module.feedback.mapper.FeedbackMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 意见反馈服务：用户提交(待处理) → 管理员回复(已处理) 的状态流转
 */
@Service
public class FeedbackService {

    private final FeedbackMapper feedbackMapper;

    public FeedbackService(FeedbackMapper feedbackMapper) {
        this.feedbackMapper = feedbackMapper;
    }

    // 用户提交反馈（默认状态=待处理）
    public Feedback submit(Feedback feedback) { feedback.setStatus(0); feedbackMapper.insert(feedback); return feedback; }

    // 管理员查看所有反馈
    public List<Feedback> listAll() {
        return feedbackMapper.selectList(new LambdaQueryWrapper<Feedback>().orderByDesc(Feedback::getCreateTime));
    }

    // 管理员回复反馈（状态 → 已处理）
    public Feedback reply(Integer id, String adminReply) {
        var feedback = feedbackMapper.selectById(id);
        if (feedback != null) { feedback.setAdminReply(adminReply); feedback.setStatus(1); feedbackMapper.updateById(feedback); }
        return feedback;
    }
}
