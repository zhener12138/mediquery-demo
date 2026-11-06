package com.mediquery.integration.ai;

import com.mediquery.module.illness.entity.Illness;
import com.mediquery.module.illness.service.IllnessService;
import com.mediquery.module.medicine.entity.Medicine;
import com.mediquery.module.medicine.service.MedicineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link KnowledgeRetrievalService} 的重排与截断逻辑。
 *
 * <p>为什么这个测试值得写：全文检索改造之后，「召回」交给了数据库，
 * 「排序」仍留在内存里（名称命中 ×3、正文 ×1、词长加权）。
 * 如果只测召回不测排序，那么把 {@code scoreAgainst} 的权重改坏、
 * 或者把 {@code TOP_K} 截断去掉，测试都不会红 —— 而用户会直接看到
 * 一连串不相关的疾病排在第一位。
 *
 * <p>用 {@link LikeRetrieval} 接真 Service 太重，这里直接用 mock 的
 * {@link RetrievalStrategy}，把「候选集」当成输入注入，只验证内存这一段的语义。
 */
class KnowledgeRetrievalServiceTest {

    private RetrievalStrategy strategy;
    private KnowledgeRetrievalService service;

    @BeforeEach
    void setUp() {
        strategy = mock(RetrievalStrategy.class);
        service = new KnowledgeRetrievalService(strategy);
    }

    private static Illness illness(int id, String name, String cause, String symptom) {
        var i = new Illness();
        i.setId(id);
        i.setName(name);
        i.setCause(cause);
        i.setSymptom(symptom);
        i.setStatus(1);
        return i;
    }

    private static Medicine medicine(int id, String name, String keyword, String effect) {
        var m = new Medicine();
        m.setId(id);
        m.setName(name);
        m.setKeyword(keyword);
        m.setEffect(effect);
        m.setStatus(1);
        return m;
    }

    @Test
    @DisplayName("名称命中权重高于正文命中：即使正文里出现多次也排不到前面")
    void nameHitOutranksBodyHit() {
        // id=2 只在 symptom 里含「咳嗽」；id=1 在 name 里含「咳嗽」
        when(strategy.recallIllnesses(anyList(), anyInt())).thenReturn(List.of(
                illness(2, "扁桃体炎", "细菌感染", "咳嗽、咽痛"),
                illness(1, "咳嗽变异性哮喘", "气道高反应", "干咳")
        ));

        var context = service.retrieve("咳嗽怎么办");

        // 名字里带「咳嗽」的必须排在前面
        assertThat(context.indexOf("咳嗽变异性哮喘"))
                .isLessThan(context.indexOf("扁桃体炎"));
    }

    @Test
    @DisplayName("Top3 截断：召回 5 条只注入 3 条")
    void topThreeIsEnforced() {
        when(strategy.recallIllnesses(anyList(), anyInt())).thenReturn(List.of(
                illness(1, "感冒甲", "病毒感染", "发热"),
                illness(2, "感冒乙", "病毒感染", "发热"),
                illness(3, "感冒丙", "病毒感染", "发热"),
                illness(4, "感冒丁", "病毒感染", "发热"),
                illness(5, "感冒戊", "病毒感染", "发热")
        ));

        var context = service.retrieve("感冒");

        // 五条都含「感冒」，截断后必须只剩三条
        var hits = context.split("## 疾病：", -1).length - 1;
        assertThat(hits).isEqualTo(3);
    }

    @Test
    @DisplayName("零命中时不调用格式化，返回空上下文")
    void emptyCandidatesYieldEmptyContext() {
        when(strategy.recallIllnesses(anyList(), anyInt())).thenReturn(List.of());
        when(strategy.recallMedicines(anyList(), anyInt())).thenReturn(List.of());

        assertThat(service.retrieve("感冒")).isEmpty();
    }

    @Test
    @DisplayName("全是停用词的问题不会去查数据库 —— 省掉一次白发查询")
    void onlyStopWordsSkipsRecall() {
        // 「怎么办」「什么」「请问」都在 STOP_WORDS 里
        service.retrieve("怎么办");

        verifyNoInteractions(strategy);
    }

    @Test
    @DisplayName("纯数字串被过滤，不会当成关键词去 LIKE 数字")
    void digitsOnlyTokensAreDropped() {
        // 「12345」被 isAllDigits 过滤；只剩「感冒」
        service.retrieve("12345 感冒");

        verify(strategy).recallIllnesses(anyList(), anyInt());
    }

    @Test
    @DisplayName("药品与疾病分别召回、分别截断")
    void illnessesAndMedicinesAreRecalledSeparately() {
        when(strategy.recallIllnesses(anyList(), anyInt())).thenReturn(List.of(
                illness(1, "感冒", "病毒感染", "发热")));
        when(strategy.recallMedicines(anyList(), anyInt())).thenReturn(List.of(
                medicine(1, "感冒灵", "感冒 发热", "解热镇痛")));

        var context = service.retrieve("感冒");

        assertThat(context).contains("## 疾病：感冒");
        assertThat(context).contains("## 药品：感冒灵");
    }

    @Test
    @DisplayName("格式化输出包含给 LLM 的关键字段，缺字段不能让模型瞎猜")
    void formattedOutputCarriesKeyFields() {
        when(strategy.recallIllnesses(anyList(), anyInt())).thenReturn(List.of(
                illness(1, "病毒性感冒", "病毒感染引起", "发热、咳嗽")));
        when(strategy.recallMedicines(anyList(), anyInt())).thenReturn(List.of());

        var context = service.retrieve("感冒");

        assertThat(context).contains("病因");
        assertThat(context).contains("症状");
        assertThat(context).contains("病毒感染引起");
    }

    @Test
    @DisplayName("检索策略来自注入的实现 —— 换实现不用改这里")
    void strategyIsInjectedNotHardcoded() {
        when(strategy.recallIllnesses(anyList(), anyInt())).thenReturn(List.of());
        when(strategy.recallMedicines(anyList(), anyInt())).thenReturn(List.of());

        service.retrieve("感冒");

        verify(strategy).recallIllnesses(anyList(), anyInt());
        verify(strategy).recallMedicines(anyList(), anyInt());
    }

    @Test
    @DisplayName("召回上限传给策略，不是无限拉")
    void recallLimitIsPassedDown() {
        when(strategy.recallIllnesses(anyList(), anyInt())).thenReturn(List.of());
        when(strategy.recallMedicines(anyList(), anyInt())).thenReturn(List.of());

        service.retrieve("感冒");

        // RECALL_LIMIT = 50
        verify(strategy).recallIllnesses(anyList(), org.mockito.ArgumentMatchers.eq(50));
    }
}
