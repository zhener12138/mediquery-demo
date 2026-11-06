package com.mediquery.integration.ai;

import com.mediquery.module.illness.entity.Illness;
import com.mediquery.module.illness.mapper.IllnessMapper;
import com.mediquery.module.medicine.entity.Medicine;
import com.mediquery.module.medicine.mapper.MedicineMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 生产实现：一条 SQL 走 {@code ft_illness} / {@code ft_medicine} 全文索引。
 *
 * <p>相比原来的 OR LIKE 拼接，两个变化：
 * <ol>
 *   <li><b>SQL 条数</b>：原来一句 24 个 token 的问诊会拼出
 *       {@code 24 词 × 3 字段 = 72} 个 LIKE 条件，疾病与药品各一条，
 *       合计 2 条超长 SQL，MySQL 无法用索引，只能全表扫描后逐行做字符串匹配。</li>
 *   <li><b>排序</b>：原来按 {@code view_count} 粗排，靠内存里的
 *       {@code scoreAgainst} 精排；现在数据库先按相关度返回，
 *       更相关的条目必定进入召回窗口，内存精排只负责「名称命中 ×3」这类
 *       数据库表达不了的业务权重。</li>
 * </ol>
 *
 * <p>{@code @ConditionalOnProperty} 默认 true，切换只需
 * {@code mediquery.retrieval.strategy=like}（H2 测试 profile 里就是这么设的）。
 */
@Component
@ConditionalOnProperty(name = "mediquery.retrieval.strategy", havingValue = "fulltext", matchIfMissing = true)
public class FulltextRetrieval implements RetrievalStrategy {

    private final IllnessMapper illnessMapper;
    private final MedicineMapper medicineMapper;

    public FulltextRetrieval(IllnessMapper illnessMapper, MedicineMapper medicineMapper) {
        this.illnessMapper = illnessMapper;
        this.medicineMapper = medicineMapper;
    }

    @Override
    public List<Illness> recallIllnesses(List<String> keywords, int limit) {
        var expr = MatchExpression.build(keywords);
        if (expr.isEmpty()) return List.of();
        return illnessMapper.searchByFulltext(expr, limit);
    }

    @Override
    public List<Medicine> recallMedicines(List<String> keywords, int limit) {
        var expr = MatchExpression.build(keywords);
        if (expr.isEmpty()) return List.of();
        return medicineMapper.searchByFulltext(expr, limit);
    }

    @Override
    public String name() {
        return "fulltext";
    }
}
