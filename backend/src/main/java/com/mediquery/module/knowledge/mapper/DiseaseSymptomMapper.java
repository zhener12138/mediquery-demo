package com.mediquery.module.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mediquery.module.knowledge.dto.DiseaseSymptomEdge;
import com.mediquery.module.knowledge.dto.DiseaseSymptomView;
import com.mediquery.module.knowledge.dto.SymptomDf;
import com.mediquery.module.knowledge.entity.DiseaseSymptom;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DiseaseSymptomMapper extends BaseMapper<DiseaseSymptom> {

    /**
     * 命中症状 → 反向查「哪些疾病把这些症状当自己的表现」，<b>每条边一行</b>。
     *
     * <p>返回的是边而不是聚合后的分数，因为打分要按
     * 「每条边自己的 weight × 该症状自己的 idf」来算。
     * 在 SQL 里 {@code SUM} 完再乘 idf 是错的：{@code SUM(w) * idf} 与
     * {@code SUM(w * idf)} 只在所有 idf 相同时才相等，而这里的重点恰恰是
     * 不同症状的 idf 差着一个数量级。
     *
     * <p>这里只做筛选（哪些病沾边）与排序兜底，真正的打分在
     * {@code GraphRetrievalService} 里做。
     *
     * @param symptomIds 命中的症状 ID 集合，调用方保证非空
     */
    @Select("""
            <script>
            SELECT i.id              AS illness_id,
                   i.name            AS illness_name,
                   i.category_id     AS category_id,
                   i.cause           AS cause,
                   i.symptom         AS symptom,
                   i.special_symptom AS special_symptom,
                   ds.symptom_id     AS symptom_id,
                   ds.weight         AS weight,
                   ds.is_cardinal    AS is_cardinal,
                   ds.is_red_flag    AS is_red_flag
              FROM disease_symptom ds
              JOIN illness i ON i.id = ds.illness_id
             WHERE i.deleted = 0
               AND i.status = 1
               AND ds.symptom_id IN
               <foreach collection="symptomIds" item="sid" open="(" separator="," close=")">
                 #{sid}
               </foreach>
             ORDER BY i.id ASC, ds.weight DESC
            </script>
            """)
    List<DiseaseSymptomEdge> selectEdgesBySymptoms(@Param("symptomIds") List<Integer> symptomIds);

    /**
     * 已发布疾病总数。IDF 的分母。
     *
     * <p>过滤条件必须与 {@link #selectEdgesBySymptoms} 完全一致
     * （{@code deleted = 0 AND status = 1}），否则分母里混进了草稿病，
     * 每个症状的 IDF 都会偏大一点点 —— 单看没错，合起来排序就歪了。
     */
    @Select("SELECT COUNT(*) FROM illness WHERE deleted = 0 AND status = 1")
    int countPublishedDiseases();

    /**
     * 指定症状各自被多少个已发布疾病引用。IDF 的分子。
     *
     * <p>只查本轮命中的这几个症状，不是整表扫 —— 用户一句话最多归一
     * 出五六个症状，全表算 DF 是白花的开销。
     */
    @Select("""
            <script>
            SELECT ds.symptom_id AS symptom_id,
                   COUNT(DISTINCT ds.illness_id) AS df
              FROM disease_symptom ds
              JOIN illness i ON i.id = ds.illness_id
             WHERE i.deleted = 0
               AND i.status = 1
               AND ds.symptom_id IN
               <foreach collection="symptomIds" item="sid" open="(" separator="," close=")">
                 #{sid}
               </foreach>
             GROUP BY ds.symptom_id
            </script>
            """)
    List<SymptomDf> selectSymptomDf(@Param("symptomIds") List<Integer> symptomIds);

    /**
     * 指定疾病的<b>全部</b>症状表现，含症状名。
     *
     * <p>这是主动追问的数据来源。要问「有没有吞咽困难」，系统得先知道
     * 扁桃体炎写了吞咽困难而病毒性感冒没写 —— 只查用户提过的症状，
     * 就永远只能复述用户自己说过的话。
     *
     * <p>查询把候选疾病的边全捞回来（含用户没提的），由
     * {@code FollowUpQuestionService} 去算「哪个症状能把它们分开」。
     *
     * @param illnessIds 候选疾病 ID，调用方保证非空
     */
    @Select("""
            <script>
            SELECT ds.illness_id  AS illness_id,
                   i.name         AS illness_name,
                   ds.symptom_id  AS symptom_id,
                   s.name         AS symptom_name,
                   ds.is_cardinal AS is_cardinal,
                   ds.weight      AS weight
              FROM disease_symptom ds
              JOIN illness i ON i.id = ds.illness_id
              JOIN symptom s ON s.id = ds.symptom_id
             WHERE i.deleted = 0
               AND i.status = 1
               AND s.status = 1
               AND ds.illness_id IN
               <foreach collection="illnessIds" item="iid" open="(" separator="," close=")">
                 #{iid}
               </foreach>
             ORDER BY ds.illness_id ASC, ds.is_cardinal DESC, ds.weight DESC
            </script>
            """)
    List<DiseaseSymptomView> selectSymptomsByIllnessIds(@Param("illnessIds") List<Integer> illnessIds);
}
