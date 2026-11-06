package com.mediquery.integration.ai;

import java.util.List;

/**
 * 把关键词列表拼成 MySQL {@code MATCH ... AGAINST(... IN BOOLEAN MODE)} 的表达式。
 *
 * <p><b>为什么每个词都要加双引号。</b>
 * 索引用的是 ngram 解析器（{@code WITH PARSER ngram}，{@code ngram_token_size=2}），
 * 它把全文切成两字一组的 token。于是「病毒性感冒」会变成
 * {@code 病毒 / 毒感 / 性感 / 感冒}。裸词在布尔模式下如何解释，
 * 取决于 MySQL 版本与解析器行为，这不是我们想依赖的东西。
 *
 * <p>实测对照（词 {@code 病毒性感冒}，语料里混入只含「性感」的干扰行，
 * 数据见设计文档 3.6 节）：
 * <pre>
 * BOOLEAN MODE 裸词        → WHERE 阶段只召回真命中行，干扰行未混入
 * BOOLEAN MODE 引号短语    → 同样只召回真命中行
 * NATURAL MODE 裸词        → 干扰行也进入结果集，只是分数极低
 *                            （真命中 1.09，干扰行 0.03）
 * NATURAL MODE 引号短语    → 干扰行的分数不会抬高
 * </pre>
 * 也就是说：在布尔模式的过滤阶段，加不加引号这一版 MySQL 表现一致；
 * <b>但它们不是永远等价</b> —— 只要哪天把查询换成 NATURAL MODE 打分、
 * 或者在 WHERE 后面再叠一层相关度排序，裸词就会让干扰行混进来。
 *
 * <p>所以这里加引号是<b>把语义钉死</b>，不是修一个当前存在的 bug：
 * 引号的含义是「这几个字必须连在一起出现」，和 {@code LIKE '%kw%'} 完全一致，
 * 不依赖解析器怎么切。多关键词之间仍是 OR（空格分隔），
 * 与原来 OR LIKE 拼接的召回语义相同。
 *
 * <p>引号内的转义：只有 {@code "} 和 {@code \} 需要反斜杠转义，
 * 其余布尔模式操作符（{@code + - < > ( ) ~ * @}）在引号内都是普通字符。
 */
public final class MatchExpression {

    private MatchExpression() {
    }

    /**
     * @param keywords 已去重、已过滤的关键词；允许含 null 或空白项（会被跳过）
     * @return 形如 {@code "咳嗽" "发热"} 的表达式；无有效关键词时返回空串
     */
    public static String build(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) return "";
        var sb = new StringBuilder();
        for (var kw : keywords) {
            if (kw == null || kw.isBlank()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append('"').append(escape(kw)).append('"');
        }
        return sb.toString();
    }

    /** 引号内只有 {@code "} 和 {@code \} 是特殊字符 */
    static String escape(String raw) {
        var sb = new StringBuilder(raw.length() + 4);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }
}
