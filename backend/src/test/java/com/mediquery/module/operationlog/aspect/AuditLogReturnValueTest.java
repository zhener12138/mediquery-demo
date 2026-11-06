package com.mediquery.module.operationlog.aspect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuditLog#returnValueMustBe()} 的判定语义。
 *
 * <p>单独一个测试类是因为这里的规则很容易写错，而写错的后果是
 * 「审计日志悄悄少记」或「记了一条什么都没发生的假记录」——
 * 两种都不会报错，只能靠测试钉住。
 */
class AuditLogReturnValueTest {

    @Test
    @DisplayName("未声明时：任何返回值都记")
    void unsetMeansAlwaysRecord() {
        assertThat(AuditLogAspect.returnValueAllows(stub(""), Boolean.FALSE)).isTrue();
        assertThat(AuditLogAspect.returnValueAllows(stub(""), null)).isTrue();
        assertThat(AuditLogAspect.returnValueAllows(stub(""), "whatever")).isTrue();
    }

    @Test
    @DisplayName("声明 true：返回 false 就不记")
    void falseSkipsRecording() {
        AuditLog annotation = stub("true");

        assertThat(AuditLogAspect.returnValueAllows(annotation, Boolean.TRUE)).isTrue();
        assertThat(AuditLogAspect.returnValueAllows(annotation, Boolean.FALSE)).isFalse();
    }

    @Test
    @DisplayName("声明 true：返回 null 不记 —— null 不是成功")
    void nullIsNotSuccess() {
        assertThat(AuditLogAspect.returnValueAllows(stub("true"), null)).isFalse();
    }

    @Test
    @DisplayName("注解写错（返回值不是 Boolean）时不记 —— 拿不出成功证据")
    void nonBooleanReturnSkipsRecording() {
        assertThat(AuditLogAspect.returnValueAllows(stub("true"), "yes")).isFalse();
        assertThat(AuditLogAspect.returnValueAllows(stub("true"), 1)).isFalse();
    }

    @Test
    @DisplayName("大小写与空白都容错")
    void trimsAndIgnoresCase() {
        assertThat(AuditLogAspect.returnValueAllows(stub(" TRUE "), Boolean.TRUE)).isTrue();
        assertThat(AuditLogAspect.returnValueAllows(stub("False"), Boolean.FALSE)).isTrue();
    }

    private static AuditLog stub(String returnValueMustBe) {
        return new AuditLog() {
            @Override
            public String value() {
                return "测试动作";
            }

            @Override
            public String module() {
                return "测试";
            }

            @Override
            public String returnValueMustBe() {
                return returnValueMustBe;
            }

            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return AuditLog.class;
            }
        };
    }
}
