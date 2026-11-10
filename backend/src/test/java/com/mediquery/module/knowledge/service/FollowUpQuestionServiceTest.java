package com.mediquery.module.knowledge.service;

import com.mediquery.module.knowledge.dto.DiseaseSymptomView;
import com.mediquery.module.knowledge.dto.NormalizedSymptom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 追问选择的纯单测。
 *
 * <p>这块最容易写错的地方是「问什么」。直觉会让人问「这个病还有什么症状」，
 * 结果问出来的是每个候选都有的「发热」—— 答什么都收窄不了。
 * 下面几条用例都在钉同一件事：<b>优先问能把候选分开的那个</b>。
 */
class FollowUpQuestionServiceTest {

    private final FollowUpQuestionService service = new FollowUpQuestionService();

    @Test
    @DisplayName("问能对半分候选的症状，而不是每个候选都有的那个")
    void asksTheSplittingSymptom() {
        // 三个候选都发热 → 问发热毫无意义
        var candidates = List.of(
                row("扁桃体炎", "发热", true), row("扁桃体炎", "咽痛", true),
                row("扁桃体炎", "吞咽困难", true),
                row("病毒性感冒", "发热", true), row("病毒性感冒", "鼻塞", true),
                row("病毒性感冒", "全身酸痛", false),
                row("风寒感冒", "发热", true), row("风寒感冒", "恶寒", true));

        var questions = service.pickQuestions(candidates, List.of());

        assertThat(questions).isNotEmpty();
        assertThat(questions).extracting(FollowUpQuestionService.Question::symptomName)
                .doesNotContain("发热");

        // 「咽痛」「吞咽困难」「鼻塞」「全身酸痛」「恶寒」对这三个候选的分布
        // 一模一样（都只有一个候选有），信息量相同，选哪个都在理。所以这里
        // 只钉「问出来的每一条都真能把候选分开」，不锁死具体名字 ——
        // 锁死了的话，下次调整权重或 SQL 排序，测试会红，但红得没有信息量。
        assertThat(questions).allSatisfy(q -> {
            assertThat(q.hitDiseases()).isNotEmpty();
            assertThat(q.missDiseases()).isNotEmpty();
        });
        // 两个问题不能指向同一批候选，否则等于让人重复回答
        assertThat(questions).extracting(FollowUpQuestionService.Question::hitDiseases)
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("用户已经说过的症状不再问")
    void skipsAlreadyMentionedSymptoms() {
        var candidates = List.of(
                row("扁桃体炎", "发热", true), row("扁桃体炎", "吞咽困难", true),
                row("病毒性感冒", "发热", true), row("病毒性感冒", "鼻塞", true));
        var known = List.of(normalized("吞咽困难"));

        var questions = service.pickQuestions(candidates, known);

        assertThat(questions).extracting(FollowUpQuestionService.Question::symptomName)
                .doesNotContain("吞咽困难");
    }

    @Test
    @DisplayName("只有一个候选时不追问 —— 没有可鉴别的")
    void noQuestionForSingleCandidate() {
        var candidates = List.of(
                row("便秘", "排便费力", true), row("便秘", "粪便干硬", true));

        assertThat(service.pickQuestions(candidates, List.of())).isEmpty();
    }

    @Test
    @DisplayName("最多问两个，问多了用户不答")
    void atMostTwoQuestions() {
        // 四种候选，各自有大量独有症状
        var candidates = Stream.of("甲", "乙", "丙", "丁")
                .flatMap(name -> Stream.of(
                        row(name, "发热", true),
                        row(name, name + "特有症状", true),
                        row(name, name + "另一个症状", true)))
                .toList();

        assertThat(service.pickQuestions(candidates, List.of()))
                .hasSize(FollowUpQuestionService.MAX_QUESTIONS);
    }

    @Test
    @DisplayName("问题的提示写清了答有和答没有分别意味着什么")
    void hintExplainsBothAnswers() {
        var candidates = List.of(
                row("扁桃体炎", "吞咽困难", true),
                row("病毒性感冒", "鼻塞", true));

        var question = service.pickQuestions(candidates, List.of()).get(0);

        // 提示里必须同时出现两边，模型才知道这个问题的价值在哪
        assertThat(question.hint()).contains("扁桃体炎").contains("病毒性感冒");
        assertThat(question.hitDiseases()).isNotEmpty();
        assertThat(question.missDiseases()).isNotEmpty();
    }

    @Test
    @DisplayName("全是边缘症状时仍然给问题，但排在核心症状后面")
    void cardinalBeatsPeripheral() {
        var candidates = List.of(
                row("甲", "发热", false), row("甲", "关键症状", true),
                row("乙", "发热", false), row("乙", "次要症状", false));

        var questions = service.pickQuestions(candidates, List.of());

        // 「关键症状」是核心，「次要症状」是边缘，同分布下核心应该排前面
        assertThat(questions.get(0).symptomName()).isEqualTo("关键症状");
    }

    @Test
    @DisplayName("命中同一批候选的两个症状只问一个 —— 问两个是让人重复回答")
    void symptomsWithSameHitSetAreDeduplicated() {
        // 偏头痛既有「搏动性头痛」也有「单侧头痛」，另外两个病都没有。
        // 这两个问题答完得到的结论完全一样，不该都问。
        var candidates = List.of(
                row("偏头痛", "搏动性头痛", true), row("偏头痛", "单侧头痛", true),
                row("病毒性感冒", "发热", true),
                row("风寒感冒", "恶寒", true));

        var questions = service.pickQuestions(candidates, List.of());

        var headacheQuestions = questions.stream()
                .map(FollowUpQuestionService.Question::symptomName)
                .filter(name -> name.endsWith("头痛"))
                .toList();
        assertThat(headacheQuestions).hasSize(1);
    }

    @Test
    @DisplayName("空输入不抛异常")
    void emptyInputIsSafe() {
        assertThat(service.pickQuestions(null, null)).isEmpty();
        assertThat(service.pickQuestions(List.of(), List.of())).isEmpty();
        assertThat(service.pickQuestions(List.of(row("甲", "发热", true)), List.of())).isEmpty();
    }

    private static DiseaseSymptomView row(String illness, String symptom, boolean cardinal) {
        var view = new DiseaseSymptomView();
        view.setIllnessName(illness);
        view.setSymptomName(symptom);
        view.setIsCardinal(cardinal);
        view.setWeight(new BigDecimal("0.9"));
        return view;
    }

    private static NormalizedSymptom normalized(String name) {
        var s = new NormalizedSymptom();
        s.setSymptomName(name);
        return s;
    }
}
