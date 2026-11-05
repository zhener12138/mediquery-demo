-- ============================================================
-- MediQuery 测试用 schema（H2 · MODE=MySQL）
--
-- 为什么需要这个文件：
--   src/main/resources/db/init.sql 是给 MySQL 8 用的，里面用了
--   ENGINE=InnoDB / CHARSET / COLLATE / 表级 COMMENT / FULLTEXT … WITH PARSER ngram
--   这些 H2 都不认，直接在测试里执行会失败。
--
-- 维护约定：
--   本文件必须与 init.sql 的表名、字段名保持一致。
--   SchemaDriftTest 会在每次 mvn test 时比对两份文件的 CREATE TABLE 表名集合，
--   漏改会直接让测试变红，不靠人记。
--
-- 刻意省略的东西（测试不依赖，且 H2 无对应能力）：
--   · FULLTEXT KEY … WITH PARSER ngram —— 全文索引是 MySQL 专有能力
--   · ON UPDATE CURRENT_TIMESTAMP —— updateTime 由 MyMetaObjectHandler 写入
--   · ENGINE / CHARSET / COLLATE / COMMENT
-- 顺带也就说明了：跑在 H2 上的测试无法验证全文检索与 MySQL 方言，
-- 真实 MySQL 的验证在 CI 的 mysql-schema 作业里（只校验 init.sql 本身能建库）。
-- ============================================================

DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
    `id`          INT          NOT NULL AUTO_INCREMENT,
    `username`    VARCHAR(50)  NOT NULL,
    `password`    VARCHAR(200) NOT NULL,
    `real_name`   VARCHAR(50)  DEFAULT NULL,
    `age`         INT          DEFAULT NULL,
    `sex`         VARCHAR(5)   DEFAULT NULL,
    `email`       VARCHAR(100) DEFAULT NULL,
    `phone`       VARCHAR(20)  DEFAULT NULL,
    `avatar_url`  VARCHAR(500) DEFAULT NULL,
    `role_status` TINYINT      NOT NULL DEFAULT 0,
    `status`      TINYINT      NOT NULL DEFAULT 1,
    `deleted`     TINYINT      NOT NULL DEFAULT 0,
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_username` UNIQUE (`username`),
    CONSTRAINT `uk_email` UNIQUE (`email`),
    CONSTRAINT `uk_phone` UNIQUE (`phone`)
);

DROP TABLE IF EXISTS `illness_category`;
CREATE TABLE `illness_category` (
    `id`          INT          NOT NULL AUTO_INCREMENT,
    `name`        VARCHAR(100) NOT NULL,
    `info`        TEXT         DEFAULT NULL,
    `sort_order`  INT          DEFAULT 0,
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

DROP TABLE IF EXISTS `illness`;
CREATE TABLE `illness` (
    `id`              INT          NOT NULL AUTO_INCREMENT,
    `category_id`     INT          NOT NULL,
    `name`            VARCHAR(200) NOT NULL,
    `cause`           TEXT         DEFAULT NULL,
    `symptom`         TEXT         DEFAULT NULL,
    `special_symptom` TEXT         DEFAULT NULL,
    `view_count`      INT          DEFAULT 0,
    `status`          TINYINT      NOT NULL DEFAULT 1,
    `deleted`         TINYINT      NOT NULL DEFAULT 0,
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);
CREATE INDEX `idx_category_id` ON `illness` (`category_id`);

DROP TABLE IF EXISTS `medicine`;
CREATE TABLE `medicine` (
    `id`               INT           NOT NULL AUTO_INCREMENT,
    `name`             VARCHAR(200)  NOT NULL,
    `keyword`          VARCHAR(500)  DEFAULT NULL,
    `effect`           TEXT          DEFAULT NULL,
    `brand`            VARCHAR(200)  DEFAULT NULL,
    `manufacturer`     VARCHAR(200)  DEFAULT NULL,
    `interaction`      TEXT          DEFAULT NULL,
    `contraindication` TEXT          DEFAULT NULL,
    `usage_dosage`     TEXT          DEFAULT NULL,
    `type`             TINYINT       DEFAULT 0,
    `price`            DECIMAL(10,2) DEFAULT NULL,
    `image_url`        VARCHAR(500)  DEFAULT NULL,
    `status`           TINYINT       NOT NULL DEFAULT 1,
    `deleted`          TINYINT       NOT NULL DEFAULT 0,
    `create_time`      DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME      DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

DROP TABLE IF EXISTS `illness_medicine_rel`;
CREATE TABLE `illness_medicine_rel` (
    `id`          INT NOT NULL AUTO_INCREMENT,
    `illness_id`  INT NOT NULL,
    `medicine_id` INT NOT NULL,
    PRIMARY KEY (`id`)
);
CREATE INDEX `idx_illness_id` ON `illness_medicine_rel` (`illness_id`);
CREATE INDEX `idx_medicine_id` ON `illness_medicine_rel` (`medicine_id`);

DROP TABLE IF EXISTS `search_history`;
CREATE TABLE `search_history` (
    `id`           INT          NOT NULL AUTO_INCREMENT,
    `user_id`      INT          NOT NULL,
    `keyword`      VARCHAR(500) NOT NULL,
    `operate_type` TINYINT      NOT NULL,
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);
CREATE INDEX `idx_search_history_user_id` ON `search_history` (`user_id`);
CREATE INDEX `idx_search_history_create_time` ON `search_history` (`create_time`);

DROP TABLE IF EXISTS `feedback`;
CREATE TABLE `feedback` (
    `id`          INT          NOT NULL AUTO_INCREMENT,
    `name`        VARCHAR(100) NOT NULL,
    `email`       VARCHAR(100) DEFAULT NULL,
    `title`       VARCHAR(200) NOT NULL,
    `content`     TEXT         NOT NULL,
    `status`      TINYINT      NOT NULL DEFAULT 0,
    `admin_reply` TEXT         DEFAULT NULL,
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

DROP TABLE IF EXISTS `sys_doctor`;
CREATE TABLE `sys_doctor` (
    `id`          INT          NOT NULL AUTO_INCREMENT,
    `name`        VARCHAR(100) NOT NULL,
    `title`       VARCHAR(100) DEFAULT NULL,
    `department`  VARCHAR(100) DEFAULT NULL,
    `hospital`    VARCHAR(200) DEFAULT NULL,
    `avatar_url`  VARCHAR(500) DEFAULT NULL,
    `specialty`   TEXT         DEFAULT NULL,
    `description` TEXT         DEFAULT NULL,
    `phone`       VARCHAR(20)  DEFAULT NULL,
    `status`      TINYINT      NOT NULL DEFAULT 1,
    `sort_order`  INT          DEFAULT 0,
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);

DROP TABLE IF EXISTS `ai_chat_record`;
CREATE TABLE `ai_chat_record` (
    `id`          INT         NOT NULL AUTO_INCREMENT,
    `user_id`     INT         NOT NULL,
    `session_id`  VARCHAR(36) NOT NULL,
    `role`        VARCHAR(10) NOT NULL,
    `content`     TEXT        NOT NULL,
    `create_time` DATETIME    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);
CREATE INDEX `idx_user_session` ON `ai_chat_record` (`user_id`, `session_id`);

DROP TABLE IF EXISTS `user_favorite`;
CREATE TABLE `user_favorite` (
    `id`          INT         NOT NULL AUTO_INCREMENT,
    `user_id`     INT         NOT NULL,
    `target_type` VARCHAR(20) NOT NULL,
    `target_id`   INT         NOT NULL,
    `create_time` DATETIME    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_user_target` UNIQUE (`user_id`, `target_type`, `target_id`)
);

DROP TABLE IF EXISTS `sys_operation_log`;
CREATE TABLE `sys_operation_log` (
    `id`          INT          NOT NULL AUTO_INCREMENT,
    `user_id`     INT          DEFAULT NULL,
    `username`    VARCHAR(100) DEFAULT NULL,
    `module`      VARCHAR(50)  DEFAULT NULL,
    `action`      VARCHAR(50)  DEFAULT NULL,
    `target`      VARCHAR(200) DEFAULT NULL,
    `ip_address`  VARCHAR(50)  DEFAULT NULL,
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
);
CREATE INDEX `idx_operation_log_user_id` ON `sys_operation_log` (`user_id`);
CREATE INDEX `idx_operation_log_create_time` ON `sys_operation_log` (`create_time`);
