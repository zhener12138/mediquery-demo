package com.mediquery.module.knowledge.dto;

import lombok.Data;

/**
 * 一个症状在知识库里的「文档频率」：有多少个疾病把它当自己的表现。
 *
 * <p>这是 IDF 的输入。{@code df} 越大说明这个症状越没有区分度 ——
 * 「发热」挂在几乎所有感染性疾病上，「骨擦音」只挂在骨折上，
 * 两者命中时给出的信息量差着一个数量级，而 {@code SUM(weight)}
 * 把它们当成一样值钱。
 *
 * <p>为什么不在 SQL 里直接算 {@code LOG(N/df)}：H2 与 MySQL 对
 * {@code LOG} 的边界行为（df=N 时 log(1)=0 会把权重整个抹掉）需要各写一遍
 * 才能确认一致，而这段算术放在 Java 里能单测、能解释、也不怕换数据库。
 */
@Data
public class SymptomDf {

    /** 症状 ID */
    private Integer symptomId;

    /** 含该症状的已发布疾病数 */
    private Integer df;
}
