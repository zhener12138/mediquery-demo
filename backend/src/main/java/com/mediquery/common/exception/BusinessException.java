package com.mediquery.common.exception;

/**
 * 自定义业务异常，用于在Service层抛出，由全局异常处理器统一捕获
 */
public class BusinessException extends RuntimeException {
    private final int code;

    public int getCode() { return code; }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String message) {
        super(message);
        this.code = 500;
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(404, message);
    }

    public static BusinessException badRequest(String message) {
        return new BusinessException(400, message);
    }

    public static BusinessException unauthorized(String message) {
        return new BusinessException(401, message);
    }

    public static BusinessException forbidden(String message) {
        return new BusinessException(403, message);
    }
}
