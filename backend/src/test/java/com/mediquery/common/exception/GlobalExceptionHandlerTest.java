package com.mediquery.common.exception;

import com.mediquery.common.dto.Result;
import com.mediquery.module.consultation.statemachine.IllegalStateTransitionException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.lang.reflect.Method;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 异常 → HTTP 状态码的映射约束。
 *
 * <h3>为什么这个类值得单独测</h3>
 *
 * <p>这是全站唯一一处把内部错误翻译成对外语义的地方。它有个隐蔽的历史 bug：
 * 原先所有业务错误都返回 HTTP 200 + body 里的 code，前端 {@code if (status === 401)}
 * 永远不成立 —— 于是"用户名或密码错误"压根不会显示给用户，
 * "会话过期"也不会跳登录页。所以这里断言的不是"抛了没抛"，
 * 而是<b>状态码对不对</b>。
 *
 * <h3>状态码从哪来</h3>
 *
 * <p>{@code handleBusinessException} 返回 {@link ResponseEntity}，状态码在对象里；
 * 其余处理器返回 {@code Result} 并把状态码写在 {@link org.springframework.web.bind.annotation.ResponseStatus}
 * 注解上。两种都要断言 —— 注解写错了线上就是 500，而方法体照样返回正确文案。
 * 所以 {@link #statusOf(String, Object...)} 两种形态都支持。
 *
 * <p>另一条不能破的规矩：500 对外必须只说"服务器内部错误 + 错误编号"，
 * 不能把堆栈或异常消息吐出去（可能含 SQL、路径、内部类名）。
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    /**
     * 调用处理器并拿到它最终会呈现的 HTTP 状态码。
     *
     * <p>返回 {@link ResponseEntity} 的直接取状态；返回 {@code Result} 的读
     * {@code @ResponseStatus} —— 后者正是 Spring MVC 在渲染响应时采用的值，
     * 测它等同于测真实行为，而不需要一个完整容器。
     */
    private int statusOf(String methodName, Object... args) {
        try {
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                types[i] = args[i] instanceof Exception e
                        ? mostSpecificDeclared(methodName, e)
                        : args[i].getClass();
            }
            Method m = handler.getClass().getDeclaredMethod(methodName, types);
            Object result = m.invoke(handler, args);
            if (result instanceof ResponseEntity<?> re) {
                return re.getStatusCode().value();
            }
            // 必须用 findMergedAnnotation：@ResponseStatus 的 value() 与 code() 是
            // @AliasFor 关系，直接反射拿 code() 拿不到 value() 里写的值，
            // 会一路返回默认的 INTERNAL_SERVER_ERROR（我第一次跑就是这么红的）。
            var rs = org.springframework.core.annotation.AnnotatedElementUtils
                    .findMergedAnnotation(m, org.springframework.web.bind.annotation.ResponseStatus.class);
            assertThat(rs)
                    .as("%s 既没返回 ResponseEntity 也没标 @ResponseStatus，状态码会变成 200", methodName)
                    .isNotNull();
            return rs.code().value();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("反射调用 " + methodName + " 失败: " + e, e);
        }
    }

    /**
     * 处理器方法参数声明的是具体异常类型（如 {@code BindException}），
     * 而 {@code getClass()} 拿到的是运行时类型；这里按声明类型回查一次。
     */
    private Class<?> mostSpecificDeclared(String methodName, Exception e) {
        for (Method m : handler.getClass().getDeclaredMethods()) {
            if (!m.getName().equals(methodName) || m.getParameterCount() != 1) continue;
            if (m.getParameterTypes()[0].isInstance(e)) {
                return m.getParameterTypes()[0];
            }
        }
        return Exception.class;
    }

    private Result<?> bodyOf(String methodName, Object... args) {
        try {
            for (Method m : handler.getClass().getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) continue;
                if (m.getParameterCount() != args.length) continue;
                if (!m.getParameterTypes()[0].isInstance(args[0])) continue;
                Object r = m.invoke(handler, args);
                return r instanceof ResponseEntity<?> re ? (Result<?>) re.getBody() : (Result<?>) r;
            }
            throw new AssertionError("找不到 " + methodName);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // ───────── 业务异常：code 决定状态码 ─────────

    @Test
    @DisplayName("404 业务异常 → HTTP 404")
    void notFoundMapsTo404() {
        var e = BusinessException.notFound("问诊不存在");

        assertThat(statusOf("handleBusinessException", e)).isEqualTo(404);
        assertThat(bodyOf("handleBusinessException", e).getCode()).isEqualTo(404);
        assertThat(bodyOf("handleBusinessException", e).getMessage()).isEqualTo("问诊不存在");
    }

    @Test
    @DisplayName("401 业务异常 → HTTP 401")
    void unauthorizedMapsTo401() {
        assertThat(statusOf("handleBusinessException",
                BusinessException.unauthorized("用户名或密码错误"))).isEqualTo(401);
    }

    @Test
    @DisplayName("403 业务异常 → HTTP 403")
    void forbiddenMapsTo403() {
        assertThat(statusOf("handleBusinessException",
                BusinessException.forbidden("账号已被禁用"))).isEqualTo(403);
    }

    @Test
    @DisplayName("400 业务异常 → HTTP 400")
    void badRequestMapsTo400() {
        assertThat(statusOf("handleBusinessException",
                BusinessException.badRequest("参数不对"))).isEqualTo(400);
    }

    @Test
    @DisplayName("带自定义 code 的 409 → HTTP 409")
    void conflictPassesThrough() {
        var e = new BusinessException(409, "该会话已被占用");

        assertThat(statusOf("handleBusinessException", e)).isEqualTo(409);
        assertThat(bodyOf("handleBusinessException", e).getCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("未识别的 code（如 418）→ 500，不把奇怪的状态码透出去")
    void unknownCodeFallsBackTo500() {
        assertThat(statusOf("handleBusinessException",
                new BusinessException(418, "我是茶壶"))).isEqualTo(500);
    }

    @Test
    @DisplayName("默认构造的业务异常（code=500）→ HTTP 500")
    void defaultBusinessExceptionIs500() {
        assertThat(statusOf("handleBusinessException",
                new BusinessException("出错了"))).isEqualTo(500);
    }

    // ───────── 状态机冲突 ─────────

    @Test
    @DisplayName("状态转移冲突 → HTTP 409，且 code 是 409；终态的话给一句人话")
    void illegalTransitionMapsTo409() {
        // dimension 必须是「问诊状态」—— buildMessage 靠这个字符串区分主状态与小结状态，
        // 传 "consultation" 会掉进小结分支，消息里就没有「已完成」了。
        var e = new IllegalStateTransitionException(
                "问诊状态",
                com.mediquery.module.consultation.entity.ConsultationStatus.COMPLETED,
                com.mediquery.module.consultation.entity.ConsultationStatus.COMPLETED);

        assertThat(statusOf("handleIllegalTransition", e)).isEqualTo(409);
        assertThat(bodyOf("handleIllegalTransition", e).getCode()).isEqualTo(409);
        assertThat(bodyOf("handleIllegalTransition", e).getMessage())
                .isEqualTo("本次问诊已完成，这张单已经关闭，不能再操作");
    }

    // ───────── 安全异常 ─────────

    @Test
    @DisplayName("登录凭证错误 → HTTP 401")
    void badCredentialsMapsTo401() {
        assertThat(statusOf("handleBadCredentials",
                new BadCredentialsException("bad"))).isEqualTo(401);
    }

    @Test
    @DisplayName("已登录但无权限 → HTTP 403")
    void accessDeniedMapsTo403() {
        assertThat(statusOf("handleAccessDenied",
                new AccessDeniedException("nope"))).isEqualTo(403);
    }

    // ───────── 参数校验 ─────────

    @Test
    @DisplayName("ConstraintViolationException 透传消息")
    @SuppressWarnings("unchecked")
    void constraintViolationKeepsMessage() {
        ConstraintViolation<Object> v = mock(ConstraintViolation.class);
        when(v.getMessage()).thenReturn("密码长度必须在 6 到 32 之间");
        var e = new ConstraintViolationException(Set.of(v));

        assertThat(statusOf("handleConstraintViolation", e)).isEqualTo(400);
        // ConstraintViolationException.getMessage() 是 "propertyPath: message" 拼起来的，
        // 所以用 contains 而不是 isEqualTo —— 这里要保的是「原文没被吞掉」。
        assertThat(bodyOf("handleConstraintViolation", e).getMessage())
                .contains("密码长度必须在 6 到 32 之间");
    }

    @Test
    @DisplayName("ConstraintViolationException 消息集合为空时不 NPE")
    void constraintViolationWithEmptySetIsSafe() {
        var e = new ConstraintViolationException(Set.of());

        assertThat(statusOf("handleConstraintViolation", e)).isEqualTo(400);
    }

    @Test
    @DisplayName("畸形 JSON → 400，且不把 Jackson 的原文吐出去")
    void unreadableBodyIs400() {
        var e = new HttpMessageNotReadableException(
                "JSON parse error: Unexpected character", (org.springframework.http.HttpInputMessage) null);

        assertThat(statusOf("handleNotReadable", e)).isEqualTo(400);
        assertThat(bodyOf("handleNotReadable", e).getMessage())
                .isEqualTo("请求格式错误，请检查 JSON 是否合法");
    }

    @Test
    @DisplayName("路径参数类型不对 → 400，并说明是哪个参数")
    void typeMismatchNamesTheParameter() {
        var e = new MethodArgumentTypeMismatchException(
                "abc", Integer.class, "id", null, new NumberFormatException());

        assertThat(statusOf("handleTypeMismatch", e)).isEqualTo(400);
        assertThat(bodyOf("handleTypeMismatch", e).getMessage()).contains("id");
    }

    @Test
    @DisplayName("缺少必填查询参数 → 400，并说明参数名")
    void missingParameterNamesIt() {
        var e = new MissingServletRequestParameterException("page", "int");

        assertThat(statusOf("handleMissingParam", e)).isEqualTo(400);
        assertThat(bodyOf("handleMissingParam", e).getMessage()).contains("page");
    }

    @Test
    @DisplayName("BindException 走同一个处理器：状态码是 400")
    void bindExceptionIs400() {
        var be = new BindException(new Object(), "form");
        be.addError(new FieldError("form", "username", "用户名不能为空"));

        assertThat(statusOf("handleValidation", be)).isEqualTo(400);
    }

    /**
     * 这条用文档化的方式钉住一个已知缺口。
     *
     * <p>{@code handleValidation} 同时处理 {@code MethodArgumentNotValidException} 与
     * {@code BindException}，但只有一个分支取字段级消息。所以走 {@code BindException}
     * 时用户看到的是泛泛的「参数校验失败」，而不是「用户名不能为空」。
     *
     * <p>当前项目所有 {@code @Valid} 都挂在 {@code @RequestBody} 上，触发的是
     * 前者，所以这条缺口还没有暴露。写在这里是为了：将来真要用表单绑定时，
     * 这条测试会提醒你补分支，而不是让用户对着「参数校验失败」猜哪个字段错了。
     */
    @Test
    @DisplayName("已知缺口：BindException 拿不到字段级消息，只回通用文案")
    void bindExceptionFallsBackToGenericMessage() {
        var be = new BindException(new Object(), "form");
        be.addError(new FieldError("form", "username", "用户名不能为空"));

        assertThat(bodyOf("handleValidation", be).getMessage()).isEqualTo("参数校验失败");
    }

    // ───────── 路由 / 方法 ─────────

    @Test
    @DisplayName("HTTP 方法不支持 → 405")
    void methodNotSupportedIs405() {
        var e = new HttpRequestMethodNotSupportedException("DELETE");

        assertThat(statusOf("handleMethodNotSupported", e)).isEqualTo(405);
        assertThat(bodyOf("handleMethodNotSupported", e).getMessage()).contains("DELETE");
    }

    @Test
    @DisplayName("NoResourceFoundException（静态资源没找到）→ 404 而不是 500")
    void noResourceFoundIs404() {
        var e = new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/nope.js");

        assertThat(statusOf("handleNotFound", e)).isEqualTo(404);
    }

    @Test
    @DisplayName("NoHandlerFoundException（没有这个接口）→ 404")
    void noHandlerFoundIs404() {
        var e = new NoHandlerFoundException("GET", "/api/v1/nope", null);

        assertThat(statusOf("handleNotFound", e)).isEqualTo(404);
    }

    // ───────── 兜底 500 ─────────

    @Test
    @DisplayName("未预期异常 → 500，且对外只说通用话，不泄露异常消息")
    void unexpectedExceptionHidesDetails() {
        var e = new IllegalStateException("connection to 10.0.0.5:3306 refused");

        assertThat(statusOf("handleException", e)).isEqualTo(500);
        String msg = bodyOf("handleException", e).getMessage();
        assertThat(msg).doesNotContain("10.0.0.5");
        assertThat(msg).doesNotContain("3306");
        assertThat(msg).doesNotContain("IllegalStateException");
    }

    @Test
    @DisplayName("500 时带上 traceId，用户报障能对上日志")
    void unexpectedExceptionCarriesTraceId() {
        MDC.put("traceId", "abc123def456");

        assertThat(bodyOf("handleException", new RuntimeException("boom")).getMessage())
                .contains("abc123def456");
    }

    @Test
    @DisplayName("没有 traceId 时也不出现空括号")
    void missingTraceIdDoesNotLeaveEmptyBrackets() {
        assertThat(bodyOf("handleException", new RuntimeException("boom")).getMessage())
                .isEqualTo("服务器内部错误");
    }

    @Test
    @DisplayName("500 的 body code 是 500，不是可疑的 0")
    void unexpectedExceptionBodyCodeIs500() {
        assertThat(bodyOf("handleException", new RuntimeException("boom")).getCode()).isEqualTo(500);
    }

    @Test
    @DisplayName("HttpStatus 常量与断言用的字面量一致（防止注解被改错）")
    void statusConstantsMatch() {
        assertThat(HttpStatus.NOT_FOUND.value()).isEqualTo(404);
        assertThat(HttpStatus.CONFLICT.value()).isEqualTo(409);
    }
}
