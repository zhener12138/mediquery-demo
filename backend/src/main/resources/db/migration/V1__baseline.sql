-- ============================================================
-- V1 · 基线：8 张表
--
-- 这份脚本由 db/init.sql 拆分而来，是 Flyway 的第一版迁移。
-- 它描述的是「项目收敛为纯在线问诊之后」的初始结构：
--   sys_user / illness_category / illness / medicine /
--   illness_medicine_rel / ai_chat_record / consultation / sys_operation_log
--
-- 与原 init.sql 的区别：
--   1. 没有 CREATE DATABASE / USE —— 库由部署方建，连接串里已经指名了；
--   2. 没有 DROP TABLE —— 迁移脚本只往前推，不毁数据；
--   3. 没有 4 个 intake 列 —— 那是 V2 加上的，V1 保持它被写出来时的样子。
--
-- 种子数据不在这里，见 db/seed.sql（Flyway 不管种子，它管结构）。
-- ============================================================

-- ============================================================
-- 系统用户表
-- ============================================================
CREATE TABLE `sys_user` (
    `id`          INT          NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username`    VARCHAR(50)  NOT NULL COMMENT '用户名',
    `password`    VARCHAR(200) NOT NULL COMMENT '密码(BCrypt加密)',
    `real_name`   VARCHAR(50)  DEFAULT NULL COMMENT '真实姓名',
    `age`         INT          DEFAULT NULL COMMENT '年龄',
    `sex`         VARCHAR(5)   DEFAULT NULL COMMENT '性别',
    `email`       VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `phone`       VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    `avatar_url`  VARCHAR(500) DEFAULT NULL COMMENT '头像URL',
    `role_status` TINYINT      NOT NULL DEFAULT 0 COMMENT '角色: 0-普通用户, 1-管理员',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 0-禁用, 1-正常',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0-未删除, 1-已删除',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`),
    UNIQUE KEY `uk_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统用户表';

-- ============================================================
-- 疾病分类表
-- ============================================================
CREATE TABLE `illness_category` (
    `id`          INT          NOT NULL AUTO_INCREMENT COMMENT '分类ID',
    `name`        VARCHAR(100) NOT NULL COMMENT '分类名称',
    `info`        TEXT         DEFAULT NULL COMMENT '分类描述',
    `sort_order`  INT          DEFAULT 0 COMMENT '排序',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='疾病分类表';

-- ============================================================
-- 疾病表
-- ============================================================
CREATE TABLE `illness` (
    `id`              INT          NOT NULL AUTO_INCREMENT COMMENT '疾病ID',
    `category_id`     INT          NOT NULL COMMENT '所属分类ID',
    `name`            VARCHAR(200) NOT NULL COMMENT '疾病名称',
    `cause`           TEXT         DEFAULT NULL COMMENT '病因',
    `symptom`         TEXT         DEFAULT NULL COMMENT '症状',
    `special_symptom` TEXT         DEFAULT NULL COMMENT '特殊症状',
    `view_count`      INT          DEFAULT 0 COMMENT '浏览量',
    `status`          TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 0-草稿, 1-已发布',
    `deleted`         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_category_id` (`category_id`),
    FULLTEXT KEY `ft_illness` (`name`, `cause`, `symptom`) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='疾病表';

-- ============================================================
-- 药品表
-- ============================================================
CREATE TABLE `medicine` (
    `id`            INT          NOT NULL AUTO_INCREMENT COMMENT '药品ID',
    `name`          VARCHAR(200) NOT NULL COMMENT '药品名称',
    `keyword`       VARCHAR(500) DEFAULT NULL COMMENT '搜索关键词',
    `effect`        TEXT         DEFAULT NULL COMMENT '功效',
    `brand`         VARCHAR(200) DEFAULT NULL COMMENT '品牌',
    `manufacturer`  VARCHAR(200) DEFAULT NULL COMMENT '生产厂商',
    `interaction`   TEXT         DEFAULT NULL COMMENT '药物相互作用',
    `contraindication` TEXT      DEFAULT NULL COMMENT '禁忌',
    `usage_dosage`  TEXT         DEFAULT NULL COMMENT '用法用量',
    `type`          TINYINT      DEFAULT 0 COMMENT '类型: 0-西药, 1-中药, 2-中成药',
    `price`         DECIMAL(10,2) DEFAULT NULL COMMENT '参考价格',
    `image_url`     VARCHAR(500) DEFAULT NULL COMMENT '图片URL',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 0-下架, 1-上架',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    FULLTEXT KEY `ft_medicine` (`name`, `keyword`, `effect`) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='药品表';

-- ============================================================
-- 疾病-药品关联表
-- ============================================================
CREATE TABLE `illness_medicine_rel` (
    `id`          INT NOT NULL AUTO_INCREMENT COMMENT '关联ID',
    `illness_id`  INT NOT NULL COMMENT '疾病ID',
    `medicine_id` INT NOT NULL COMMENT '药品ID',
    PRIMARY KEY (`id`),
    KEY `idx_illness_id` (`illness_id`),
    KEY `idx_medicine_id` (`medicine_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='疾病药品关联表';

-- 说明：下列表在「只保留在线问诊」重构中删除，DDL 与种子数据一并移除。
--   search_history / user_favorite —— 从建库起就没有实体、Mapper、Service，纯死表；
--   feedback —— 意见反馈模块整体下线，不属于问诊主线；
--   sys_doctor —— 本项目是纯 AI 问诊、不做人工接诊，医生表没有任何消费方。
-- 真要做这些能力时按新需求重建，不要从历史版本里捞半成品。

-- ============================================================
-- AI对话记录表
-- ============================================================
CREATE TABLE `ai_chat_record` (
    `id`          INT          NOT NULL AUTO_INCREMENT COMMENT '记录ID',
    `user_id`     INT          NOT NULL COMMENT '用户ID',
    `session_id`  VARCHAR(36)  NOT NULL COMMENT '会话ID(UUID)',
    `role`        VARCHAR(10)  NOT NULL COMMENT '角色: user/assistant',
    `content`     TEXT         NOT NULL COMMENT '消息内容',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_session` (`user_id`, `session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI对话记录表';

-- ============================================================
-- 问诊单表
--
-- 这是「在线问诊」的核心表：一次问诊 = 一张单，有明确的开始和结束。
--
-- 为什么需要它：ai_chat_record 只记「谁说了什么」，没有「这次问诊结束了没有」。
-- 没有状态，就没有状态机、没有并发争用、没有超时收尾 —— 也就没有可讲的业务复杂度。
--
-- summary_status 是刻意加的第二个状态维度：完结后要异步生成 AI 小结，
-- 生成是过程（可能失败、可能重试），不该和问诊单本身的生老病死混在一个字段里。
--
-- version 是乐观锁：患者点完结和定时任务判超时可能同时打同一张单。
-- ============================================================
CREATE TABLE `consultation` (
    `id`              INT          NOT NULL AUTO_INCREMENT COMMENT '问诊ID',
    `patient_id`      INT          NOT NULL COMMENT '患者ID',
    `session_id`      VARCHAR(36)  NOT NULL COMMENT '会话ID，与ai_chat_record.session_id对应',
    `chief_complaint` VARCHAR(500) NOT NULL COMMENT '主诉',
    `status`          TINYINT      NOT NULL DEFAULT 0 COMMENT '状态: 0-进行中, 1-已完成, 2-已取消, 3-超时关闭',
    `severity`        TINYINT      NOT NULL DEFAULT 0 COMMENT '严重度: 0-低, 1-中, 2-高(展示紧急就医提示)',
    `summary_status`  TINYINT      NOT NULL DEFAULT 0 COMMENT '小结状态: 0-未生成, 1-生成中, 2-已生成, 3-生成失败',
    `ai_summary`      TEXT         DEFAULT NULL COMMENT 'AI结构化小结(JSON)',
    `conclusion`      TEXT         DEFAULT NULL COMMENT '最终结论(完结时由小结落成)',
    `last_active_at`  DATETIME     NOT NULL COMMENT '最后活动时间，超时判定用',
    `closed_at`       DATETIME     DEFAULT NULL COMMENT '关闭时间',
    `version`         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session` (`session_id`),
    KEY `idx_patient_status` (`patient_id`, `status`),
    KEY `idx_status_active` (`status`, `last_active_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='问诊单表';

-- ============================================================
-- 操作日志表
-- ============================================================
CREATE TABLE `sys_operation_log` (
    `id`          INT          NOT NULL AUTO_INCREMENT COMMENT '日志ID',
    `user_id`     INT          DEFAULT NULL COMMENT '操作人ID',
    `username`    VARCHAR(100) DEFAULT NULL COMMENT '操作人账号',
    `module`      VARCHAR(50)  DEFAULT NULL COMMENT '操作模块',
    `action`      VARCHAR(50)  DEFAULT NULL COMMENT '操作动作',
    `target`      VARCHAR(200) DEFAULT NULL COMMENT '操作对象',
    `ip_address`  VARCHAR(50)  DEFAULT NULL COMMENT 'IP地址',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作日志表';

-- ============================================================
