package com.mediquery.module.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mediquery.module.knowledge.dto.SymptomVocabularyRow;
import com.mediquery.module.knowledge.entity.Symptom;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SymptomMapper extends BaseMapper<Symptom> {

    /**
     * 取整张症状词表：标准名 + 全部别名，拍平成一张表。
     *
     * <p><b>为什么整表捞进内存，而不是拿用户输入去数据库里筛。</b>
     * 词表的量级是「几百到几千行」，一次全取回来常驻内存完全放得下；
     * 而拿输入去筛要求先把用户那句话切成词、再拼 LIKE —— 又回到
     * 「切错一个词就永远命不中」的老路上。归一化这一步的正确性
     * 恰恰依赖「拿输入去比全部候选」，而不是「让数据库做前缀匹配」。
     *
     * <p>标准名以 {@code weight = 1.00} 出现在结果里，和别名走同一个匹配循环，
     * 避免「先匹标准名、再匹别名」的两段式逻辑在边界上不一致。
     *
     * <p>停用的症状（{@code status = 0}）不出现在词表里 —— 停用的意思是
     * 「这个名字已经不用了」，但它名下的疾病关联还在，所以只屏蔽说法，不删关联。
     */
    @Select("""
            SELECT s.id           AS symptom_id,
                   s.name         AS symptom_name,
                   s.body_part    AS body_part,
                   s.category     AS category,
                   s.name         AS match_text,
                   1.00           AS weight
              FROM symptom s
             WHERE s.status = 1
            UNION ALL
            SELECT s.id           AS symptom_id,
                   s.name         AS symptom_name,
                   s.body_part    AS body_part,
                   s.category     AS category,
                   a.alias        AS match_text,
                   a.weight       AS weight
              FROM symptom_alias a
              JOIN symptom s ON s.id = a.symptom_id
             WHERE s.status = 1
            """)
    List<SymptomVocabularyRow> selectVocabulary();
}
