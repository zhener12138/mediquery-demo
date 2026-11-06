package com.mediquery.integration.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MatchExpression} 的单元测试。
 *
 * <p>这是 3.6 全文检索改造里唯一能在 CI（H2）里被完整覆盖的部分 ——
 * 因为它不碰 SQL，只拼字符串。生成的表达式最终会被塞进
 * {@code MATCH ... AGAINST(#{expr} IN BOOLEAN MODE)}，
 * 所以这里每条断言都在保护「拼错了会让查询要么语法报错要么召回错东西」这个后果。
 */
class MatchExpressionTest {

    @Test
    @DisplayName("单个关键词被双引号包裹")
    void singleKeywordIsQuoted() {
        assertThat(MatchExpression.build(List.of("感冒"))).isEqualTo("\"感冒\"");
    }

    @Test
    @DisplayName("多个关键词以空格分隔，每个各自成短语")
    void multipleKeywordsAreSeparatedBySpace() {
        assertThat(MatchExpression.build(List.of("咳嗽", "发热")))
                .isEqualTo("\"咳嗽\" \"发热\"");
    }

    @Test
    @DisplayName("关键词之间的空格是 OR，不是 AND —— 拼成短语就会变成 AND 语义")
    void keywordsAreNotMergedIntoOnePhrase() {
        var expr = MatchExpression.build(List.of("咳嗽", "发热"));
        // 一条短语 "咳嗽 发热" 表示「这两词必须相邻且按序出现」，召回会大幅收窄
        assertThat(expr).isNotEqualTo("\"咳嗽 发热\"");
        assertThat(expr.split("\" \"")).hasSize(2);
    }

    @Test
    @DisplayName("空列表与 null 返回空串，调用方据此跳过查询")
    void emptyInputYieldsEmptyExpression() {
        assertThat(MatchExpression.build(List.of())).isEmpty();
        assertThat(MatchExpression.build(null)).isEmpty();
    }

    @Test
    @DisplayName("null 与空白关键词被跳过，不产生一对空引号")
    void blankKeywordsAreSkipped() {
        var withBlanks = new ArrayList<String>();
        withBlanks.add("感冒");
        withBlanks.add(null);
        withBlanks.add("   ");
        withBlanks.add("");
        withBlanks.add("咳嗽");
        // 空引号 "" 在布尔模式下是零长度短语，会让整条 MATCH 语法出错
        assertThat(MatchExpression.build(withBlanks)).isEqualTo("\"感冒\" \"咳嗽\"");
        assertThat(MatchExpression.build(withBlanks)).doesNotContain("\"\"");
    }

    @Test
    @DisplayName("全是空白时退化为空串，不产生 \"\" 这种非法短语")
    void allBlankYieldsEmptyExpression() {
        assertThat(MatchExpression.build(Arrays.asList(null, "", "  "))).isEmpty();
    }

    @Test
    @DisplayName("双引号被转义，避免关键词提前闭合短语")
    void doubleQuoteIsEscaped() {
        // 用户真的会输入引号；不转义的话 "a"b" 会被解析成短语 a 加一个裸词 b
        assertThat(MatchExpression.build(List.of("a\"b"))).isEqualTo("\"a\\\"b\"");
    }

    @Test
    @DisplayName("反斜杠被转义，避免吞掉后续字符")
    void backslashIsEscaped() {
        assertThat(MatchExpression.build(List.of("a\\b"))).isEqualTo("\"a\\\\b\"");
    }

    @Test
    @DisplayName("布尔模式操作符在引号内保持原样，不需要转义")
    void booleanOperatorsAreLeftAloneInsideQuotes() {
        // + - < > ( ) ~ * 在引号内都是普通字符，多转义反而会把用户输入改坏
        var raw = "+咳嗽 -发热 (严重) ~排除 *通配";
        var expr = MatchExpression.build(List.of(raw));
        assertThat(expr).isEqualTo("\"" + raw + "\"");
    }

    @Test
    @DisplayName("转义只针对引号与反斜杠，不动中文")
    void escapeTouchesOnlyQuoteAndBackslash() {
        assertThat(MatchExpression.escape("病毒性感冒")).isEqualTo("病毒性感冒");
        assertThat(MatchExpression.escape("\"\\")).isEqualTo("\\\"\\\\");
    }

    @Test
    @DisplayName("关键词顺序被保留 —— 顺序影响 MySQL 的相关度打分")
    void orderIsPreserved() {
        var expr = MatchExpression.build(List.of("甲", "乙", "丙"));
        assertThat(expr.indexOf("甲")).isLessThan(expr.indexOf("乙"));
        assertThat(expr.indexOf("乙")).isLessThan(expr.indexOf("丙"));
    }

    @Test
    @DisplayName("单个中文字符也能成短语，不会被丢掉")
    void singleCharKeywordIsKept() {
        // tokenize 的最小长度是 2，但 MatchExpression 不负责长度策略，
        // 万一上游改了分词规则，这里不应该静默丢掉单字
        assertThat(MatchExpression.build(List.of("热"))).isEqualTo("\"热\"");
    }
}
