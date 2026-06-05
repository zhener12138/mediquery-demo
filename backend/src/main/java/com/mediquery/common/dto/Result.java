package com.mediquery.common.dto;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 统一响应体，所有API返回都用此类包装
 */
@Data
public class Result<T> {
    private int code;
    private String message;
    private T data;
    private LocalDateTime timestamp;

    public Result() {}
    public Result(int code, String message, T data, LocalDateTime timestamp) {
        this.code = code; this.message = message; this.data = data; this.timestamp = timestamp;
    }

    public static <T> Result<T> success(T data) { return new Result<>(200, "操作成功", data, LocalDateTime.now()); }
    public static <T> Result<T> success(String message, T data) { return new Result<>(200, message, data, LocalDateTime.now()); }
    public static <T> Result<T> success() { return new Result<>(200, "操作成功", null, LocalDateTime.now()); }
    public static <T> Result<T> error(int code, String message) { return new Result<>(code, message, null, LocalDateTime.now()); }
    public static <T> Result<T> error(String message) { return new Result<>(500, message, null, LocalDateTime.now()); }
    public static <T> Result<T> notFound(String message) { return new Result<>(404, message, null, LocalDateTime.now()); }
    public static <T> Result<T> unauthorized(String message) { return new Result<>(401, message, null, LocalDateTime.now()); }
    public static <T> Result<T> forbidden(String message) { return new Result<>(403, message, null, LocalDateTime.now()); }
    public static <T> Result<T> badRequest(String message) { return new Result<>(400, message, null, LocalDateTime.now()); }
}
