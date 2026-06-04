package com.mediquery.module.feedback.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mediquery.module.feedback.entity.Feedback;
import com.mediquery.module.feedback.mapper.FeedbackMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FeedbackService {

    private final FeedbackMapper feedbackMapper;

    public FeedbackService(FeedbackMapper feedbackMapper) {
        this.feedbackMapper = feedbackMapper;
    }

    public Feedback submit(Feedback feedback) { feedback.setStatus(0); feedbackMapper.insert(feedback); return feedback; }

    public List<Feedback> listAll() {
        return feedbackMapper.selectList(new LambdaQueryWrapper<Feedback>().orderByDesc(Feedback::getCreateTime));
    }

    public Feedback reply(Integer id, String adminReply) {
        var feedback = feedbackMapper.selectById(id);
        if (feedback != null) { feedback.setAdminReply(adminReply); feedback.setStatus(1); feedbackMapper.updateById(feedback); }
        return feedback;
    }
}
