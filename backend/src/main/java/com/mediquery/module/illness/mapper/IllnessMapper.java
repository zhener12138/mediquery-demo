package com.mediquery.module.illness.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mediquery.module.illness.entity.Illness;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface IllnessMapper extends BaseMapper<Illness> {

    /**
     * 全文检索召回：一条 SQL 替代「每个关键词 × 每个字段」的 OR LIKE 拼接。
     *
     * <p>索引是 {@code ft_illness (name, cause, symptom) WITH PARSER ngram}，
     * 所以 {@code MATCH} 的列必须与索引列完全一致 —— MySQL 要求存在列清单精确匹配的
     * 全文索引，拆成 {@code MATCH(name)} 会报
     * {@code Can't find FULLTEXT index matching the column list}。
     *
     * <p>排序用相关度而不是 {@code view_count}：带 LIMIT 的召回先让「更相关的」进入窗口，
     * 后面的内存精排（名称命中 ×3）才有东西可挑。{@code view_count, id} 作为 tie-breaker
     * 保证同分时结果可复现。
     *
     * <p>逻辑删除条件要手写：{@code @Select} 绕过了 MyBatis-Plus 的
     * {@code @TableLogic} 自动追加。
     *
     * @param expr  {@link com.mediquery.integration.ai.MatchExpression} 生成的布尔模式表达式
     * @param limit 召回上限
     */
    @Select("""
            SELECT * FROM illness
            WHERE deleted = 0 AND status = 1
              AND MATCH(name, cause, symptom) AGAINST(#{expr} IN BOOLEAN MODE)
            ORDER BY MATCH(name, cause, symptom) AGAINST(#{expr} IN BOOLEAN MODE) DESC,
                     view_count DESC, id DESC
            LIMIT #{limit}
            """)
    List<Illness> searchByFulltext(@Param("expr") String expr, @Param("limit") int limit);
}
