package com.mediquery.module.knowledge.mapper;

import com.mediquery.module.knowledge.dto.DiseaseMedicine;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DiseaseMedicineMapper {

    /**
     * 按疾病批量取「带条件的用药边」，一次 JOIN 把药名和三个条件字段一起捞回来。
     *
     * <p>排序按 {@code line_of_treatment} 升序（未分级 0 排在前面？不是 —— 见下）。
     * 实际上 0 代表「未分级」，语义上不该排在一线前面，
     * 所以用 {@code CASE} 把 0 折算成 9：明确的一线永远优先，
     * 没写等级的排在最后。这比在 Java 里排序省一次遍历，也让 SQL 读起来
     * 就知道「没写等级的往后站」。
     *
     * <p>只取未删除、已上架的药品，与老的 {@code findMedicinesByIllnessId} 口径一致。
     */
    @Select("""
            <script>
            SELECT imr.illness_id            AS illness_id,
                   m.id                      AS medicine_id,
                   m.name                    AS medicine_name,
                   m.effect                  AS effect,
                   m.usage_dosage            AS usage_dosage,
                   m.contraindication        AS contraindication,
                   m.interaction             AS interaction,
                   imr.indication            AS indication,
                   imr.line_of_treatment     AS line_of_treatment,
                   imr.evidence_level        AS evidence_level
              FROM illness_medicine_rel imr
              JOIN medicine m ON m.id = imr.medicine_id
             WHERE m.deleted = 0
               AND m.status = 1
               AND imr.illness_id IN
               <foreach collection="illnessIds" item="iid" open="(" separator="," close=")">
                 #{iid}
               </foreach>
             ORDER BY imr.illness_id,
                      CASE WHEN imr.line_of_treatment = 0 THEN 9 ELSE imr.line_of_treatment END,
                      m.id
            </script>
            """)
    List<DiseaseMedicine> selectByIllnessIds(@Param("illnessIds") List<Integer> illnessIds);
}
