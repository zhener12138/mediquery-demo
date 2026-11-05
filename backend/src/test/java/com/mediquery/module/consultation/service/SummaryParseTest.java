package com.mediquery.module.consultation.service;

import com.mediquery.module.consultation.vo.SummaryVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 小结解析的健壮性测试。
 *
 * <p>这里测的不是「正常情况能不能解析」—— 那是最不容易出问题的一条路径。
 * 真正会出事的是模型不听话的时候：包了 markdown 代码块、字段只给一半、
 * severity 给了个 7、干脆返回一段中文散文。
 *
 * <h3>一条贯穿全部用例的断言</h3>
 *
 * <p><b>解析失败必须降级成 severity=LOW，绝不能猜成 HIGH。</b>
 * 假的紧急告警比漏报更糟：它让用户对告警脱敏，下次真出事就不当回事了。
 * 所以下面每个异常输入都用 {@code assertEquals(Severity.LOW, ...)} 收尾。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:mediquery_summary;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always"
})
class SummaryParseTest {

    @Autowired
    private SummaryService summaryService;

    // ────────────────────── 正常路径 ──────────────────────

    @Test
    @DisplayName("标准 JSON 能完整解析")
    void parsesCleanJson() {
        String raw = """
                {
                  "chiefComplaint": "咳嗽三天伴低烧",
                  "possibleDirections": ["病毒性感冒", "急性支气管炎"],
                  "suggestedDepartments": ["呼吸内科"],
                  "medicationHints": ["多喝温水", "注意休息"],
                  "severity": 1,
                  "severityReason": "有低烧但无呼吸困难",
                  "redFlags": []
                }
                """;
        SummaryVO vo = summaryService.parse(raw, "兜底主诉");

        assertEquals("咳嗽三天伴低烧", vo.getChiefComplaint());
        assertEquals(2, vo.getPossibleDirections().size());
        assertEquals(1, vo.getSeverity());
        assertEquals("有低烧但无呼吸困难", vo.getSeverityReason());
        assertTrue(vo.getRedFlags().isEmpty());
    }

    @Test
    @DisplayName("包在 markdown 代码块里也能解析")
    void parsesJsonInsideCodeFence() {
        String raw = """
                ```json
                {"chiefComplaint":"头痛","possibleDirections":["紧张性头痛"],"severity":1,
                 "suggestedDepartments":[],"medicationHints":[],"redFlags":[],"severityReason":"x"}
                ```
                """;
        SummaryVO vo = summaryService.parse(raw, "兜底");

        assertEquals("头痛", vo.getChiefComplaint());
        assertEquals(1, vo.getSeverity());
    }

    @Test
    @DisplayName("JSON 前后有解释文字也能抠出来")
    void stripsSurroundingProse() {
        String raw = "好的，这是本次问诊的小结：\n"
                + "{\"chiefComplaint\":\"胃疼\",\"severity\":2,\"possibleDirections\":[\"胃炎\"],"
                + "\"suggestedDepartments\":[],\"medicationHints\":[],\"redFlags\":[\"呕血\"],"
                + "\"severityReason\":\"有呕血\"}\n"
                + "希望对你有帮助。";
        SummaryVO vo = summaryService.parse(raw, "兜底");

        assertEquals("胃疼", vo.getChiefComplaint());
        assertEquals(2, vo.getSeverity(), "合法的高危是允许的，只有解析失败才降级");
        assertEquals(1, vo.getRedFlags().size());
    }

    // ────────────────────── 降级路径 ──────────────────────

    @Test
    @DisplayName("完全不是 JSON 时降级，且 severity 必须是 LOW")
    void nonJsonDegradesToLow() {
        SummaryVO vo = summaryService.parse("抱歉，我无法总结这段对话。", "原始主诉");

        assertEquals("原始主诉", vo.getChiefComplaint(), "降级时用调用方给的主诉兜底");
        assertEquals(0, vo.getSeverity(), "解析失败绝不能变成高危");
        assertNotNull(vo.getPossibleDirections());
        assertTrue(vo.getPossibleDirections().isEmpty());
    }

    @Test
    @DisplayName("空输入降级")
    void blankDegrades() {
        assertEquals(0, summaryService.parse("", "主诉").getSeverity());
        assertEquals(0, summaryService.parse(null, "主诉").getSeverity());
        assertEquals(0, summaryService.parse("   ", "主诉").getSeverity());
    }

    @Test
    @DisplayName("截断的 JSON 降级")
    void truncatedJsonDegrades() {
        SummaryVO vo = summaryService.parse("{\"chiefComplaint\":\"咳嗽\",\"severity\":2", "兜底");
        assertEquals(0, vo.getSeverity(), "半个 JSON 里的 2 不能当真");
    }

    @Test
    @DisplayName("severity 越界时归一化：7 变 0，-1 变 0")
    void outOfRangeSeverityIsNormalized() {
        String high = "{\"chiefComplaint\":\"x\",\"severity\":7,\"possibleDirections\":[],"
                + "\"suggestedDepartments\":[],\"medicationHints\":[],\"redFlags\":[],\"severityReason\":\"y\"}";
        assertEquals(0, summaryService.parse(high, "兜底").getSeverity());

        String negative = "{\"chiefComplaint\":\"x\",\"severity\":-1,\"possibleDirections\":[],"
                + "\"suggestedDepartments\":[],\"medicationHints\":[],\"redFlags\":[],\"severityReason\":\"y\"}";
        assertEquals(0, summaryService.parse(negative, "兜底").getSeverity());
    }

    @Test
    @DisplayName("缺字段时补空数组，不返回 null")
    void missingFieldsBecomeEmptyLists() {
        SummaryVO vo = summaryService.parse("{\"chiefComplaint\":\"咳嗽\",\"severity\":1}", "兜底");

        assertNotNull(vo.getPossibleDirections(), "前端 v-for 拿到 null 会崩");
        assertNotNull(vo.getSuggestedDepartments());
        assertNotNull(vo.getMedicationHints());
        assertNotNull(vo.getRedFlags());
        assertNotNull(vo.getSeverityReason());
    }

    @Test
    @DisplayName("缺 chiefComplaint 时用调用方给的兜底")
    void missingChiefComplaintFallsBack() {
        SummaryVO vo = summaryService.parse("{\"severity\":0}", "用户填的主诉");
        assertEquals("用户填的主诉", vo.getChiefComplaint());
    }

    // ────────────────────── 免责声明 ──────────────────────

    @Test
    @DisplayName("免责声明由后端无条件覆盖，不信模型输出")
    void disclaimerIsAlwaysOverwritten() {
        String raw = "{\"chiefComplaint\":\"x\",\"severity\":0,\"possibleDirections\":[],"
                + "\"suggestedDepartments\":[],\"medicationHints\":[],\"redFlags\":[],"
                + "\"severityReason\":\"y\",\"disclaimer\":\"我是医生，放心吧\"}";
        SummaryVO vo = summaryService.parse(raw, "兜底");

        assertEquals(SummaryVO.DISCLAIMER_TEXT, vo.getDisclaimer());
        assertFalse(vo.getDisclaimer().contains("放心吧"));
    }

    @Test
    @DisplayName("降级路径的免责声明也在")
    void degradedSummaryKeepsDisclaimer() {
        assertEquals(SummaryVO.DISCLAIMER_TEXT, summaryService.parse("垃圾输入", "兜底").getDisclaimer());
    }

    @Test
    @DisplayName("免责声明里必须包含不构成诊断与急救提示")
    void disclaimerHasRequiredPhrases() {
        String d = SummaryVO.DISCLAIMER_TEXT;
        assertTrue(d.contains("不构成医疗诊断"), "合规底线：必须说清这不是诊断");
        assertTrue(d.contains("120"), "必须给出紧急情况下的行动指引");
    }

    // ────────────────────── 状态判定辅助 ──────────────────────

    @Test
    @DisplayName("只有 FAILED 可重试，DONE 不可")
    void retryabilityIsNarrow() {
        assertTrue(SummaryService.isRetryable(3), "FAILED 应可重试");
        assertFalse(SummaryService.isRetryable(2), "DONE 不该被覆盖");
        assertFalse(SummaryService.isRetryable(0));
        assertFalse(SummaryService.isRetryable(1));
    }
}
