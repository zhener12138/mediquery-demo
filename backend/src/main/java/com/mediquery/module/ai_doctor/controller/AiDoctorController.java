package com.mediquery.module.ai_doctor.controller;

import com.mediquery.module.ai_doctor.service.AiDoctorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * AI智能医生控制器：RAG 增强对话 + SSE 流式推送。
 */
@Tag(name = "AI智能医生")
@RestController
@RequestMapping("/api/v1/ai-doctor")
public class AiDoctorController {

    private final AiDoctorService aiDoctorService;

    public AiDoctorController(AiDoctorService aiDoctorService) { this.aiDoctorService = aiDoctorService; }

    /**
     * RAG 增强对话：前端传入 sessionId 用于维护知识上下文缓存。
     * 首次对话由前端生成 UUID 作为 sessionId，后续消息复用。
     */
    @Operation(summary = "流式对话 (SSE)")
    @PostMapping("/chat/stream")
    public SseEmitter chatStream(Authentication auth, @RequestBody Map<String, String> body) {
        return aiDoctorService.chatStream(getUserId(auth), body.get("sessionId"), body.get("content"));
    }

    private Integer getUserId(Authentication auth) {
        var userId = auth.getDetails();
        if (userId instanceof Long) {
            return ((Long) userId).intValue();
        }
        throw new IllegalStateException("无法从认证信息中获取用户ID");
    }
}
