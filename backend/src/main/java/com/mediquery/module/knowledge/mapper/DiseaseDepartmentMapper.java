package com.mediquery.module.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mediquery.module.knowledge.entity.DiseaseDepartment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DiseaseDepartmentMapper extends BaseMapper<DiseaseDepartment> {

    /**
     * 按疾病批量取科室，按优先级升序。
     *
     * <p>批量而不是逐个查：候选疾病最多几十个，逐个查就是几十次往返。
     * 返回的行带 {@code illnessId}，调用方自己在内存里分组。
     */
    @Select("""
            <script>
            SELECT dd.* FROM disease_department dd
             WHERE dd.illness_id IN
             <foreach collection="illnessIds" item="iid" open="(" separator="," close=")">
               #{iid}
             </foreach>
             ORDER BY dd.illness_id, dd.priority ASC
            </script>
            """)
    List<DiseaseDepartment> selectByIllnessIds(@Param("illnessIds") List<Integer> illnessIds);
}
