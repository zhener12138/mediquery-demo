package com.mediquery.module.knowledge.service;

import com.mediquery.module.knowledge.dto.DiseaseSymptomEdge;
import com.mediquery.module.knowledge.dto.SymptomDf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 打分逻辑的纯单测：不连库、不起 Spring。
 *
 * <p>这一层以前是藏在 SQL 的 {@code SUM()} 里的，改一次要跑整个容器才能
 * 看一眼结果，所以「发热该不该比骨擦音值钱」这个问题从来没人能回答。
 * 拆出来之后它就是一个纯函数，答案几行就能验证。
 */
class DiseaseScorerTest {

    @Test
    @DisplayName("常见症状的系数低于罕见症状，且都大于 0")
    void commonSymptomGetsLowerIdf() {
        // 100 个病，发热挂在 80 个上，骨擦音只挂在 1 个上
        var idf = DiseaseScorer.idf(100, List.of(df(1, 80), df(2, 1)));

        assertThat(idf.get(1)).isLessThan(idf.get(2));
        // 平滑公式下 df = N 也不归零：发热仍然是「一条证据」，只是不值钱
        assertThat(idf.get(1)).isGreaterThan(0.0);
        assertThat(idf.get(2)).isGreaterThan(1.5);
    }

    @Test
    @DisplayName("一个症状挂满所有疾病时，系数退到 1.0 而不是 0")
    void ubiquitousSymptomDoesNotVanish() {
        var idf = DiseaseScorer.idf(10, List.of(df(1, 10)));

        // 教科书 log(N/df) 在这里会给出 0，等于把「发热」整个删掉；
        // 用户最常说的那个词反而最没份量，显然不对
        assertThat(idf.get(1)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("df 缺失的症状不在表里，调用方按 1.0 处理")
    void unknownSymptomIsNotInMap() {
        var idf = DiseaseScorer.idf(10, List.of(df(1, 3)));

        assertThat(idf).containsOnlyKeys(1);
    }

    @Test
    @DisplayName("知识库为空时不算出 NaN 或负无穷")
    void emptyKnowledgeBaseIsSafe() {
        var idf = DiseaseScorer.idf(0, List.of(df(1, 1)));

        assertThat(idf.get(1)).isFinite();
        assertThat(idf.get(1)).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("同分时核心症状命中多的排前面")
    void cardinalHitsBreakTies() {
        // 甲命中一个核心症状，乙只命中边缘症状，两边 weight 一样
        var edges = List.of(
                edge(1, "甲", 10, true, new BigDecimal("1.0")),
                edge(2, "乙", 10, false, new BigDecimal("1.0")));

        var ranked = DiseaseScorer.score(edges, Map.of(10, 1.0), 3);

        assertThat(ranked).extracting(h -> h.getIllnessName()).containsExactly("甲", "乙");
    }

    @Test
    @DisplayName("罕见症状把低权重的病顶到常见症状前面")
    void rareSymptomOutweighsCommonOne() {
        // 甲：命中「发热」(weight 1.0，df=8，idf≈1.1)
        // 乙：命中「骨擦音」(weight 0.5，df=1，idf≈2.3)
        // 不加 idf 时甲赢（1.0 > 0.5），加了之后乙应该反超
        var idf = DiseaseScorer.idf(10, List.of(df(1, 8), df(2, 1)));
        var edges = List.of(
                edge(1, "常见病甲", 1, false, new BigDecimal("1.0")),
                edge(2, "罕见病乙", 2, false, new BigDecimal("0.5")));

        var withoutIdf = DiseaseScorer.score(edges, Map.of(1, 1.0, 2, 1.0), 3);
        var withIdf = DiseaseScorer.score(edges, idf, 3);

        assertThat(withoutIdf.get(0).getIllnessName()).isEqualTo("常见病甲");
        assertThat(withIdf.get(0).getIllnessName()).isEqualTo("罕见病乙");
    }

    @Test
    @DisplayName("红旗不参与打分，只是标记")
    void redFlagIsOnlyAMarker() {
        var edges = List.of(
                edge(1, "有病", 1, false, new BigDecimal("1.0")),
                edge(2, "另一种", 2, false, new BigDecimal("1.0")));
        edges.get(0).setIsRedFlag(true);

        var ranked = DiseaseScorer.score(edges, Map.of(1, 1.0, 2, 1.0), 3);

        assertThat(ranked.get(0).getHasRedFlag()).isTrue();
        // 两者分数仍然相等 —— 红旗没有给它加分
        assertThat(ranked.get(0).getScore()).isEqualByComparingTo(ranked.get(1).getScore());
    }

    @Test
    @DisplayName("同样的输入永远得到同样的顺序")
    void orderIsStableForEqualScores() {
        var edges = List.of(
                edge(3, "丙", 1, false, new BigDecimal("1.0")),
                edge(1, "甲", 1, false, new BigDecimal("1.0")),
                edge(2, "乙", 1, false, new BigDecimal("1.0")));

        var ranked = DiseaseScorer.score(edges, Map.of(1, 1.0), 3);

        assertThat(ranked).extracting(h -> h.getIllnessName()).containsExactly("甲", "乙", "丙");
    }

    @Test
    @DisplayName("limit 生效，且不因超限抛异常")
    void limitIsRespected() {
        var edges = List.of(
                edge(1, "甲", 1, false, new BigDecimal("1.0")),
                edge(2, "乙", 1, false, new BigDecimal("0.9")),
                edge(3, "丙", 1, false, new BigDecimal("0.8")));

        assertThat(DiseaseScorer.score(edges, Map.of(), 2)).hasSize(2);
        assertThat(DiseaseScorer.score(edges, Map.of(), 0)).hasSize(3);
        assertThat(DiseaseScorer.score(List.of(), Map.of(), 3)).isEmpty();
    }

    @Test
    @DisplayName("否认的症状不计分：靠「发热」撑起来的病会被拉到后面")
    void deniedSymptomIsDiscounted() {
        // 甲：只有「发热」一条边（weight 1.0）
        // 乙：「咽痛」一条边（weight 0.7）
        // 不否认时甲赢；患者说了「没有发烧」，甲的那条边一分不得，跌到乙后面
        var edges = List.of(
                edge(1, "靠发热撑的病", 1, false, new BigDecimal("1.0")),
                edge(2, "靠咽痛的病", 2, false, new BigDecimal("0.7")));

        var normal = DiseaseScorer.score(edges, Map.of(), 3);
        var withDenial = DiseaseScorer.score(edges, Map.of(), List.of(1), 3);

        assertThat(normal.get(0).getIllnessName()).isEqualTo("靠发热撑的病");
        assertThat(withDenial.get(0).getIllnessName()).isEqualTo("靠咽痛的病");
    }

    @Test
    @DisplayName("否认不删除候选：只剩它一个时仍然在列表里，只是分数为 0")
    void denialNeverDropsTheOnlyCandidate() {
        // 「宁可排错也不能漏掉」的底线：把可能正确的病直接踢出候选，
        // 用户看到的是「系统没提这个病」，比排最后一行难发现得多
        var edges = List.of(edge(1, "唯一的病", 1, false, new BigDecimal("1.0")));

        var ranked = DiseaseScorer.score(edges, Map.of(), List.of(1), 3);

        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).getScore()).isEqualByComparingTo("0.000");
    }

    @Test
    @DisplayName("否认一个症状不影响它没挂的那些病")
    void denialOnlyTouchesMatchingEdges() {
        var edges = List.of(
                edge(1, "有发热的病", 1, false, new BigDecimal("1.0")),
                edge(2, "没写发热的病", 2, false, new BigDecimal("1.0")));

        var ranked = DiseaseScorer.score(edges, Map.of(), List.of(1), 3);

        // 甲被清零，乙原封不动 —— 两者的差距正是「否认」这件事的贡献
        assertThat(ranked).extracting(h -> h.getIllnessName())
                .containsExactly("没写发热的病", "有发热的病");
        assertThat(ranked.get(1).getScore()).isEqualByComparingTo("0.000");
        assertThat(ranked.get(0).getScore()).isEqualByComparingTo("1.000");
    }

    private static SymptomDf df(int symptomId, int df) {
        var row = new SymptomDf();
        row.setSymptomId(symptomId);
        row.setDf(df);
        return row;
    }

    private static DiseaseSymptomEdge edge(int illnessId, String name, int symptomId,
                                           boolean cardinal, BigDecimal weight) {
        var edge = new DiseaseSymptomEdge();
        edge.setIllnessId(illnessId);
        edge.setIllnessName(name);
        edge.setSymptomId(symptomId);
        edge.setWeight(weight);
        edge.setIsCardinal(cardinal);
        edge.setIsRedFlag(false);
        return edge;
    }
}
