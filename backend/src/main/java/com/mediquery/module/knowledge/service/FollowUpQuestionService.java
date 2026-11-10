package com.mediquery.module.knowledge.service;

import com.mediquery.module.knowledge.dto.DiseaseSymptomView;
import com.mediquery.module.knowledge.dto.NormalizedSymptom;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 主动追问：从候选疾病里挑出「问了最能分开它们」的那一两个症状。
 *
 * <h3>为什么不是「这个病还有什么症状就问什么」</h3>
 * <p>那样会问出最没用的东西。手边有一百个候选时，几乎每个候选都写了
 * 「发热」—— 问「有没有发热」的答案是「有」或「没有」，无论哪种，
 * 都没法把候选砍掉多少。<b>有用的问题是能把候选对半劈开的那个</b>，
 * 比如「吞咽困难」：扁桃体炎有、病毒性感冒没有，答「有」就基本定案，
 * 答「没有」就把它划掉。
 *
 * <h3>怎么判断「能劈开」</h3>
 * <p>看这个症状在候选里出现的比例：
 * <ul>
 *   <li>全部候选都有 → 0 信息量，问它等于没问；</li>
 *   <li>只有一个候选有 → 信息量大但风险也大，答「有」就定案，
 *       答「没有」等于白问一轮；</li>
 *   <li>接近一半有 → 最好的情况，两种回答都能砍掉一半候选。</li>
 * </ul>
 * 所以用与「对半分」的接近程度来排：{@code 4p(1-p)} 在 p=0.5 时取到 1，
 * 在 p=0 或 1 时取到 0 —— 正是信息增益的形状，而且不用真去算熵。
 *
 * <h3>为什么还要看 weight 和 cardinal</h3>
 * <p>光看分布会把「两个病都写了但都只写了个边缘症状」的东西排前面。
 * 核心症状（{@code is_cardinal}）和带权重的边才是这个病的典型表现，
 * 问典型表现比问边角料更可能在对话里被患者认可。
 */
@Service
public class FollowUpQuestionService {

    /** 最多问几个问题。问三个以上，用户没有耐心答完。 */
    public static final int MAX_QUESTIONS = 2;

    /**
     * 一个待问的症状。
     *
     * @param symptomName 症状名，用来问「有没有…」
     * @param hitDiseases 有此症状的候选病名
     * @param missDiseases 没有此症状的候选病名
     * @param weight 该症状在候选里的平均权重，只是排序的次要项
     */
    public record Question(String symptomName,
                           List<String> hitDiseases,
                           List<String> missDiseases,
                           double weight) {

        /** 答「有」能收窄到哪些病；答「没有」能收窄到哪些病。 */
        public String hint() {
            return "答「有」更可能是" + String.join("、", hitDiseases)
                    + "；答「没有」则更像" + String.join("、", missDiseases);
        }
    }

    /**
     * 挑出值得问的问题。
     *
     * @param candidates 候选疾病的全部症状（同一病多行）
     * @param known      用户已经说过的症状名 —— 已经说过的不用再问
     * @return 按鉴别价值从高到低，最多 {@link #MAX_QUESTIONS} 个
     */
    public List<Question> pickQuestions(List<DiseaseSymptomView> candidates,
                                        List<NormalizedSymptom> known) {
        if (candidates == null || candidates.size() < 2) {
            // 只有一个候选（或者没有），没有「鉴别」可言
            return List.of();
        }

        Set<String> alreadyKnown = new LinkedHashSet<>();
        if (known != null) {
            for (var s : known) {
                if (s != null && s.getSymptomName() != null) {
                    alreadyKnown.add(s.getSymptomName());
                }
            }
        }

        // 症状 → 哪些候选有它 / 各自权重是多少
        Map<String, List<String>> hitBySymptom = new LinkedHashMap<>();
        Map<String, Double> weightSum = new LinkedHashMap<>();
        Set<String> allCandidates = new LinkedHashSet<>();

        for (var row : candidates) {
            if (row == null || row.getIllnessName() == null) {
                continue;
            }
            allCandidates.add(row.getIllnessName());
            String name = row.getSymptomName();
            if (name == null || alreadyKnown.contains(name)) {
                continue;
            }
            hitBySymptom.computeIfAbsent(name, k -> new ArrayList<>()).add(row.getIllnessName());
            double w = row.getWeight() == null ? 1.0 : row.getWeight().doubleValue();
            weightSum.merge(name, w, Double::sum);
        }

        int total = allCandidates.size();
        var scored = new ArrayList<Scored>();
        for (var e : hitBySymptom.entrySet()) {
            var hit = e.getValue();
            int hitCount = hit.size();
            int missCount = total - hitCount;

            // 所有候选都有它 → 问不出区别；只有零星一个病有 → 前面已论证风险大，
            // 但如果这个病本身就是榜首，那它反而很有价值，所以不直接排除，
            // 只让分布项把它的分压低。
            if (hitCount == 0) {
                continue;
            }

            double p = (double) hitCount / total;
            double split = 4 * p * (1 - p);

            // 边缘症状打七折：核心症状才代表这个病
            double cardinalFactor = hitCount == 1 ? 1.0 : averageCardinal(candidates, e.getKey());
            double weight = weightSum.get(e.getKey()) / hitCount;

            double score = split * cardinalFactor * weight;
            if (score <= 0) {
                continue;
            }

            var miss = new ArrayList<String>();
            for (var name : allCandidates) {
                if (!hit.contains(name)) {
                    miss.add(name);
                }
            }
            scored.add(new Scored(score, new Question(e.getKey(), List.copyOf(hit), List.copyOf(miss), weight)));
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));

        // 两个症状如果命中的候选完全是同一批，它们提供的是同一条信息 ——
        // 「偏头痛有搏动性头痛、单侧头痛，另外两个病都没有」，那这两个问题
        // 答完得到的结论一模一样。问一个是鉴别，问两个是让人重复回答。
        var picked = new ArrayList<Question>(MAX_QUESTIONS);
        var seenHitSets = new LinkedHashSet<List<String>>();
        for (var s : scored) {
            if (picked.size() >= MAX_QUESTIONS) {
                break;
            }
            if (!seenHitSets.add(s.question.hitDiseases())) {
                continue;
            }
            picked.add(s.question);
        }
        return picked;
    }

    /** 该症状在候选里是不是核心症状；混合时按比例给个 0.7~1.0 之间的系数。 */
    private double averageCardinal(List<DiseaseSymptomView> candidates, String symptomName) {
        int total = 0;
        int cardinal = 0;
        for (var row : candidates) {
            if (row != null && symptomName.equals(row.getSymptomName())) {
                total++;
                if (Boolean.TRUE.equals(row.getIsCardinal())) {
                    cardinal++;
                }
            }
        }
        if (total == 0) {
            return 1.0;
        }
        // 全是核心症状 → 1.0；全是边缘 → 0.7
        return 0.7 + 0.3 * ((double) cardinal / total);
    }

    private record Scored(double score, Question question) {
    }
}
