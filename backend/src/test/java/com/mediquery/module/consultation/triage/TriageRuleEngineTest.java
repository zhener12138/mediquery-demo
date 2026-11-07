package com.mediquery.module.consultation.triage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预筛规则的测试。
 *
 * <h3>这组测试守的是什么</h3>
 *
 * <p>规则引擎是纯函数，没有并发、没有 IO，所以它的测试不是在防「跑不起来」，
 * 而是在防**有人后来为了省事把词表改窄了**。比如有人觉得「喘不上气」太口语，
 * 只留「呼吸困难」，那这条规则在真实用户面前就失效了一半 —— 而代码依然能跑，
 * 测试如果不写这些口语变体，就永远发现不了。
 *
 * <p>所以这里刻意用口语化的输入：真正的患者不会说「我呼吸困难」，
 * 他会说「我喘不上气」。
 */
class TriageRuleEngineTest {

    private final TriageRuleEngine engine = new TriageRuleEngine();

    // ────────────────────── 高危识别 ──────────────────────

    @ParameterizedTest(name = "\"{0}\" 应被判定为高危")
    @DisplayName("高危信号的常见口语说法都能抓到")
    @ValueSource(strings = {
            "我胸口像被压住一样疼，还冒冷汗",
            "这两天喘不上气，走两步就得歇",
            "我妈刚才突然晕倒了，现在叫不醒",
            "刚才吐了一口血",
            "肚子疼得直不起腰",
            "高烧不退，已经三天了",
            "头痛欲裂，从来没这么疼过",
            "孩子抽搐了，怎么办",
            "吃了花生以后嘴唇肿起来了",
            "我真的不想活了"
    })
    void catchesHighRiskColloquialisms(String message) {
        var r = engine.triage(message);

        assertEquals(2, r.severity(), "这段话必须触发高危，否则用户会错过就医时机");
        assertTrue(r.hasRedFlag(), "必须报出具体的危险信号名称");
        assertFalse(r.advice().isBlank(), "高危必须给行动指引，不能只说『严重』");
        assertTrue(r.advice().contains("急诊") || r.advice().contains("120"),
                "行动指引必须指向具体去处");
    }

    @Test
    @DisplayName("高危信号带空格也能命中")
    void normalizesWhitespace() {
        assertEquals(2, engine.triage("胸 痛 得 厉 害").severity());
    }

    @Test
    @DisplayName("高危时科室默认兜底到急诊科")
    void highRiskDefaultsToEmergency() {
        var r = engine.triage("我突然晕倒了");
        assertTrue(r.departments().contains("急诊科"),
                "紧急情况下不该推给普通门诊，必须先到急诊");
    }

    // ────────────────────── 中度识别 ──────────────────────

    @ParameterizedTest(name = "\"{0}\" 应为中度")
    @DisplayName("常见症状判为中度但不弹急救提示")
    @ValueSource(strings = {
            "咳嗽三天了，有痰",
            "拉肚子拉了两天",
            "嗓子疼，鼻塞",
            "身上起了红疹，很痒",
            "头晕乏力，没精神"
    })
    void moderateSymptoms(String message) {
        var r = engine.triage(message);

        assertEquals(1, r.severity());
        assertFalse(r.hasRedFlag(), "中度不该被报成危险信号，否则告警会被脱敏");
        assertFalse(r.advice().contains("120"), "中度不该让用户打急救电话");
    }

    // ────────────────────── 低危与边界 ──────────────────────

    @Test
    @DisplayName("普通描述不触发任何告警")
    void benignMessage() {
        var r = engine.triage("我最近想体检一下，需要空腹吗");

        assertEquals(0, r.severity());
        assertFalse(r.hasRedFlag());
        assertTrue(r.advice().isEmpty(), "没问题就不该硬凑一句提示");
    }

    @Test
    @DisplayName("空输入不炸")
    void blankInput() {
        assertEquals(0, engine.triage(null).severity());
        assertEquals(0, engine.triage("").severity());
        assertEquals(0, engine.triage("   ").severity());
    }

    @Test
    @DisplayName("空结果也要有非 null 的列表，前端 v-for 不能崩")
    void noneResultHasNoNulls() {
        var r = TriageResult.none();

        assertEquals(0, r.severity());
        assertTrue(r.flags().isEmpty());
        assertTrue(r.departments().isEmpty());
        assertEquals("", r.advice());
    }

    // ────────────────────── 科室匹配 ──────────────────────

    @Test
    @DisplayName("症状指向明确时给出对应科室")
    void mapsDepartment() {
        assertTrue(engine.triage("牙龈肿痛，刷牙出血").departments().contains("口腔科"));
        assertTrue(engine.triage("脸上起疹子很痒").departments().contains("皮肤科"));
    }

    @Test
    @DisplayName("科室最多给两个")
    void atMostTwoDepartments() {
        // 这句话同时命中内科、呼吸内科、耳鼻喉科、急诊科的词
        var r = engine.triage("发烧咳嗽嗓子疼");
        assertTrue(r.departments().size() <= 2,
                "给太多科室等于没给，用户还是不知道该挂哪个");
    }

    // ────────────────────── 给模型的指令 ──────────────────────

    @Test
    @DisplayName("命中高危时，给模型的指令必须要求先提示就医")
    void promptPrefixDemandsUrgency() {
        var prefix = engine.triage("喘不上气").toPromptPrefix();

        assertTrue(prefix.contains("危及") || prefix.contains("立即就医")
                        || prefix.contains("必须"),
                "指令里必须明确要求模型先做紧急提示");
        assertTrue(prefix.contains("120") || prefix.contains("急诊"));
    }

    @Test
    @DisplayName("没有命中时不注入任何指令，避免污染上下文")
    void noGuidanceNoPrefix() {
        assertEquals("", engine.triage("我最近想体检一下").toPromptPrefix());
    }

    @Test
    @DisplayName("只有科室指向时也给指令，但措辞不强硬")
    void departmentOnlyGuidance() {
        var r = engine.triage("牙龈肿痛");
        var prefix = r.toPromptPrefix();

        assertTrue(prefix.contains("科室"));
        assertFalse(prefix.contains("立即就医"), "只是建议科室，不该用急救措辞");
    }
}
