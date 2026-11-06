package com.mediquery.module.operationlog.aspect;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个需要写审计日志的方法。
 *
 * <p>只加在**改变状态**的方法上（创建/完结/取消/删除/管理端增删改），
 * 不加在查询上。理由：审计日志回答的是「谁改了什么」，
 * 查询由访问日志回答。给每个 GET 都写一条，这张表一天就能涨几十万行，
 * 而其中 99% 是「某人看了一眼药品列表」—— 真出事时反而找不到关键那几条。
 *
 * <h3>为什么不写成 {@code @Log(module = "问诊")}</h3>
 *
 * <p>{@code module} 带默认值，绝大多数场景只用写 {@code @AuditLog("完结问诊")}，
 * 注解里只保留一个必填项。默认的 {@code module} 用空串表示「不填」，
 * 由切面从类名推导 —— 但更推荐显式写，推导出来的名字给人看时往往不够准确。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {

    /** 操作动作，如「完结问诊」「删除疾病」。必填，因为它是这条日志的核心信息。 */
    String value();

    /** 操作模块。不填时切面退化为类名。 */
    String module() default "";

    /**
     * 只有方法返回指定值时才记日志。
     *
     * <p>默认记（任何正常返回都算成功）。目前只有一处用到：
     * 超时关闭任务的 {@code closeTimeout(id)} 返回 {@code boolean}，
     * {@code false} 表示「这张单子已经被别人关了 / 已经不在进行中」——
     * 那次调用什么都没改，记一条「超时关闭」就是假记录。
     *
     * <p>用字符串而不是 {@code Object}：注解属性不能用装箱基本类型，
     * 而 {@code "true"} / {@code "false"} 这种写法一眼能看出意图。
     * 空串表示不校验返回值。
     */
    String returnValueMustBe() default "";
}
