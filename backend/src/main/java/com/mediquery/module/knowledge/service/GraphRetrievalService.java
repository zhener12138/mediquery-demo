package com.mediquery.module.knowledge.service;

import com.mediquery.module.knowledge.entity.DiseaseDepartment;
import com.mediquery.module.knowledge.dto.DiseaseMedicine;
import com.mediquery.module.knowledge.dto.NormalizedSymptom;
import com.mediquery.module.knowledge.dto.SymptomHit;
import com.mediquery.module.knowledge.mapper.DiseaseDepartmentMapper;
import com.mediquery.module.knowledge.mapper.DiseaseMedicineMapper;
import com.mediquery.module.knowledge.mapper.DiseaseSymptomMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图谱检索：症状 → 疾病 → 科室/用药 的两跳召回。
 *
 * <p>与老的 {@link com.mediquery.integration.ai.RetrievalStrategy} 的区别不是「快慢」，
 * 是<b>能不能回答</b>：
 * <ul>
 *   <li>老路径是一条 SQL：拿切出来的 2-4 字碎片去 {@code MATCH} 疾病表的顿号串。
 *       用户说「睡不着」，切出「睡不」「不着」，疾病表里写的是「失眠」——
 *       两边根本没有共同的字符串，召回 0 条是必然结果；</li>
 *   <li>这条路先做<b>归一</b>（「睡不着」→ 症状「失眠」），再用症状 ID 去查
 *       「哪些病把失眠当自己的表现」，最后顺着边取科室和用药。
 *       口语怎么变，只要录了别名就能命中。</li>
 * </ul>
 *
 * <h3>打分：为什么不是 {@code SUM(weight)}</h3>
 * <p>「发热」挂在知识库里大半的疾病上，「骨擦音」只挂在骨折上。
 * 两者命中时给出的信息量差着一个数量级，而 {@code SUM(weight)}
 * 把它们当成一样值钱。代价在只有 10 个病时看不出来 ——
 * 每个症状最多命中三五个病，怎么排都还像话；病一多，
 * 最普遍的那个症状就会把它命中的所有病一起顶到前排。
 *
 * <p>所以真正的排序交给 {@link DiseaseScorer}：按每个症状自己的
 * 文档频率算 idf，再乘该条边的 weight 求和。SQL 只负责
 * 「哪些病沾边」和把边原样带回来。
 *
 * <p>红旗用 {@code MAX(is_red_flag)} 单独带出，<b>不混进分数</b> ——
 * 红旗是「要不要立刻就医」的判断，不是「更像哪种病」的判断，
 * 混进分数里会让危急情况被高分但无关的病压下去。
 */
@Service
public class GraphRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(GraphRetrievalService.class);

    /** 最终注入上下文的疾病数 */
    public static final int TOP_K = 3;

    private final SymptomNormalizer normalizer;
    private final DiseaseSymptomMapper diseaseSymptomMapper;
    private final DiseaseDepartmentMapper departmentMapper;
    private final DiseaseMedicineMapper diseaseMedicineMapper;
    private final FollowUpQuestionService followUpQuestionService;

    public GraphRetrievalService(SymptomNormalizer normalizer,
                                 DiseaseSymptomMapper diseaseSymptomMapper,
                                 DiseaseDepartmentMapper departmentMapper,
                                 DiseaseMedicineMapper diseaseMedicineMapper,
                                 FollowUpQuestionService followUpQuestionService) {
        this.normalizer = normalizer;
        this.diseaseSymptomMapper = diseaseSymptomMapper;
        this.departmentMapper = departmentMapper;
        this.diseaseMedicineMapper = diseaseMedicineMapper;
        this.followUpQuestionService = followUpQuestionService;
    }

    /** 一个候选疾病，连同它的科室与用药边。 */
    public record Entry(SymptomHit disease,
                        List<DiseaseDepartment> departments,
                        List<DiseaseMedicine> medicines) {

        /** 排在最前面的科室，没有时为 null。 */
        public String primaryDepartment() {
            return departments.isEmpty() ? null : departments.get(0).getDepartment();
        }
    }

    /**
     * 检索结果。
     *
     * <p>把归一出来的症状一起带出来，不是为了好看：上下文里写一句
     * 「本轮识别到的症状：失眠（你说的是"睡不着"）」，模型的措辞会立刻变得具体，
     * 而用户也能看出系统有没有听懂 —— 听不懂的时候，前端至少能显示它听成了什么。
     *
     * <p>{@code questions} 是「再问一句就能定案」的问题，不是知识。
     * 它和疾病列表是两种东西：疾病是「我猜你可能是这几个」，
     * 问题是「告诉我这一句，我就能猜到一两个」。混在一起写，模型会把两类
     * 都当成结论念出来。
     */
    public record Result(List<NormalizedSymptom> symptoms,
                         List<Entry> entries,
                         List<FollowUpQuestionService.Question> questions) {

        public static Result empty() {
            return new Result(List.of(), List.of(), List.of());
        }

        public boolean isEmpty() {
            return entries.isEmpty();
        }
    }

    /**
     * 跑完整条链路。
     *
     * <p>任何一层失败都退化成「这一轮没有图谱上下文」，不抛异常：
     * 知识库挂了不该让用户连回复都收不到。老的关键词检索仍然会兜底，
     * 所以最坏情况就是回到改造前的样子。
     */
    public Result retrieve(String text) {
        List<NormalizedSymptom> symptoms;
        try {
            symptoms = normalizer.normalize(text);
        } catch (Exception e) {
            log.warn("症状归一化失败，本轮跳过图谱检索", e);
            return Result.empty();
        }
        if (symptoms.isEmpty()) {
            return Result.empty();
        }

        var ids = symptoms.stream().map(NormalizedSymptom::getSymptomId).toList();

        List<SymptomHit> ranked;
        try {
            var edges = diseaseSymptomMapper.selectEdgesBySymptoms(ids);
            if (edges == null || edges.isEmpty()) {
                return Result.empty();
            }
            ranked = DiseaseScorer.score(edges, idfOf(ids), TOP_K);
        } catch (Exception e) {
            log.warn("疾病召回或打分失败，本轮跳过图谱检索", e);
            return Result.empty();
        }
        if (ranked.isEmpty()) {
            return Result.empty();
        }

        var illnessIds = ranked.stream().map(SymptomHit::getIllnessId).toList();

        // 科室与用药一次批量取回，避免每个病各查两次
        Map<Integer, List<DiseaseDepartment>> departments = groupDepartments(illnessIds);
        Map<Integer, List<DiseaseMedicine>> medicines = groupMedicines(illnessIds);

        var entries = new ArrayList<Entry>(ranked.size());
        for (var hit : ranked) {
            entries.add(new Entry(hit,
                    departments.getOrDefault(hit.getIllnessId(), List.of()),
                    medicines.getOrDefault(hit.getIllnessId(), List.of())));
        }

        var questions = pickQuestions(illnessIds, symptoms);
        log.debug("图谱检索命中：症状={}，疾病={}，追问={}", symptoms.size(), entries.size(), questions.size());
        return new Result(symptoms, entries, questions);
    }

    /**
     * 挑出能分开这几个候选的问题。
     *
     * <p>查的是候选疾病的<b>全部</b>症状 —— 包括用户没提的。
     * 只拿用户提过的去比，能问出来的一定是「你还疼不疼」这种重复确认；
     * 要看「扁桃体炎写了吞咽困难而感冒没写」，才问得出真正有区分度的那句。
     *
     * <p>这一步失败不值得让整轮检索失败：没有追问，只是回复不够聪明。
     */
    private List<FollowUpQuestionService.Question> pickQuestions(List<Integer> illnessIds,
                                                                 List<NormalizedSymptom> symptoms) {
        if (illnessIds.size() < 2) {
            // 只有一个候选，没什么可鉴别的
            return List.of();
        }
        try {
            var views = diseaseSymptomMapper.selectSymptomsByIllnessIds(illnessIds);
            return followUpQuestionService.pickQuestions(views, symptoms);
        } catch (Exception e) {
            log.warn("挑选追问失败 illnessIds={}，跳过", illnessIds, e);
            return List.of();
        }
    }

    /**
     * 算本轮命中症状各自的区分度。
     *
     * <p>IDF 查失败就全体按 1.0 走 —— 那等于退回原来的
     * {@code SUM(weight)}。这比整轮检索失败好：排序不够聪明，
     * 但至少答案还在。
     */
    private Map<Integer, Double> idfOf(List<Integer> symptomIds) {
        try {
            int total = diseaseSymptomMapper.countPublishedDiseases();
            var dfs = diseaseSymptomMapper.selectSymptomDf(symptomIds);
            return DiseaseScorer.idf(total, dfs);
        } catch (Exception e) {
            log.warn("统计症状文档频率失败，本轮退回等权打分", e);
            return Map.of();
        }
    }

    private Map<Integer, List<DiseaseDepartment>> groupDepartments(List<Integer> illnessIds) {
        Map<Integer, List<DiseaseDepartment>> map = new LinkedHashMap<>();
        try {
            var rows = departmentMapper.selectByIllnessIds(illnessIds);
            if (rows != null) {
                for (var row : rows) {
                    map.computeIfAbsent(row.getIllnessId(), k -> new ArrayList<>()).add(row);
                }
            }
        } catch (Exception e) {
            // 科室缺失只是让建议少一条，不值得让整轮检索失败
            log.warn("补科室失败 illnessIds={}，跳过", illnessIds, e);
        }
        return map;
    }

    private Map<Integer, List<DiseaseMedicine>> groupMedicines(List<Integer> illnessIds) {
        Map<Integer, List<DiseaseMedicine>> map = new LinkedHashMap<>();
        try {
            var rows = diseaseMedicineMapper.selectByIllnessIds(illnessIds);
            if (rows != null) {
                for (var row : rows) {
                    map.computeIfAbsent(row.getIllnessId(), k -> new ArrayList<>()).add(row);
                }
            }
        } catch (Exception e) {
            log.warn("补用药边失败 illnessIds={}，跳过", illnessIds, e);
        }
        return map;
    }
}
