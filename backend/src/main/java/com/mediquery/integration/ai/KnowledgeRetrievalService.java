package com.mediquery.integration.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.mediquery.module.illness.entity.Illness;
import com.mediquery.module.illness.mapper.IllnessMedicineRelMapper;
import com.mediquery.module.knowledge.dto.NormalizedSymptom;
import com.mediquery.module.knowledge.entity.DiseaseDepartment;
import com.mediquery.module.knowledge.dto.DiseaseMedicine;
import com.mediquery.module.knowledge.service.GraphRetrievalService;
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
    private final IllnessMedicineRelMapper relMapper;
    private final GraphRetrievalService graphRetrievalService;

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrievalService.class);

    public KnowledgeRetrievalService(RetrievalStrategy retrievalStrategy,
                                     IllnessMedicineRelMapper relMapper,
                                     GraphRetrievalService graphRetrievalService) {
        this.retrievalStrategy = retrievalStrategy;
        this.relMapper = relMapper;
        this.graphRetrievalService = graphRetrievalService;
    }

    /**
     * 检索产物：不只是喂给模型的文本，还有「到底参考了哪几条」。
     * <p>以前只返回 String，命中的条目名在拼进上下文的那一刻就丢了 ——
     * 想在前端标一句「参考了：病毒性感冒」都做不到，用户只能看到一段没有出处的黑盒回答。
     */
    public record Retrieved(String context, List<String> sources, List<String> departments) {

        /** 老调用方（只关心文本和来源）的快捷构造 */
        public Retrieved(String context, List<String> sources) {
            this(context, sources, List.of());
        }
    }

    /**
     * 根据用户输入检索相关知识，格式化为上下文文本。
     * 返回空字符串表示未找到匹配。
     */
    public String retrieve(String userMessage) {
        return retrieveWithSources(userMessage).context();
    }

    /** 同 {@link #retrieve(String)}，但额外带出命中的条目名（疾病名 + 药品名），供前端做来源标注。 */
    public Retrieved retrieveWithSources(String userMessage) {
        if (!StringUtils.hasText(userMessage)) return new Retrieved("", List.of());

        // 先走图谱：症状归一 → 疾病 → 科室/带条件的用药。
        // 这条路能回答「睡不着」这类老链路注定零命中的说法（详见 GraphRetrievalService 的类注释）。
        var graph = retrieveFromGraph(userMessage);
        if (!graph.isEmpty()) return formatGraph(graph);

        // 图谱没命中，说明用户说的症状还没进词表。这时退回关键词检索 ——
        // 宁可给一条粗一点的上下文，也不要因为知识库没录全就什么都不给。
        var keywords = tokenize(userMessage);
        if (keywords.isEmpty()) return new Retrieved("", List.of());

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

        // 名称去重（同名疾病/药品理论上只有一条，但 LinkedHashSet 防御性地保序去重）
        var sources = new LinkedHashSet<String>();
        for (var i : illnesses) sources.add(i.getName());
        for (var m : medicines) sources.add(m.getName());
        return new Retrieved(context.toString(), List.copyOf(sources));
    }

    /**
     * 走图谱检索，失败了就当没命中。
     *
     * <p>这一个 try 是整条链路的保险丝：知识库表缺失、SQL 语法错、数据库连不上 ——
     * 任何一种都只会让这一轮少一点上下文，绝不会让用户收不到回复。
     * 老的关键词检索还在后面兜底，所以最坏情况就是回到改造前的水平。
     */
    private GraphRetrievalService.Result retrieveFromGraph(String userMessage) {
        try {
            return graphRetrievalService.retrieve(userMessage);
        } catch (Exception e) {
            log.warn("图谱检索异常，退回关键词检索", e);
            return GraphRetrievalService.Result.empty();
        }
    }

    /**
     * 把图谱结果排成给模型看的上下文。
     *
     * <p>与 {@link #formatIllness} 的差别，逐条都是为了「少说错话」：
     * <ul>
     *   <li><b>先说识别到了什么症状</b>，并回显用户原话。模型会照着这个措辞复述
     *       （「你说的睡不着，对应失眠」），用户立刻知道系统听懂了没有；</li>
     *   <li><b>科室带优先级</b>：门诊建议只给第一个，其余写「也可考虑」。
     *       平铺五个科室让用户自己挑，等于没建议；</li>
     *   <li><b>用药必须带条件</b>：没有 indication 的药直接不输出。这条最关键 ——
     *       阿莫西林三个字裸奔出去，就是在暗示「感冒吃这个」；</li>
     *   <li><b>红旗单独列</b>：不进主结论，单独一段「这些情况要立刻就医」，
     *       免得模型把它当成鉴别诊断的一部分。</li>
     * </ul>
     */
    private Retrieved formatGraph(GraphRetrievalService.Result result) {
        var sb = new StringBuilder();

        var symptoms = result.symptoms();
        if (!symptoms.isEmpty()) {
            sb.append("【本轮识别到的症状】\n");
            for (var s : symptoms) {
                sb.append("- ").append(s.getSymptomName());
                // 只有在用户说的不是标准名时才回显，否则会写出「失眠（你说的是失眠）」
                if (s.getMatchedText() != null && !s.getMatchedText().equals(s.getSymptomName())) {
                    sb.append("（你说的是「").append(s.getMatchedText()).append("」）");
                }
                if (StringUtils.hasText(s.getBodyPart())) {
                    sb.append(" · 部位：").append(s.getBodyPart());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        sb.append("【参考知识库 · 可能相关的疾病】\n");
        var sources = new LinkedHashSet<String>();
        var departmentHints = new LinkedHashSet<String>();
        for (var entry : result.entries()) {
            var primary = entry.primaryDepartment();
            if (StringUtils.hasText(primary)) departmentHints.add(primary);
            var d = entry.disease();
            sb.append("## 疾病：").append(d.getIllnessName()).append("\n");
            sources.add(d.getIllnessName());
            if (StringUtils.hasText(d.getCause()))
                sb.append("- 病因：").append(d.getCause()).append("\n");
            if (StringUtils.hasText(d.getSymptom()))
                sb.append("- 典型表现：").append(d.getSymptom()).append("\n");
            if (StringUtils.hasText(d.getSpecialSymptom()))
                sb.append("- 特殊说明：").append(d.getSpecialSymptom()).append("\n");
            appendDepartments(sb, entry.departments());
            appendMedicines(sb, entry.medicines());
            sb.append("\n");
        }

        var redFlags = collectRedFlagDiseases(result);
        if (!redFlags.isEmpty()) {
            sb.append("【需要立即就医的提示】\n");
            sb.append("- 以下疾病在你的描述里出现了「红旗症状」：")
              .append(String.join("、", redFlags)).append("\n");
            sb.append("- 红旗症状的意思是「这个表现本身就该马上看医生」，"
                      + "不代表一定得了这些病。请优先排除紧急情况。\n\n");
        }

        return new Retrieved(sb.toString(), List.copyOf(sources), List.copyOf(departmentHints));
    }

    private void appendDepartments(StringBuilder sb, List<DiseaseDepartment> departments) {
        if (departments == null || departments.isEmpty()) return;
        var first = departments.get(0);
        sb.append("- 建议科室：").append(first.getDepartment());
        if (StringUtils.hasText(first.getReason())) {
            sb.append("（").append(first.getReason()).append("）");
        }
        sb.append("\n");
        if (departments.size() > 1) {
            var others = departments.stream().skip(1)
                    .map(DiseaseDepartment::getDepartment)
                    .collect(Collectors.joining("、"));
            sb.append("- 也可考虑：").append(others).append("\n");
        }
    }

    /**
     * 用药边：没有适应症说明的药一律不输出。
     *
     * <p>这不是保守，是纠错。改造前 {@code illness_medicine_rel} 只有两个外键，
     * 于是「阿莫西林 → 病毒性感冒」这条边在库里长得和一条正确边一模一样，
     * 模型照着念就是在给病毒感染推抗生素。加一列 {@code indication} 之后，
     * 写不出「什么情况下才用」的边自然就消失了 —— 补不出来，就说明它本来就不该存在。
     */
    private void appendMedicines(StringBuilder sb, List<DiseaseMedicine> medicines) {
        if (medicines == null || medicines.isEmpty()) return;
        var usable = medicines.stream()
                .filter(m -> StringUtils.hasText(m.getIndication()))
                .toList();
        if (usable.isEmpty()) {
            sb.append("- 用药提示：知识库里没有针对本病的明确用药建议，不要自行服药\n");
            return;
        }
        for (var m : usable) {
            sb.append("- 用药提示：").append(m.getMedicineName())
              .append(" —— ").append(m.getIndication());
            var line = lineLabel(m.getLineOfTreatment());
            if (line != null) sb.append("；").append(line);
            sb.append("\n");
        }
    }

    /** 治疗线转成人话。0 / null 表示未分级，不输出，免得模型把「未分级」念成「随便用」。 */
    private static String lineLabel(Integer lineOfTreatment) {
        if (lineOfTreatment == null) return null;
        return switch (lineOfTreatment) {
            case 1 -> "一线用药";
            case 2 -> "二线用药";
            case 3 -> "需医生评估后考虑";
            default -> null;
        };
    }

    private static List<String> collectRedFlagDiseases(GraphRetrievalService.Result result) {
        var out = new ArrayList<String>();
        for (var entry : result.entries()) {
            if (Boolean.TRUE.equals(entry.disease().getHasRedFlag())) {
                out.add(entry.disease().getIllnessName());
            }
        }
        return out;
    }

    /** 批量召回后按相关度取 Top3 */
    private List<Illness> rankIllnesses(List<String> keywords) {
        var candidates = retrievalStrategy.recallIllnesses(keywords, RECALL_LIMIT);
        var ranked = candidates.stream()
                .map(i -> new Scored<>(i, scoreIllness(i, keywords)))
                .filter(s -> s.score > 0)
                .sorted(Comparator.comparingInt((Scored<Illness> s) -> s.score).reversed())
                .limit(TOP_K)
                .map(s -> s.item)
                .collect(Collectors.toList());

        // 只给最终入选的 Top3 补关联药品，不是给整个 50 条召回窗口补：
        // 排名之外的条目根本不会进上下文，逐条查关联表纯属浪费 SQL。
        for (var illness : ranked) attachMedicines(illness);
        return ranked;
    }

    /**
     * 补关联药品。
     *
     * <p>{@code Illness.medicines} 是 {@code @TableField(exist = false)} 的关联字段，
     * 召回拿到的实体里它永远是 null。以前只有
     * {@link com.mediquery.module.illness.service.IllnessService#getById} 会去查关联表，
     * 而它挂在「疾病详情页」上 —— 详情页随重构删掉了，于是 {@link #formatIllness}
     * 里那句「关联药品」在生产路径上从来没输出过，模型拿到的永远是半条知识。
     *
     * <p>语义是「这一篇疾病通常用什么药」，给模型做判断参考，不构成用药推荐。
     */
    private void attachMedicines(Illness illness) {
        if (illness == null || illness.getId() == null || illness.getMedicines() != null) return;
        try {
            var medicines = relMapper.findMedicinesByIllnessId(illness.getId());
            illness.setMedicines(medicines == null ? List.of() : medicines);
        } catch (Exception e) {
            // 关联表查失败不该让整轮问诊挂掉：没有关联药品只是少一点上下文，
            // 而抛出去会让用户连回复都收不到。
            log.warn("补关联药品失败 illnessId={}，跳过", illness.getId(), e);
            illness.setMedicines(List.of());
        }
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
        // 科室名来自 illness_category 的回填（IllnessService#fillCategoryNames）。
        // 有了这一行，模型建议科室时是照着真实存在过的科室说，而不是自由发挥。
        if (StringUtils.hasText(i.getCategoryName()))
            sb.append("- 常见科室：").append(i.getCategoryName()).append("\n");
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
