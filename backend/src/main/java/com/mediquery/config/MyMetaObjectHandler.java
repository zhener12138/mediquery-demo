package com.mediquery.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 自动填充处理器。
 *
 * <p>实体上标了 {@code @TableField(fill = FieldFill.INSERT)} / {@code INSERT_UPDATE}
 * 却未注册本处理器时，insert 语句会把 create_time 显式写成 NULL。
 * MySQL 8 中「显式写 NULL」不会触发 DDL 的 {@code DEFAULT CURRENT_TIMESTAMP}，
 * 结果是 create_time 全表为 NULL，任何 orderBy(createTime) 查询退化为无序。
 *
 * <p>本类为插入/更新时的 createTime、updateTime 提供真实时间值。
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    private static final String CREATE_TIME = "createTime";
    private static final String UPDATE_TIME = "updateTime";

    @Override
    public void insertFill(MetaObject metaObject) {
        var now = LocalDateTime.now();
        // strictInsertFill 只在字段存在且当前值为 null 时填充，不会覆盖调用方显式设置的值
        this.strictInsertFill(metaObject, CREATE_TIME, LocalDateTime.class, now);
        this.strictInsertFill(metaObject, UPDATE_TIME, LocalDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        // 更新时间总是覆盖，保证审计字段可信
        this.strictUpdateFill(metaObject, UPDATE_TIME, LocalDateTime.class, LocalDateTime.now());
    }
}
