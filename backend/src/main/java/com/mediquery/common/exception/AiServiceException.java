package com.mediquery.common.exception;

/**
 * AI 服务异常：把 DashScope 的真实故障（限流/鉴权/欠费/超时）与「正常回答」区分开。
 *
 * <p>早期实现里 DashScopeClient 捕获所有异常后返回一句中文兜底文案，
 * HTTP 仍然是 200，前端会把「智能医生现在不在线」当成模型的正常回复渲染。
 * 这导致：① 用户无法区分故障与回答；② 运维不知道 AI 已经挂了；③ 无法告警。
 *
 * <p>本异常携带厂商错误码，前端可据此给出可操作提示（如「请求过于频繁，请稍后再试」）。
 */
public class AiServiceException extends RuntimeException {

    /** 厂商返回的状态码，0 表示未拿到（网络层失败） */
    private final int upstreamStatus;

    /** 厂商错误码，如 Throttling.RateQuota / InvalidApiKey / AccessDenied */
    private final String upstreamCode;

    public AiServiceException(int upstreamStatus, String upstreamCode, String message) {
        super(message);
        this.upstreamStatus = upstreamStatus;
        this.upstreamCode = upstreamCode;
    }

    public int getUpstreamStatus() { return upstreamStatus; }

    public String getUpstreamCode() { return upstreamCode; }

    /** 限流：可重试。detail 只用于服务端日志，不拼进用户可见文案 */
    public static AiServiceException rateLimited(String detail) {
        return new AiServiceException(429, "Throttling.RateQuota",
                "AI 助手当前请求较多，请稍后重试");
    }

    /** 鉴权失败：配置问题，不该重试 */
    public static AiServiceException authFailed(String code, String detail) {
        return new AiServiceException(401, code,
                "AI 服务鉴权失败，请检查 API Key 配置" + (detail == null ? "" : "（" + detail + "）"));
    }

    /** 模型未开通/无权限 */
    public static AiServiceException modelDenied(String model, String code) {
        return new AiServiceException(403, code,
                "AI 助手暂不可用（模型 " + model + " 未开通），请联系管理员");
    }

    /** 其他上游故障 */
    public static AiServiceException upstream(int status, String code, String message) {
        return new AiServiceException(status, code, "AI 助手暂时无法回答，请稍后重试");
    }
}
