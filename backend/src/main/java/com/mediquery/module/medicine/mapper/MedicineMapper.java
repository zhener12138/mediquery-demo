package com.mediquery.module.medicine.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mediquery.module.medicine.entity.Medicine;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MedicineMapper extends BaseMapper<Medicine> {

    /**
     * 全文检索召回，对应索引 {@code ft_medicine (name, keyword, effect)}。
     *
     * <p>{@code keyword} 是列名也是 SQL 关键字（{@code KEY} 才是保留字，但为了不给解析器
     * 留任何歧义，列名一律加反引号）。{@code MATCH} 的列清单必须与索引完全一致，多一列少一列
     * 都会报 {@code Can't find FULLTEXT index matching the column list}。
     *
     * <p>其余说明同 {@code IllnessMapper.searchByFulltext}：相关度排序、tie-breaker 保可复现、
     * 手写 {@code deleted = 0} 因为 {@code @Select} 绕过了逻辑删除自动追加。
     */
    @Select("""
            SELECT * FROM medicine
            WHERE deleted = 0 AND status = 1
              AND MATCH(`name`, `keyword`, `effect`) AGAINST(#{expr} IN BOOLEAN MODE)
            ORDER BY MATCH(`name`, `keyword`, `effect`) AGAINST(#{expr} IN BOOLEAN MODE) DESC,
                     id DESC
            LIMIT #{limit}
            """)
    List<Medicine> searchByFulltext(@Param("expr") String expr, @Param("limit") int limit);
}
