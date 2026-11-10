package com.mediquery.module.knowledge.service;

import com.mediquery.common.text.NegationMatcher;
import com.mediquery.module.knowledge.dto.NormalizedSymptom;
import com.mediquery.module.knowledge.dto.SymptomVocabularyRow;
import com.mediquery.module.knowledge.mapper.SymptomMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 症状归一化：把用户口语化的主诉，映射成知识库里的标准症状实体。
 *
 * <p>这一步是整套图谱检索的入口，也是「失眠召回 0 条」那个问题的正解所在。
 * 老链路是拿 2-4 字滑窗切出来的碎片去 {@code LIKE} 疾病表的顿号串：
 * 「睡不着怎么办」切出「睡不」「不着」「怎么」「么办」，
 * 而疾病表里「失眠」两个字压根不存在 —— 不是数据少，是<b>两边说的不是同一种话</b>。
 *
 * <p>归一化做的是把「睡不着」「入睡困难」「睡不好」都收敛到同一个
 * {@code symptom(id=…, name="失眠")} 上，再由「疾病—症状」边反查疾病。
 * 这样口语怎么变，只要有人把它录进别名表，就能命中。
 *
 * <p><b>三条实现约束</b>：
 * <ol>
 *   <li><b>整表进内存</b>：词表几百到几千行，一次捞回来常驻，比每次查库快，
 *       也比「拿输入去数据库筛」稳 —— 后者要求先切词，又回到切词路径上去了；</li>
 *   <li><b>最长匹配优先</b>：词表里同时有「喉咙」和「喉咙痛」时，
 *       命中的应该是更长的那个，否则「喉咙痛」会被拆成「喉咙」+ 孤立的「痛」；</li>
 *   <li><b>否定过滤</b>：「没有头痛」不该归一成「头痛」。这一层与危险信号判定
 *       共用 {@link NegationMatcher}，两边语义必须一致 —— 一边说没事、一边说有事
 *       是最糟的结果。</li>
 * </ol>
 */
@Service
public class SymptomNormalizer {

    private static final Logger log = LoggerFactory.getLogger(SymptomNormalizer.class);

    /**
     * 词表缓存时长。
     *
     * <p>不设成永久：录了新别名要能生效，又不想每次问诊都发一次全表查询。
     * 五分钟是一个折中 —— 改完数据最多等五分钟，而单个用户一轮问诊里
     * 通常只会触发一次加载。
     */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    /** 最长匹配优先：按文本长度降序，长度相同按权重降序（更可信的说法先匹配） */
    private static final Comparator<SymptomVocabularyRow> LONGEST_FIRST =
            Comparator.comparingInt((SymptomVocabularyRow r) -> length(r.getMatchText()))
                    .reversed()
                    .thenComparing((SymptomVocabularyRow r) -> weightOf(r), Comparator.reverseOrder());

    private final SymptomMapper symptomMapper;

    private volatile List<SymptomVocabularyRow> cache = List.of();
    private volatile long loadedAt = 0L;

    public SymptomNormalizer(SymptomMapper symptomMapper) {
        this.symptomMapper = symptomMapper;
    }

    /**
     * 归一化结果：认出的症状，以及用户<b>明确否认</b>的症状。
     *
     * <p>否认不是「没提」。患者说「没有发烧」和压根不提发烧是两件事：
     * 前者是在主动排除一个方向，后者只是信息缺口。把两者都当没提，
     * 等于让患者把阴性症状说了一遍却得不到任何好处 —— 而这恰恰惩罚了
     * 表达最仔细的那部分人。
     */
    public record Normalized(List<NormalizedSymptom> hits, List<NormalizedSymptom> denied) {

        public static Normalized empty() {
            return new Normalized(List.of(), List.of());
        }
    }

    /**
     * 把一句话归一成症状列表。
     *
     * <p>同一个症状被多个说法命中时只保留一条，取权重最高的那次 ——
     * 「发烧」比「有点热」更确信，没必要在结果里出现两次。
     *
     * @param text 用户这一轮的原始输入，可以为 null
     * @return 按首次出现顺序排列的症状；输入无有效信息时返回空列表
     */
    public List<NormalizedSymptom> normalize(String text) {
        return normalizeWithNegatives(text).hits();
    }

    /**
     * 归一化，同时把被否定的症状也带出来。
     *
     * <p>词表里每个说法照样会被扫一遍：「没有发烧」里的「发烧」命中位置，
     * 只是被 {@link NegationMatcher} 判成否认，于是落进 {@code denied} 而不是 {@code hits}。
     * 下游拿 denied 去扣分、去避免重复追问 —— 具体怎么用不在这里决定，
     * 这一层只负责说实话：用户提了这个词，而且他说的是没有。
     *
     * <p>同一个症状在句子里出现两次、一次否认一次承认（「昨天没有发烧，今天烧起来了」），
     * 以<b>承认</b>为准：患者最后说的是他现在有。
     */
    public Normalized normalizeWithNegatives(String text) {
        if (text == null || text.isBlank()) {
            return Normalized.empty();
        }

        var vocabulary = vocabulary();
        if (vocabulary.isEmpty()) {
            return Normalized.empty();
        }

        // 已认定的字符区间。最长优先 + 区间占位，保证「喉咙痛」不会被再拆出「喉咙」
        boolean[] taken = new boolean[text.length()];
        // key = symptomId，value = 该症状最可信的一次命中
        Map<Integer, NormalizedSymptom> hits = new LinkedHashMap<>();
        Map<Integer, NormalizedSymptom> denied = new LinkedHashMap<>();

        for (var row : vocabulary) {
            var token = row.getMatchText();
            if (token == null || token.isEmpty()) {
                continue;
            }
            int from = 0;
            while (true) {
                int idx = text.indexOf(token, from);
                if (idx < 0) {
                    break;
                }
                int end = idx + token.length();
                if (!overlaps(taken, idx, end)) {
                    mark(taken, idx, end);
                    var found = toNormalized(row, token);
                    var bucket = NegationMatcher.isNegated(text, idx) ? denied : hits;
                    bucket.merge(row.getSymptomId(), found,
                            (oldOne, newOne) -> weightOf(newOne).compareTo(weightOf(oldOne)) > 0 ? newOne : oldOne);
                }
                from = end;
            }
        }

        // 承认优先：同一个症状既被否认又被承认时，按承认算
        denied.keySet().removeIf(hits::containsKey);

        if (!hits.isEmpty() || !denied.isEmpty()) {
            log.debug("症状归一：{} → 命中 {}，否认 {}", text,
                    hits.values().stream().map(n -> n.getMatchedText() + "=" + n.getSymptomName()).toList(),
                    denied.values().stream().map(n -> n.getMatchedText() + "=" + n.getSymptomName()).toList());
        }
        return new Normalized(new ArrayList<>(hits.values()), new ArrayList<>(denied.values()));
    }

    /**
     * 取词表，必要时重新加载。
     *
     * <p>加载失败时退回上一次的缓存而不是抛异常：归一化失败只该让这一轮
     * 少一点上下文，不该让用户连回复都收不到。缓存为空时才真的放弃。
     */
    private List<SymptomVocabularyRow> vocabulary() {
        long now = System.currentTimeMillis();
        if (!cache.isEmpty() && now - loadedAt < CACHE_TTL_MS) {
            return cache;
        }
        try {
            var rows = symptomMapper.selectVocabulary();
            cache = rows == null ? List.of() : rows.stream().sorted(LONGEST_FIRST).toList();
            loadedAt = now;
            log.info("症状词表已加载：{} 条说法（含别名）", cache.size());
        } catch (Exception e) {
            log.warn("症状词表加载失败，沿用上一次的 {} 条", cache.size(), e);
        }
        return cache;
    }

    /** 强制下一次调用重新加载词表（录入新别名后可用，不必等 TTL）。 */
    public void invalidate() {
        loadedAt = 0L;
    }

    private static NormalizedSymptom toNormalized(SymptomVocabularyRow row, String matchedText) {
        return new NormalizedSymptom(
                row.getSymptomId(),
                row.getSymptomName(),
                row.getBodyPart(),
                row.getCategory(),
                matchedText,
                weightOf(row));
    }

    private static boolean overlaps(boolean[] taken, int start, int end) {
        for (int i = start; i < end; i++) {
            if (taken[i]) {
                return true;
            }
        }
        return false;
    }

    private static void mark(boolean[] taken, int start, int end) {
        for (int i = start; i < end; i++) {
            taken[i] = true;
        }
    }

    private static int length(String s) {
        return s == null ? 0 : s.length();
    }

    private static BigDecimal weightOf(SymptomVocabularyRow row) {
        return row.getWeight() == null ? BigDecimal.ONE : row.getWeight();
    }

    private static BigDecimal weightOf(NormalizedSymptom s) {
        return s.getWeight() == null ? BigDecimal.ONE : s.getWeight();
    }
}
