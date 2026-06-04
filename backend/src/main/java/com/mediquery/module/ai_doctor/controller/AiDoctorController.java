package com.mediquery.module.ai_doctor.controller;

import com.mediquery.common.dto.Result;
import com.mediquery.module.ai_doctor.entity.AiChatRecord;
import com.mediquery.module.ai_doctor.service.AiDoctorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@Tag(name = "AI智能医生")
@RestController
@RequestMapping("/api/v1/ai-doctor")
public class AiDoctorController {

    private final AiDoctorService aiDoctorService;

    public AiDoctorController(AiDoctorService aiDoctorService) { this.aiDoctorService = aiDoctorService; }

    @Operation(summary = "发送消息给智能医生")
    @PostMapping("/chat")
    public Result<Map<String, String>> chat(Authentication auth, @RequestBody Map<String, String> body) {
        var reply = aiDoctorService.chat(getUserId(auth), body.get("content"));
        return Result.success(Map.of("reply", reply));
    }

    @Operation(summary = "流式对话 (SSE)")
    @PostMapping("/chat/stream")
    public SseEmitter chatStream(Authentication auth, @RequestBody Map<String, String> body) {
        return aiDoctorService.chatStream(getUserId(auth), body.get("content"));
    }

    @Operation(summary = "获取对话历史")
    @GetMapping("/history")
    public Result<List<AiChatRecord>> history(Authentication auth) { return Result.success(aiDoctorService.getHistory(getUserId(auth))); }

    private Integer getUserId(Authentication auth) { return 1; }
}
