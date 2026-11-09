package com.mediquery.module.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mediquery.module.knowledge.dto.SymptomHit;
import com.mediquery.module.knowledge.entity.DiseaseSymptom;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DiseaseSymptomMapper extends BaseMapper<DiseaseSymptom> {

    /**
     * 命中症状 → 反向查「哪些疾病把这些症状当自己的表现」，并带出疾病本体。
     *
     * <p>这是「两跳」里的第二跳。第一跳在内存里完成（用户的话 → 症状 ID），
     * 第二跳交回数据库：用症状 ID 集合去捞疾病，并让数据库把
     * {@code weight} 做一次聚合，返回候选集。
     *
     * <p>聚合口径：{@code SUM(weight)} 作为粗排分。同一个症状在多个疾病上
     * 都有 weight，所以一句「咳嗽伴黄痰」命中的疾病里，
     * 把咳嗽当核心症状的那个必然分数更高。
     *
     * <p>红旗症状单独用 {@code MAX(is_red_flag)} 带出来，不参与打分 ——
     * 红旗是「要不要立刻就医」的判断，不是「更像哪种病」的判断，
     * 混进分数里会让危急情况被高分但无关的病压下去。
     *
     * @param symptomIds 命中的症状 ID 集合，调用方保证非空
     * @param limit      候选上限，留足内存重排的空间
     */
    @Select("""
            <script>
            SELECT i.id              AS illness_id,
                   i.name            AS illness_name,
                   i.category_id     AS category_id,
                   i.cause           AS cause,
                   i.symptom         AS symptom,
                   i.special_symptom AS special_symptom,
                   SUM(ds.weight)    AS score,
                   MAX(ds.is_red_flag) AS has_red_flag,
                   SUM(CASE WHEN ds.is_cardinal = 1 THEN 1 ELSE 0 END) AS cardinal_hits
              FROM disease_symptom ds
              JOIN illness i ON i.id = ds.illness_id
             WHERE i.deleted = 0
               AND i.status = 1
               AND ds.symptom_id IN
               <foreach collection="symptomIds" item="sid" open="(" separator="," close=")">
                 #{sid}
               </foreach>
             GROUP BY i.id, i.name, i.category_id, i.cause, i.symptom, i.special_symptom
             ORDER BY score DESC, cardinal_hits DESC, i.id ASC
             LIMIT #{limit}
            </script>
            """)
    List<SymptomHit> selectIllnessesBySymptoms(@Param("symptomIds") List<Integer> symptomIds,
                                               @Param("limit") int limit);
}
