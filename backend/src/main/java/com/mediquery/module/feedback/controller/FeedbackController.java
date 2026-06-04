package com.mediquery.module.feedback.controller;

import com.mediquery.common.dto.Result;
import com.mediquery.module.feedback.entity.Feedback;
import com.mediquery.module.feedback.service.FeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "意见反馈")
@RestController
@RequestMapping("/api/v1/feedbacks")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) { this.feedbackService = feedbackService; }

    @Operation(summary = "提交反馈")
    @PostMapping
    public Result<Feedback> submit(@RequestBody Feedback feedback) { return Result.success(feedbackService.submit(feedback)); }

    @Operation(summary = "获取反馈列表")
    @GetMapping @PreAuthorize("hasRole('ADMIN')")
    public Result<List<Feedback>> listAll() { return Result.success(feedbackService.listAll()); }

    @Operation(summary = "回复反馈")
    @PutMapping("/{id}/reply") @PreAuthorize("hasRole('ADMIN')")
    public Result<Feedback> reply(@PathVariable Integer id, @RequestBody Map<String, String> body) { return Result.success(feedbackService.reply(id, body.get("reply"))); }
}
