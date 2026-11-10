package com.mediquery.module.knowledge.service;

import com.mediquery.module.knowledge.dto.DiseaseSymptomEdge;
import com.mediquery.module.knowledge.dto.SymptomDf;
import com.mediquery.module.knowledge.dto.SymptomHit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 疾病打分：把「症状—疾病」边折成每个疾病一个分，决定谁进 Top3。
 *
 * <p>单独抽成一个无状态类，不为分层好看，是因为这块逻辑以前待在一条
 * SQL 的 {@code SUM()} 里，改一次就要跑一遍整个 Spring 容器才能验证。
 * 放在这里之后，「发热该不该比骨擦音值钱」这种问题能用几行单测回答。
 *
 * <h3>为什么原来的 {@code SUM(weight)} 不够</h3>
 * <p>假设知识库只有 10 个病，其中 8 个都写「发热」。用户说「发烧」，
 * 这 8 个病各得一份 {@code weight}，谁的分高取决于各自还把多少别的
 * 症状写进去了 —— 也就是说，<b>「发热」这个词几乎没提供任何区分度，
 * 却给它命中的每个病都加了同样的分</b>。用户再说「骨擦音」时，
 * 骨折的这份权重含金量跟「发热」完全不是一回事，但公式当成一样算。
 *
 * <p>IDF 就是把这个区别补回来：出现在多半疾病里的症状，
 * 说明它是个背景噪声，乘个小系数；只出现在一两个病里的症状，
 * 说明它基本能定案，乘个大系数。
 *
 * <h3>为什么用平滑公式而不是 {@code log(N/df)}</h3>
 * <p>教科书 idf 在 {@code df = N} 时等于 0 —— 一个「所有病都有」的症状，
 * 整条边被彻底抹掉。这在纯文本检索里无所谓（那个词确实没信息），
 * 但在这里会把「几乎每个病都写发热」直接变成「发热不算数」，
 * 而发热恰恰是用户最常说的那句话。{@code log((N+1)/(df+1)) + 1}
 * 让最普遍的词退到 1.0 而不是 0，保住「至少算一条证据」，
 * 同时保持大小关系的严格单调。
 */
public final class DiseaseScorer {

    private DiseaseScorer() {
    }

    /**
     * 按 {@code df} 算每个症状的区分度系数。
     *
     * @param totalDiseases 已发布疾病总数（IDF 分母）
     * @param dfs           本轮命中症状各自的文档频率
     * @return 症状 ID → idf；本轮没查到的症状不在表里，调用方按 1.0 处理
     */
    public static Map<Integer, Double> idf(int totalDiseases, List<SymptomDf> dfs) {
        Map<Integer, Double> result = new HashMap<>();
        if (dfs == null) {
            return result;
        }
        // 分母至少是 1：知识库为空时不该算出 NaN 或负无穷
        double n = Math.max(1, totalDiseases);
        for (var row : dfs) {
            if (row == null || row.getSymptomId() == null) {
                continue;
            }
            int df = row.getDf() == null ? 0 : row.getDf();
            if (df <= 0) {
                continue;
            }
            result.put(row.getSymptomId(), Math.log((n + 1) / (df + 1)) + 1);
        }
        return result;
    }

    /**
     * 把边折成疾病候选，按分数从高到低排。
     *
     * @param edges 本轮命中的全部边（同一种病可能出现多行）
     * @param idf   症状区分度，缺项按 1.0 处理
     * @param limit 最多返回多少个候选，0 或负数表示不限
     */
    public static List<SymptomHit> score(List<DiseaseSymptomEdge> edges,
                                         Map<Integer, Double> idf,
                                         int limit) {
        return score(edges, idf, java.util.Set.<Integer>of(), limit);
    }

    /**
     * 带否认症状的打分。
     *
     * <p>否认不是「没提到」。患者说「没有发烧」，意思是<b>这个病不能拿发烧当理由</b> ——
     * 一份写着「发热为主」的感冒，和一份写着「咽痛为主」的扁桃体炎，
     * 在「咽痛 + 否认发热」这句话面前份量必须不一样。
     *
     * <p>做法是<b>不给这条边计分</b>，而不是打个折。这一点试过：
     * 第一版按 {@code 0.5} 折算，结果病毒性感冒在「咳嗽但没有发烧」里
     * 反而比不打折时<b>更高分</b>（0.6 × 0.5 = 0.3 仍是白送的），
     * 等于系统把患者主动排除的方向又捡回来一次。控制实验（把系数改成 1.0
     * 再跑同一批评测）结果一模一样，说明那一版对排序毫无影响 —— 看着实现了，
     * 实际是空的。
     *
     * <p>不计分的连带结果是：如果某个病<b>只</b>靠被否认的症状进候选，
     * 它这轮得 0 分，会掉到列表最后。不把它删掉 —— 保留在列表里成本很低，
     * 而误删一个可能正确的病，用户看到的是「系统没提」，比排最后难发现得多。
     *
     * @param deniedSymptomIds 患者明确否认的症状 ID
     */
    public static List<SymptomHit> score(List<DiseaseSymptomEdge> edges,
                                         Map<Integer, Double> idf,
                                         java.util.Collection<Integer> deniedSymptomIds,
                                         int limit) {
        if (edges == null || edges.isEmpty()) {
            return List.of();
        }
        var denied = deniedSymptomIds == null ? java.util.Set.<Integer>of()
                : new java.util.HashSet<>(deniedSymptomIds);

        Map<Integer, double[]> totals = new LinkedHashMap<>();
        Map<Integer, SymptomHit> bodies = new LinkedHashMap<>();

        for (var edge : edges) {
            if (edge == null || edge.getIllnessId() == null) {
                continue;
            }
            var disease = bodies.computeIfAbsent(edge.getIllnessId(), id -> {
                var hit = new SymptomHit();
                hit.setIllnessId(edge.getIllnessId());
                hit.setIllnessName(edge.getIllnessName());
                hit.setCategoryId(edge.getCategoryId());
                hit.setCause(edge.getCause());
                hit.setSymptom(edge.getSymptom());
                hit.setSpecialSymptom(edge.getSpecialSymptom());
                hit.setScore(BigDecimal.ZERO);
                hit.setHasRedFlag(Boolean.FALSE);
                hit.setCardinalHits(0);
                return hit;
            });

            double weight = edge.getWeight() == null ? 1.0 : edge.getWeight().doubleValue();
            double factor = idf.getOrDefault(edge.getSymptomId(), 1.0);
            // 被否认的症状不计分：见方法注释，打对折等于白送半分
            if (edge.getSymptomId() != null && denied.contains(edge.getSymptomId())) {
                weight = 0.0;
            }
            double[] acc = totals.computeIfAbsent(edge.getIllnessId(), id -> new double[2]);
            acc[0] += weight * factor;
            // 被否认的症状既不加分也不计入「命中了几个核心症状」，
            // 否则同分的两个病里，靠被否认症状撑出核心命中的那个反而排前面
            if (Boolean.TRUE.equals(edge.getIsCardinal()) && weight > 0.0) {
                acc[1] += 1;
            }
            if (Boolean.TRUE.equals(edge.getIsRedFlag())) {
                disease.setHasRedFlag(Boolean.TRUE);
            }
        }

        List<SymptomHit> ranked = new ArrayList<>(bodies.size());
        for (var body : bodies.values()) {
            double[] acc = totals.get(body.getIllnessId());
            // 保留三位小数：分数只用来排序，多留几位既没意义又让日志难看
            body.setScore(BigDecimal.valueOf(acc[0]).setScale(3, java.math.RoundingMode.HALF_UP));
            body.setCardinalHits((int) acc[1]);
            ranked.add(body);
        }

        ranked.sort((a, b) -> {
            int byScore = b.getScore().compareTo(a.getScore());
            if (byScore != 0) {
                return byScore;
            }
            int byCardinal = Integer.compare(b.getCardinalHits(), a.getCardinalHits());
            if (byCardinal != 0) {
                return byCardinal;
            }
            // 最后按 ID 兜底，保证同样的输入永远得到同样的顺序
            return Integer.compare(a.getIllnessId(), b.getIllnessId());
        });

        return limit > 0 && ranked.size() > limit ? ranked.subList(0, limit) : ranked;
    }
}
