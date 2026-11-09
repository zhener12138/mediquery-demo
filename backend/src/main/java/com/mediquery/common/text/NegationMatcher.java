package com.mediquery.common.text;

import java.util.List;

/**
 * 否定判定：判断一个关键词在文本里的某次出现，是不是被前面的否定词否掉了。
 *
 * <p>「没有胸痛」「否认发烧」和「胸痛」「发烧」是完全相反的结论。不做这一层区分，
 * 后果是：患者越仔细地把阴性症状说全，被误报的概率越高 —— 这恰好惩罚了
 * 表达最严谨的那部分用户。
 *
 * <p>这套逻辑原来只活在 {@code TriageRuleEngine} 里（判断危险信号）。做症状归一化时
 * 需要一模一样的语义 —— 「没有头痛」不该被归一成「头痛」。与其抄一份，
 * 不如把它提出来两边共用：抄一份的下场是两边迟早不一致，而这里不一致的代价
 * 是「一份说没事、一份说有事」。
 */
public final class NegationMatcher {

    /** 否定词：出现在关键词前面不远处，说明用户是在<b>否认</b>。 */
    private static final List<String> NEGATIONS = List.of("没有", "没", "不", "无", "否认", "未", "从未");

    /**
     * 双否定：「不排除胸痛」「无法排除」是<b>还没排除</b>，属于命中，不是否认。
     *
     * <p>这几条必须在扫否定词之前先从窗口里抹掉，否则「不排除」会先被「不」判成否认，
     * 把最该报的那句话静音掉。
     */
    private static final List<String> DOUBLE_NEGATION =
            List.of("不排除", "无法排除", "不能排除", "难以排除", "不否认", "未排除");

    /**
     * 否定词的作用范围：关键词前面几个字之内。
     *
     * <p>窗口开大能从「无诱因胸痛」里误杀真正的胸痛，而漏报的代价是人的健康。
     * 所以宁可放过几个否定句，也不能把真危险信号静音。
     */
    private static final int NEGATION_WINDOW = 5;

    /** 分句边界。跨过标点就不是同一个短句，否定词管不到后面去。 */
    private static final String PUNCTUATION = "，。、；！？,.!?;：\n\r";

    private NegationMatcher() {
    }

    /**
     * 关键词是否在「没被否定的位置」出现过。
     *
     * <p>同一个词可能出现多次：「没有胸痛，但刚才突然胸痛了一下」——
     * 前半句否认、后半句承认。只要有一处没被否定就算命中，所以逐处检查。
     */
    public static boolean containsUnnegated(String text, String keyword) {
        if (text == null || keyword == null || keyword.isEmpty()) {
            return false;
        }
        int from = 0;
        while (true) {
            int idx = text.indexOf(keyword, from);
            if (idx < 0) {
                return false;
            }
            if (!isNegated(text, idx)) {
                return true;
            }
            from = idx + keyword.length();
        }
    }

    /** 关键词的这一处出现，前面是不是跟着一个把它否掉的词。 */
    public static boolean isNegated(String text, int hitIndex) {
        int start = Math.max(0, hitIndex - NEGATION_WINDOW);
        // 往回找最近的标点，找到就从它后面开始算 —— 否定不跨句
        for (int i = hitIndex - 1; i >= start; i--) {
            if (PUNCTUATION.indexOf(text.charAt(i)) >= 0) {
                start = i + 1;
                break;
            }
        }
        if (start >= hitIndex) {
            return false;
        }

        String window = text.substring(start, hitIndex);
        // 先把双否定整段抹掉，剩下的窗口里才有资格谈「否认」
        for (String dn : DOUBLE_NEGATION) {
            window = window.replace(dn, "");
        }
        for (String neg : NEGATIONS) {
            if (window.contains(neg)) {
                return true;
            }
        }
        return false;
    }
}
