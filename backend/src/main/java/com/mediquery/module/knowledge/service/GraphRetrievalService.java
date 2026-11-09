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
 * <p>三跳各自的产出：
 * <ol>
 *   <li>症状 → 疾病：带 {@code SUM(weight)} 粗排分和命中核心症状数；
 *       红旗用 {@code MAX(is_red_flag)} 单独带出，<b>不混进分数</b>；</li>
 *   <li>疾病 → 科室：带 {@code priority}，第一个就是该先去的科；</li>
 *   <li>疾病 → 用药：带 {@code indication / line_of_treatment / evidence_level}，
 *       让「阿莫西林」这种必须带条件的药没法裸奔出现。</li>
 * </ol>
 */
@Service
public class GraphRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(GraphRetrievalService.class);

    /** 数据库侧候选上限。比最终 Top3 大得多，好让内存重排有得挑。 */
    private static final int CANDIDATE_LIMIT = 20;
    /** 最终注入上下文的疾病数 */
    public static final int TOP_K = 3;

    private final SymptomNormalizer normalizer;
    private final DiseaseSymptomMapper diseaseSymptomMapper;
    private final DiseaseDepartmentMapper departmentMapper;
    private final DiseaseMedicineMapper diseaseMedicineMapper;

    public GraphRetrievalService(SymptomNormalizer normalizer,
                                 DiseaseSymptomMapper diseaseSymptomMapper,
                                 DiseaseDepartmentMapper departmentMapper,
                                 DiseaseMedicineMapper diseaseMedicineMapper) {
        this.normalizer = normalizer;
        this.diseaseSymptomMapper = diseaseSymptomMapper;
        this.departmentMapper = departmentMapper;
        this.diseaseMedicineMapper = diseaseMedicineMapper;
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
     */
    public record Result(List<NormalizedSymptom> symptoms, List<Entry> entries) {

        public static Result empty() {
            return new Result(List.of(), List.of());
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

        List<SymptomHit> candidates;
        try {
            var ids = symptoms.stream().map(NormalizedSymptom::getSymptomId).toList();
            candidates = diseaseSymptomMapper.selectIllnessesBySymptoms(ids, CANDIDATE_LIMIT);
        } catch (Exception e) {
            log.warn("疾病召回失败，本轮跳过图谱检索", e);
            return Result.empty();
        }
        if (candidates == null || candidates.isEmpty()) {
            return Result.empty();
        }

        var top = candidates.stream().limit(TOP_K).toList();
        var illnessIds = top.stream().map(SymptomHit::getIllnessId).toList();

        // 科室与用药一次批量取回，避免每个病各查两次
        Map<Integer, List<DiseaseDepartment>> departments = groupDepartments(illnessIds);
        Map<Integer, List<DiseaseMedicine>> medicines = groupMedicines(illnessIds);

        var entries = new ArrayList<Entry>(top.size());
        for (var hit : top) {
            entries.add(new Entry(hit,
                    departments.getOrDefault(hit.getIllnessId(), List.of()),
                    medicines.getOrDefault(hit.getIllnessId(), List.of())));
        }
        log.debug("图谱检索命中：症状={}，疾病={}", symptoms.size(), entries.size());
        return new Result(symptoms, entries);
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
