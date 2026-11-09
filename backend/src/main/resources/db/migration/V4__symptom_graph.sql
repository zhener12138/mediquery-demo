-- ============================================================
-- V4 · 把「三张业务表 + 一条全文索引」改造成症状中心的知识图谱
--
-- 这一版要解决的问题，用一句实测数据说明：
--   改造前 knowledge base 只有 10 种病，全部散落在上呼吸道/口腔/皮肤上。
--   用户说「最近总睡不着」→ 召回 0 条；「腰疼」→ 0 条；「头晕」→ 0 条。
--   RAG 一条也召不到，模型只能靠自己，前端 sources 永远是空的。
--
-- 但这不是「数据太少」，是结构不对：
--   1. illness.symptom 是一串顿号分隔的文本（"鼻塞、流涕、打喷嚏、咽喉疼痛"），
--      只能 LIKE 或全文索引，没法问「谁把失眠当核心症状」，也没法把
--      「睡不着 / 入睡困难 / 眠差」归一成同一个概念；
--   2. illness_medicine_rel 只有 (illness_id, medicine_id) 两列，
--      没有适应症、没有一线二线、没有证据等级。于是
--      阿莫西林（抗生素）被关联到病毒性感冒（病毒感染）身上 —— 这是错关联，
--      模型照着念就是在给用户错误的用药暗示；
--   3. 科室只有一个 illness.category_id，一对一。真实情况是一种病可以挂多个科，
--      且该有优先级（胸痛放射左肩，先心内科，而不是把五个科室平铺让用户自己挑）。
--
-- 参照 CMeKG（12 类实体 / 六元组）、LingShu（四元组显式编码条件）、
-- PrimeKG（一张边表容纳 29 种关系）的共同结论：
--   症状必须是独立实体，关系必须能带条件，检索要能多跳。
--
-- 本版落地的结构（新增 4 张表 + 扩 1 张表）：
--   symptom            症状主表           —— 独立实体，不是疾病表里的一个字符串
--   symptom_alias      症状同义词         —— 「睡不着」「入睡困难」→「失眠」
--   disease_symptom    疾病-症状（加权边）—— weight / 是否核心 / 是否红旗
--   disease_department 疾病-科室（加权边）—— priority 决定推荐顺序
--   illness_medicine_rel 补 3 列          —— indication / line_of_treatment / evidence_level
--
-- 命名说明：新表用 disease_ 前缀，是因为它们描述的是「疾病的属性」；
-- 老表 `illness` 保持不动（改名要迁数据、改 4 个 Mapper、改一堆 SQL，
-- 换不来任何业务收益）。两者指的是同一件事，这点在此写清，避免下一任维护者困惑。
-- ============================================================

-- ============================================================
-- 症状主表
--
-- 为什么症状要独立成表，而不是留在 illness.symptom 字符串里：
--   字符串只能做「包含」，做不了「同一件事的不同说法」。
--   「睡不着」「失眠」「入睡困难」「眠差」是四个写法、一个概念，
--   存成文本就只能靠 LIKE 逐个碰运气，而且「睡不着」永远碰不到
--   只写了「失眠」的疾病 —— 这正好解释了实测里失眠召回 0 条。
-- ============================================================
CREATE TABLE `symptom` (
    `id`          INT          NOT NULL AUTO_INCREMENT COMMENT '症状ID',
    `name`        VARCHAR(100) NOT NULL COMMENT '标准症状名，如 失眠/咳嗽/胸痛',
    `body_part`   VARCHAR(50)  DEFAULT NULL COMMENT '部位，如 头面部/胸部/腹部，用于粗分诊',
    `category`    VARCHAR(50)  DEFAULT NULL COMMENT '类别，如 睡眠/呼吸/消化/疼痛，用于聚合展示',
    `description` VARCHAR(500) DEFAULT NULL COMMENT '症状的通俗解释，给模型做措辞参考',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 0-停用, 1-启用',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_symptom_name` (`name`),
    KEY `idx_symptom_body_part` (`body_part`),
    KEY `idx_symptom_category` (`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='症状主表';

-- ============================================================
-- 症状同义词表
--
-- 归一化是整条检索链路的入口，也是最脏的一块活：
--   用户不会说「失眠」，他会说「睡不着」「躺下两小时都睡不着」「一晚上醒好几次」。
--   同义词表就是把这句话收敛成症状 ID 的那一层。
--
-- weight 的用法：同一个 alias 指向症状的强度。
--   「失眠」是标准名，weight=1.0；「睡不着」是口语，也接近 1.0；
--   「睡不好」更宽泛，0.7 —— 它也可能指向「睡眠质量差」而不是「失眠」。
--   归一化命中多个症状时，按 weight 排序取最高的几个，而不是一把全召回。
-- ============================================================
CREATE TABLE `symptom_alias` (
    `id`         INT          NOT NULL AUTO_INCREMENT COMMENT '别名ID',
    `symptom_id` INT          NOT NULL COMMENT '指向的症状ID',
    `alias`      VARCHAR(100) NOT NULL COMMENT '口语说法/近义词/常见错别字写法',
    `weight`     DECIMAL(3,2) NOT NULL DEFAULT 1.00 COMMENT '归一强度 0.00-1.00',
    `create_time` DATETIME    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_alias` (`alias`),
    KEY `idx_alias_symptom` (`symptom_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='症状同义词表';

-- ============================================================
-- 疾病-症状关联表（带权重的边）
--
-- 三个属性各自解决一个具体问题：
--   weight      —— 「发热」出现在几乎每种病里，区分度低；「骨擦音」只出现在骨折，
--                  区分度高。打分时必须能体现这个差别，否则感冒永远排第一。
--   is_cardinal —— 核心症状。问「你有没有 A」时优先问核心症状，
--                  这是把问诊从「一轮接一轮瞎问」变成「有方向地问」的关键。
--   is_red_flag —— 这个症状本身意味着需要立即就医（如呼吸困难、大出血）。
--                  和 TriageRuleEngine 的关键词表是两层防线：
--                  规则层不查库、绝不会因为数据库挂了而静音；
--                  这里则让图谱能自己回答「哪些病带着红旗症状」。
-- ============================================================
CREATE TABLE `disease_symptom` (
    `id`          INT          NOT NULL AUTO_INCREMENT COMMENT '关联ID',
    `illness_id`  INT          NOT NULL COMMENT '疾病ID，对应 illness.id',
    `symptom_id`  INT          NOT NULL COMMENT '症状ID，对应 symptom.id',
    `weight`      DECIMAL(3,2) NOT NULL DEFAULT 1.00 COMMENT '该症状对本病的提示权重 0.00-1.00',
    `is_cardinal` TINYINT      NOT NULL DEFAULT 0 COMMENT '是否核心症状: 0-否, 1-是',
    `is_red_flag` TINYINT      NOT NULL DEFAULT 0 COMMENT '是否红旗症状: 0-否, 1-是',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_disease_symptom` (`illness_id`, `symptom_id`),
    KEY `idx_ds_symptom` (`symptom_id`),
    KEY `idx_ds_illness` (`illness_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='疾病-症状关联表';

-- ============================================================
-- 疾病-科室关联表（带优先级）
--
-- 老结构只有 illness.category_id，一个病一个科，且是「分类」不是「就诊科室」。
-- 真实主诉经常跨科：「胸痛放射到左肩」→ 心内科优先，急诊科兜底；
-- 「反复口腔溃疡伴皮疹」→ 口腔科 + 风湿免疫科。
--
-- priority 越小越优先，1 = 首选科室。前端只渲染前两个，
-- 但库里可以留三个，方便模型判断「还有别的可能」。
-- ============================================================
CREATE TABLE `disease_department` (
    `id`          INT          NOT NULL AUTO_INCREMENT COMMENT '关联ID',
    `illness_id`  INT          NOT NULL COMMENT '疾病ID，对应 illness.id',
    `department`  VARCHAR(50)  NOT NULL COMMENT '科室名，取值与 illness_category.name 对齐',
    `priority`    INT          NOT NULL DEFAULT 1 COMMENT '优先级: 1-首选, 数字越大越次要',
    `reason`      VARCHAR(200) DEFAULT NULL COMMENT '为什么建议挂这个科，给模型做解释用',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_disease_department` (`illness_id`, `department`),
    KEY `idx_dd_illness` (`illness_id`),
    KEY `idx_dd_department` (`department`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='疾病-科室关联表';

-- ============================================================
-- 扩展疾病-药品关联：从「两个 ID」升级为「一条带条件的边」
--
-- 这是 LingShu 四元组那套论证在小型项目里的落地版本。
-- 原表的致命问题：阿莫西林关联到病毒性感冒。
-- 阿莫西林是抗生素，只对细菌感染有效，病毒性感冒用它既无效又滥用。
-- 加了 indication 之后，同一条边必须自己解释得通：
--   「仅当合并细菌感染（如化脓性扁桃体炎、细菌性中耳炎）时考虑」
-- 说不通的关联，就该在录入数据时被删掉。
--
-- line_of_treatment: 1-一线用药, 2-二线用药, 3-仅医生评估后考虑, 0-未分级
-- evidence_level:    1-指南推荐, 2-临床常用, 3-经验性参考, 0-未标注
--
-- 默认值给 0，是为了让「老数据没标注」和「标注为未分级」在语义上一致，
-- 不假装老数据有一线/二线的结论。
-- ============================================================
ALTER TABLE `illness_medicine_rel`
    ADD COLUMN `indication` VARCHAR(500) DEFAULT NULL
        COMMENT '适应症说明：什么情况下才考虑用这个药' AFTER `medicine_id`,
    ADD COLUMN `line_of_treatment` TINYINT NOT NULL DEFAULT 0
        COMMENT '治疗线: 0-未分级, 1-一线, 2-二线, 3-医生评估后考虑' AFTER `indication`,
    ADD COLUMN `evidence_level` TINYINT NOT NULL DEFAULT 0
        COMMENT '证据等级: 0-未标注, 1-指南推荐, 2-临床常用, 3-经验性参考' AFTER `line_of_treatment`;
