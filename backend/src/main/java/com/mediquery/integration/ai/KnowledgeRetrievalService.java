package com.mediquery.integration.ai;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.mediquery.module.illness.entity.Illness;
import com.mediquery.module.medicine.entity.Medicine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RAG 知识检索服务：根据用户问题从疾病/药品库中检索相关条目，
 * 格式化为 LLM 可直接理解的上下文字符串。
 *
 * <p>检索策略：
 * <ol>
 *   <li>中文按 2-4 字滑动窗口粗分词，并过滤停用词与纯数字词；</li>
 *   <li>交给 {@link RetrievalStrategy} 用一条 SQL 批量召回（疾病 1 条 + 药品 1 条），
 *       生产环境走 MySQL 全文索引，测试环境走 OR LIKE；</li>
 *   <li>在内存中按「名称命中 ×3、正文命中 ×1、词长加权」精排，取 Top3；</li>
 *   <li>格式化为 Markdown 注入 LLM 上下文。</li>
 * </ol>
 *
 * <p>演变过程（三步，每一步都是因为上一步被实测证明不够）：
 * <ol>
 *   <li>最初「每个关键词各调一次分页搜索」：一句 20+ 词的问题产生上百次数据库往返，
 *       且最终按插入顺序截断、与相关度无关；</li>
 *   <li>改成「全部关键词拼一条 OR LIKE」：往返降到 2 次，但 72 个 LIKE 条件
 *       让 MySQL 完全用不上索引，只能全表扫描；</li>
 *   <li>改成「MATCH ... AGAINST 走全文索引」：可用索引，且数据库侧就能按相关度返回。</li>
 * </ol>
 */
@Service
public class KnowledgeRetrievalService {

    /** 每类知识最多注入的条目数 */
    private static final int TOP_K = 3;
    /** 单次批量召回的上限，留足重排空间 */
    private static final int RECALL_LIMIT = 50;

    private final RetrievalStrategy retrievalStrategy;

    public KnowledgeRetrievalService(RetrievalStrategy retrievalStrategy) {
        this.retrievalStrategy = retrievalStrategy;
    }

    /**
     * 根据用户输入检索相关知识，格式化为上下文文本。
     * 返回空字符串表示未找到匹配。
     */
    public String retrieve(String userMessage) {
        if (!StringUtils.hasText(userMessage)) return "";

        var keywords = tokenize(userMessage);
        if (keywords.isEmpty()) return "";

        var illnesses = rankIllnesses(keywords);
        var medicines = rankMedicines(keywords);

        var context = new StringBuilder();
        if (!illnesses.isEmpty()) {
            context.append("【参考知识库 · 疾病】\n");
            for (var i : illnesses) context.append(formatIllness(i));
        }
        if (!medicines.isEmpty()) {
            context.append("【参考知识库 · 药品】\n");
            for (var m : medicines) context.append(formatMedicine(m));
        }
        return context.toString();
    }

    /** 批量召回后按相关度取 Top3 */
    private List<Illness> rankIllnesses(List<String> keywords) {
        var candidates = retrievalStrategy.recallIllnesses(keywords, RECALL_LIMIT);
        return candidates.stream()
                .map(i -> new Scored<>(i, scoreIllness(i, keywords)))
                .filter(s -> s.score > 0)
                .sorted(Comparator.comparingInt((Scored<Illness> s) -> s.score).reversed())
                .limit(TOP_K)
                .map(s -> s.item)
                .collect(Collectors.toList());
    }

    /** 批量召回后按相关度取 Top3 */
    private List<Medicine> rankMedicines(List<String> keywords) {
        var candidates = retrievalStrategy.recallMedicines(keywords, RECALL_LIMIT);
        return candidates.stream()
                .map(m -> new Scored<>(m, scoreMedicine(m, keywords)))
                .filter(s -> s.score > 0)
                .sorted(Comparator.comparingInt((Scored<Medicine> s) -> s.score).reversed())
                .limit(TOP_K)
                .map(s -> s.item)
                .collect(Collectors.toList());
    }

    /**
     * 相关度打分：命中词越多分越高；命中词越长分越高（4 字词比 2 字词更具区分度）。
     * 名称命中权重最高，其次是功效/症状描述。
     */
    private int scoreIllness(Illness illness, List<String> keywords) {
        var name = nullToEmpty(illness.getName());
        var body = nullToEmpty(illness.getCause()) + nullToEmpty(illness.getSymptom())
                + nullToEmpty(illness.getSpecialSymptom());
        return scoreAgainst(name, body, keywords);
    }

    private int scoreMedicine(Medicine medicine, List<String> keywords) {
        var name = nullToEmpty(medicine.getName());
        var body = nullToEmpty(medicine.getKeyword()) + nullToEmpty(medicine.getEffect());
        return scoreAgainst(name, body, keywords);
    }

    private int scoreAgainst(String name, String body, List<String> keywords) {
        int score = 0;
        for (var kw : keywords) {
            // 词长加权：2 字 +1，3 字 +2，4 字及以上 +3
            int weight = Math.min(kw.length() - 1, 3);
            if (name.contains(kw)) score += weight * 3;   // 名称命中权重最高
            else if (body.contains(kw)) score += weight;  // 正文命中
        }
        // 浏览量作为稳定的次要排序因子（避免同分时结果随机）
        return score;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private record Scored<T>(T item, int score) {}

    /**
     * 拆词：中文无空格，使用滑动窗口提取 2-4 字短词作为搜索关键词。
     * 同时过滤常见无意义词与纯数字词。
     *
     * <p><b>先抹掉停用词再滑窗，而不是滑窗之后再判。</b>
     * 这两件事不一样：「怎么办」整词在停用词表里，但滑窗会切出「怎么」「么办」，
     * 它们不在表里，于是照样被拿去查库。用户只打一句「怎么办」时，
     * 旧实现会发出一次注定零命中的检索，白跑一遍数据库与全文索引。
     * 这个问题是在写 {@code onlyStopWordsSkipsRecall} 时暴露的 ——
     * 那条测试最初是红的，改的是实现不是测试。
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "什么", "怎么样", "如何", "为什么", "怎么办", "可以吗", "有没有",
            "哪些", "哪个", "请问", "帮我", "我想", "一下", "推荐", "应该",
            "需要", "是什么", "了吗", "呢", "吧", "呀", "啊", "吗"
    );

    /** 按长度降序，保证「怎么样」先于「么样」被抹掉 */
    private static final List<String> STOP_WORDS_BY_LENGTH =
            STOP_WORDS.stream()
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .toList();

    private List<String> tokenize(String msg) {
        // 去标点与空白
        var cleaned = msg.replaceAll("[？?！!。，,、；;：:（）()\\s]+", "");
        if (cleaned.isEmpty()) return List.of();
        // 先把停用词整段抹掉，剩下的才是有信息量的部分
        var stem = stripStopWords(cleaned);
        if (stem.isEmpty()) return List.of();
        var result = new LinkedHashSet<String>();
        // 滑动窗口：4字 → 3字 → 2字，长词优先（长词区分度更高）
        for (int len = 4; len >= 2; len--) {
            for (int i = 0; i + len <= stem.length(); i++) {
                var token = stem.substring(i, i + len);
                if (STOP_WORDS.contains(token)) continue;
                if (isAllDigits(token)) continue;
                result.add(token);
            }
        }
        return new ArrayList<>(result);
    }

    /** 按长度从长到短抹除停用词 */
    private static String stripStopWords(String s) {
        var out = s;
        for (var sw : STOP_WORDS_BY_LENGTH) {
            if (out.contains(sw)) out = out.replace(sw, "");
        }
        return out;
    }


    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private String formatIllness(Illness i) {
        var sb = new StringBuilder();
        sb.append("## 疾病：").append(i.getName()).append("\n");
        if (StringUtils.hasText(i.getCause()))
            sb.append("- 病因：").append(i.getCause()).append("\n");
        if (StringUtils.hasText(i.getSymptom()))
            sb.append("- 症状：").append(i.getSymptom()).append("\n");
        if (StringUtils.hasText(i.getSpecialSymptom()))
            sb.append("- 特殊说明：").append(i.getSpecialSymptom()).append("\n");
        var medicines = i.getMedicines();
        if (medicines != null && !medicines.isEmpty()) {
            var names = medicines.stream().map(Medicine::getName).collect(Collectors.joining("、"));
            sb.append("- 关联药品：").append(names).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    private String formatMedicine(Medicine m) {
        var sb = new StringBuilder();
        sb.append("## 药品：").append(m.getName()).append("\n");
        if (StringUtils.hasText(m.getEffect()))
            sb.append("- 功效：").append(m.getEffect()).append("\n");
        if (StringUtils.hasText(m.getUsageDosage()))
            sb.append("- 用法用量：").append(m.getUsageDosage()).append("\n");
        if (StringUtils.hasText(m.getContraindication()))
            sb.append("- 禁忌：").append(m.getContraindication()).append("\n");
        if (StringUtils.hasText(m.getInteraction()))
            sb.append("- 药物相互作用：").append(m.getInteraction()).append("\n");
        sb.append("\n");
        return sb.toString();
    }
}
