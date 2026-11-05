package com.mediquery.integration.ai;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.mediquery.module.illness.entity.Illness;
import com.mediquery.module.illness.service.IllnessService;
import com.mediquery.module.medicine.entity.Medicine;
import com.mediquery.module.medicine.service.MedicineService;

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
 *   <li>全部关键词合并为一条 SQL 的 OR 条件批量召回（疾病 1 条 + 药品 1 条）；</li>
 *   <li>在内存中按「命中关键词数量 + 关键词长度」加权打分，取 Top3；</li>
 *   <li>格式化为 Markdown 注入 LLM 上下文。</li>
 * </ol>
 *
 * <p>早期实现是「每个关键词各调一次分页搜索」，一句 20+ 词的问题会产生上百次
 * 数据库往返（含 count），且最终按插入顺序截断、与相关度无关。本类已改为
 * 「一次查询 + 相关度重排」。
 */
@Service
public class KnowledgeRetrievalService {

    /** 每类知识最多注入的条目数 */
    private static final int TOP_K = 3;
    /** 单次批量召回的上限，留足重排空间 */
    private static final int RECALL_LIMIT = 50;

    private final IllnessService illnessService;
    private final MedicineService medicineService;

    public KnowledgeRetrievalService(IllnessService illnessService, MedicineService medicineService) {
        this.illnessService = illnessService;
        this.medicineService = medicineService;
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
        var candidates = illnessService.searchByKeywords(keywords, RECALL_LIMIT);
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
        var candidates = medicineService.searchByKeywords(keywords, RECALL_LIMIT);
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
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "什么", "怎么样", "如何", "为什么", "怎么办", "可以吗", "有没有",
            "哪些", "哪个", "请问", "帮我", "我想", "一下", "推荐", "应该",
            "需要", "是什么", "了吗", "呢", "吧", "呀", "啊", "吗"
    );

    private List<String> tokenize(String msg) {
        // 去标点与空白
        var cleaned = msg.replaceAll("[？?！!。，,、；;：:（）()\\s]+", "");
        if (cleaned.isEmpty()) return List.of();
        var result = new LinkedHashSet<String>();
        // 滑动窗口：4字 → 3字 → 2字，长词优先（长词区分度更高）
        for (int len = 4; len >= 2; len--) {
            for (int i = 0; i + len <= cleaned.length(); i++) {
                var token = cleaned.substring(i, i + len);
                if (STOP_WORDS.contains(token)) continue;
                if (isAllDigits(token)) continue;
                result.add(token);
            }
        }
        return new ArrayList<>(result);
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
