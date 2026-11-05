-- ============================================================
-- MediQuery (医问通) - 智慧医药查询系统
-- 数据库初始化脚本
-- ============================================================

CREATE DATABASE IF NOT EXISTS mediquery
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE mediquery;

-- ============================================================
-- 系统用户表
-- ============================================================
DROP TABLE IF EXISTS `sys_user`;
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
DROP TABLE IF EXISTS `illness_category`;
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
DROP TABLE IF EXISTS `illness`;
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
DROP TABLE IF EXISTS `medicine`;
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
DROP TABLE IF EXISTS `illness_medicine_rel`;
CREATE TABLE `illness_medicine_rel` (
    `id`          INT NOT NULL AUTO_INCREMENT COMMENT '关联ID',
    `illness_id`  INT NOT NULL COMMENT '疾病ID',
    `medicine_id` INT NOT NULL COMMENT '药品ID',
    PRIMARY KEY (`id`),
    KEY `idx_illness_id` (`illness_id`),
    KEY `idx_medicine_id` (`medicine_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='疾病药品关联表';

-- 说明：原 search_history（搜索历史）与 user_favorite（用户收藏）两张表已删除。
-- 它们从建库起就没有对应的实体类、Mapper、Service 和接口，属于纯死表，
-- 留着只会让接手的人以为「有这功能，只是没做完」。真要做历史/收藏时按新需求重建。

-- ============================================================
-- 意见反馈表
-- ============================================================
DROP TABLE IF EXISTS `feedback`;
CREATE TABLE `feedback` (
    `id`          INT          NOT NULL AUTO_INCREMENT COMMENT '反馈ID',
    `name`        VARCHAR(100) NOT NULL COMMENT '反馈人姓名',
    `email`       VARCHAR(100) DEFAULT NULL COMMENT '反馈人邮箱',
    `title`       VARCHAR(200) NOT NULL COMMENT '反馈标题',
    `content`     TEXT         NOT NULL COMMENT '反馈内容',
    `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态: 0-待处理, 1-已处理',
    `admin_reply` TEXT         DEFAULT NULL COMMENT '管理员回复',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='意见反馈表';

-- ============================================================
-- 医生信息表 (版本A)
-- ============================================================
DROP TABLE IF EXISTS `sys_doctor`;
CREATE TABLE `sys_doctor` (
    `id`          INT          NOT NULL AUTO_INCREMENT COMMENT '医生ID',
    `name`        VARCHAR(100) NOT NULL COMMENT '医生姓名',
    `title`       VARCHAR(100) DEFAULT NULL COMMENT '职称',
    `department`  VARCHAR(100) DEFAULT NULL COMMENT '科室',
    `hospital`    VARCHAR(200) DEFAULT NULL COMMENT '所属医院',
    `avatar_url`  VARCHAR(500) DEFAULT NULL COMMENT '头像URL',
    `specialty`   TEXT         DEFAULT NULL COMMENT '擅长领域',
    `description` TEXT         DEFAULT NULL COMMENT '简介',
    `phone`       VARCHAR(20)  DEFAULT NULL COMMENT '联系电话',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 0-停诊, 1-在岗',
    `sort_order`  INT          DEFAULT 0 COMMENT '排序',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='医生信息表';

-- ============================================================
-- AI对话记录表
-- ============================================================
DROP TABLE IF EXISTS `ai_chat_record`;
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
-- 操作日志表
-- ============================================================
DROP TABLE IF EXISTS `sys_operation_log`;
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
-- 种子数据
-- ============================================================

-- 管理员账户: admin / admin123
INSERT INTO `sys_user` (`username`, `password`, `real_name`, `age`, `sex`, `email`, `role_status`, `status`)
VALUES ('admin', '$2a$10$MkRACE8FE0LNh6MSJR.gku2LTwa.oZCpGdDujBJplgtfCpZtsX10C', '系统管理员', 30, '男', 'admin@mediquery.com', 1, 1);

-- 测试用户: test / test123
INSERT INTO `sys_user` (`username`, `password`, `real_name`, `age`, `sex`, `email`, `role_status`, `status`)
VALUES ('test', '$2a$10$KeHckO8n.2RLYnSIsgm89ey/IlGKxsY1nS9gatF17ZwqOyHJICN46', '测试用户', 25, '女', 'test@mediquery.com', 0, 1);

-- 疾病分类
INSERT INTO `illness_category` (`id`, `name`, `info`, `sort_order`) VALUES
(1, '急诊科', '处理各种急危重症的科室', 1),
(2, '内科', '诊治内部器官疾病的科室', 2),
(3, '外科', '通过手术方式治疗疾病的科室', 3),
(4, '妇产科', '诊治女性生殖系统疾病及妊娠分娩的科室', 4),
(5, '儿科', '诊治儿童疾病的科室', 5),
(6, '皮肤科', '诊治皮肤疾病的科室', 6),
(7, '肝病科', '诊治肝脏相关疾病的科室', 7),
(8, '传染科', '诊治传染性疾病的科室', 8),
(9, '耳鼻喉科', '诊治耳鼻喉部位疾病的科室', 9),
(10, '口腔科', '诊治口腔部位疾病的科室', 10);

-- 疾病数据
INSERT INTO `illness` (`id`, `category_id`, `name`, `cause`, `symptom`, `special_symptom`, `view_count`, `status`) VALUES
(1, 2, '病毒性感冒', '由鼻病毒、冠状病毒、流感病毒等引起的上呼吸道感染，主要通过飞沫传播或接触传播。', '鼻塞、流涕、打喷嚏、咽喉疼痛、咳嗽、发热、头痛、全身酸痛乏力。', '病毒性感冒通常具有自限性，一般7-10天可自愈。常见于季节交替、气温变化大时，儿童和老年人发病率较高。', 5, 1),
(2, 2, '风寒感冒', '因风吹受凉而引起的感冒，多发生于秋冬季节。主要是人体抵抗力下降时，寒邪入侵所致。', '恶寒重、发热轻、无汗、头痛身痛、鼻塞流清涕、咳嗽吐稀白痰、口不渴或渴喜热饮。', '风寒感冒与风热感冒需区别对待，风寒感冒宜辛温解表，不宜使用寒凉药物。', 4, 1),
(3, 9, '扁桃体炎', '由溶血性链球菌、葡萄球菌、肺炎球菌等细菌或病毒引起的扁桃体急性炎症。', '咽痛明显、吞咽困难、发热、头痛、全身不适、扁桃体充血肿大、可有脓点。', '反复发作的扁桃体炎可能导致风湿热、肾炎等并发症，必要时需考虑手术切除。', 3, 1),
(4, 2, '偏头痛', '与遗传、环境、神经递质异常等因素相关的原发性头痛。具体机制尚未完全明确。', '反复发作的单侧搏动性头痛、恶心、呕吐、畏光、畏声。发作可持续4-72小时。', '常见的诱因包括：压力、某些食物（巧克力、红酒等）、睡眠不足、天气变化等。', 4, 1),
(5, 2, '便秘', '与饮食习惯、排便习惯、精神因素、肠道功能紊乱等多种因素相关。', '排便次数减少、粪便干硬、排便困难费力、排便不尽感。', '长期便秘可能诱发痔疮、肛裂等疾病。增加膳食纤维摄入和适量运动有助于改善。', 2, 1),
(6, 3, '骨折', '由直接暴力或间接暴力作用于骨骼，超过骨骼承受能力引起。', '局部疼痛、肿胀、功能障碍、畸形、异常活动、骨擦音或骨擦感。', '骨折后应立即固定患处，避免移动造成二次损伤，尽快前往医院就诊。', 3, 1),
(7, 10, '牙周炎', '由牙菌斑中的微生物引起的牙周支持组织（牙龈、牙周膜、牙槽骨）的慢性感染性疾病。', '牙龈红肿出血、牙周袋形成、牙槽骨吸收、牙齿松动移位、口臭。', '牙周炎是导致成年人牙齿丧失的主要原因，定期洁牙和良好的口腔卫生习惯非常重要。', 3, 1),
(8, 2, '胃溃疡', '由幽门螺杆菌感染、长期使用非甾体抗炎药、胃酸分泌过多、精神压力等因素引起。', '上腹部规律性疼痛（多在饭后1-2小时）、反酸、嗳气、恶心、食欲减退。', '胃溃疡若不及时治疗，可能导致胃出血、胃穿孔等严重并发症。', 4, 1),
(9, 10, '口腔溃疡', '与免疫功能紊乱、遗传因素、维生素缺乏、精神压力、局部创伤等多种因素相关。', '口腔黏膜出现圆形或椭圆形浅表溃疡，表面覆盖黄白色假膜，周围红晕，疼痛明显。', '多数口腔溃疡7-14天可自愈。反复发作或久不愈合的溃疡需警惕口腔癌的可能。', 2, 1),
(10, 6, '湿疹', '由内因（遗传、免疫功能异常）和外因（过敏原、刺激物、环境因素）共同作用引起。', '皮肤红斑、丘疹、水疱、糜烂、渗液、结痂、脱屑，剧烈瘙痒，反复发作。', '湿疹患者应注意皮肤保湿，避免搔抓和热水烫洗，避免接触已知过敏原。', 3, 1);

-- 药品数据
INSERT INTO `medicine` (`id`, `name`, `keyword`, `effect`, `brand`, `manufacturer`, `interaction`, `contraindication`, `usage_dosage`, `type`, `price`, `status`) VALUES
(1, '阿莫西林', '抗生素 消炎', '用于敏感菌引起的各种感染,包括呼吸道感染、泌尿道感染、皮肤软组织感染等。', '阿莫仙', '华北制药', '与丙磺舒合用可延缓阿莫西林排泄,与甲氨蝶呤合用可增加甲氨蝶呤毒性。', '对青霉素类过敏者禁用,使用前需做青霉素皮试。孕妇及哺乳期妇女慎用。', '成人一次0.5g,每6-8小时一次;儿童按体重每日20-40mg/kg,分3次服用。', 0, 18.50, 1),
(2, '999感冒灵颗粒', '感冒 流感 退烧', '解热镇痛,用于感冒引起的头痛、发热、鼻塞、流涕、咽痛等症状。', '三九', '华润三九医药', '不宜与其它解热镇痛药同时服用。与抗凝药合用可能增加出血风险。', '严重肝肾功能不全者禁用。对本品成分过敏者禁用。服用期间不得饮酒。', '开水冲服,一次1袋,一日3次。', 2, 15.80, 1),
(3, '开塞露', '便秘 通便', '用于便秘,润滑并刺激肠壁,软化大便,使其易于排出。', '开塞露', '广州白云山', '暂无明确的药物相互作用。', '对本品过敏者禁用。腹痛、恶心、呕吐者不宜使用。不可长期使用。', '将容器顶端刺破,涂少许油脂润滑管口,缓慢插入肛门,将药液挤入直肠内。成人一次20ml。', 0, 3.50, 1),
(4, '三九胃泰颗粒', '胃病 胃炎 胃溃疡', '清热燥湿、行气活血、柔肝止痛,用于上腹隐痛、饱胀反酸、恶心呕吐、纳减等。', '三九', '华润三九医药', '不宜与滋补性中药同服。与抗酸药合用需间隔1小时以上。', '孕妇禁用。对本品过敏者禁用。服药期间忌食辛辣刺激性食物。', '开水冲服,一次1袋,一日2次。', 2, 22.00, 1),
(5, '999皮炎平', '皮炎 湿疹 止痒 皮肤', '用于局限性瘙痒症、神经性皮炎、接触性皮炎、脂溢性皮炎以及慢性湿疹。', '三九', '华润三九医药', '与其他糖皮质激素类药物合用可能增加皮肤副作用。', '对本品成分过敏者禁用。皮肤破损处禁用。不宜大面积长期使用。面部、腋下等薄嫩部位慎用。', '外用,涂于患处,一日2-3次。', 0, 12.00, 1),
(6, '甲硝唑片', '牙痛 牙龈炎 消炎', '用于治疗肠道和肠外阿米巴病、阴道滴虫病、厌氧菌感染等,口腔感染如牙周炎。', '甲硝唑', '华北制药', '与酒精合用可引起双硫仑样反应。与华法林合用可增强抗凝作用。', '对硝基咪唑类药物过敏者禁用。孕妇及哺乳期妇女禁用。有活动性中枢神经系统疾病者禁用。', '成人一次0.2-0.4g,一日3次。治疗期间及停药后3天内禁止饮酒。', 0, 8.90, 1),
(7, '布洛芬', '止痛 退烧 牙痛 头痛', '非甾体抗炎药,用于缓解轻至中度疼痛如头痛、关节痛、偏头痛、牙痛、肌肉痛、痛经等,也用于退热。', '芬必得', '中美天津史克', '不宜与其他非甾体抗炎药合用。可能降低利尿剂的降压效果。与抗凝血药合用增加出血风险。', '对阿司匹林或其他非甾体抗炎药过敏者禁用。胃溃疡患者禁用。严重肝肾功能不全者禁用。', '成人一次0.2-0.4g,每4-6小时一次,一日最大量不超过2.4g。饭后服用可减少胃肠道反应。', 0, 22.50, 1);

-- 疾病-药品关联
INSERT INTO `illness_medicine_rel` (`illness_id`, `medicine_id`) VALUES
(1, 1), (1, 2), (1, 7),   -- 病毒性感冒 -> 阿莫西林, 999感冒灵, 布洛芬
(2, 2), (2, 7),             -- 风寒感冒 -> 999感冒灵, 布洛芬
(3, 1), (3, 7),             -- 扁桃体炎 -> 阿莫西林, 布洛芬
(4, 7),                     -- 偏头痛 -> 布洛芬
(5, 3),                     -- 便秘 -> 开塞露
(7, 6),                     -- 牙周炎 -> 甲硝唑
(8, 4),                     -- 胃溃疡 -> 三九胃泰
(10, 5);                    -- 湿疹 -> 999皮炎平

-- 医生数据
INSERT INTO `sys_doctor` (`id`, `name`, `title`, `department`, `hospital`, `specialty`, `description`, `status`, `sort_order`) VALUES
(1, '张明华', '主任医师', '内科', '北京协和医院', '心血管疾病、高血压、冠心病、心功能不全的诊治，对疑难危重病例有丰富经验。', '从事内科临床工作30余年，在心血管疾病的预防、诊断和治疗方面积累了丰富经验。', 1, 1),
(2, '李丽萍', '副主任医师', '儿科', '北京儿童医院', '儿童呼吸系统疾病、消化系统疾病、儿童生长发育评估与指导。', '毕业于北京大学医学部，从事儿科临床工作15年，对儿童常见病有独到见解。', 1, 2),
(3, '王建国', '主任医师', '皮肤科', '上海华山医院', '湿疹、银屑病、痤疮、荨麻疹等常见皮肤病的诊治，皮肤激光美容。', '在皮肤科领域工作20余年，对难治性皮肤病有深入研究，发表SCI论文30余篇。', 1, 3),
(4, '赵雪琴', '主治医师', '口腔科', '四川大学华西口腔医院', '牙体牙髓病、牙周病、口腔黏膜病的诊治，前牙美学修复。', '华西口腔医学院博士毕业，擅长牙周病的系统治疗和口腔健康管理。', 1, 4),
(5, '陈志强', '主任医师', '外科', '中山大学附属第一医院', '肝、胆、胰外科疾病的手术治疗，尤其擅长腹腔镜微创手术。', '从事普外科工作25年，完成各类手术超过5000例，具有丰富的临床经验。', 1, 5),
(6, '刘芳', '副主任医师', '妇产科', '复旦大学附属妇产科医院', '妇科肿瘤、内分泌疾病、不孕不育症的诊治，高危妊娠管理。', '在妇产科领域工作18年，对妇科恶性肿瘤的早期筛查和综合治疗有丰富经验。', 1, 6),
(7, '孙伟', '主治医师', '耳鼻喉科', '首都医科大学附属北京同仁医院', '耳鼻咽喉科常见病、多发病的诊治，尤其擅长鼻窦炎、中耳炎的微创手术。', '从事耳鼻喉科临床工作10年，对过敏性鼻炎的综合治疗有深入实践。', 1, 7),
(8, '周红梅', '主任医师', '肝病科', '上海瑞金医院', '病毒性肝炎、脂肪肝、肝硬化、肝癌的诊治，肝病预防与健康管理。', '在肝病领域从业22年，参与多项国家级肝病研究项目，临床经验丰富。', 1, 8);
