-- ============================================================
-- V2 · 问诊信息卡：consultation 加 4 个可空列
--
-- 为什么是 ALTER 而不是塞进 V1：V1 已经发布过，改它等于让已部署的库
-- 和未部署的库走到不同的历史里。结构变更只能新增，不能改写历史。
--
-- 四列全可空，因为「逼用户填表」是问诊体验的第一杀手：
-- 用户直接张嘴说症状就能开始，这四列只是给模型当参考，
-- 拿不到就让模型自己在对话里问。
-- ============================================================

ALTER TABLE `consultation`
    ADD COLUMN `duration_desc`  VARCHAR(100) DEFAULT NULL COMMENT '病程描述，如三天/两周' AFTER `chief_complaint`,
    ADD COLUMN `severity_scale` TINYINT      DEFAULT NULL COMMENT '自评严重度 0-10，10 最重' AFTER `duration_desc`,
    ADD COLUMN `history_flags`  VARCHAR(500) DEFAULT NULL COMMENT '既往史/过敏史/长期用药，JSON 数组字符串' AFTER `severity_scale`,
    ADD COLUMN `onset_type`     VARCHAR(20)  DEFAULT NULL COMMENT '起病方式: sudden/gradual' AFTER `history_flags`;
