package com.mediquery.integration.ai;

import com.mediquery.module.illness.entity.Illness;
import com.mediquery.module.illness.service.IllnessService;
import com.mediquery.module.medicine.entity.Medicine;
import com.mediquery.module.medicine.service.MedicineService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 测试与环境兼容实现：走 OR LIKE，语义与 {@link FulltextRetrieval} 对齐但不依赖全文索引。
 *
 * <p>存在的唯一理由：H2（CI 与本地 {@code @SpringBootTest}）没有 {@code MATCH} 函数，
 * 实测报 {@code Function "MATCH" not found [90022-232]}。没有这个实现，
 * 所有涉及检索的测试在 CI 里都会语法报错。
 *
 * <p>由 {@code mediquery.retrieval.strategy=like} 启用，
 * {@code application-test.yml} 里就是这么配的。
 *
 * <p><b>不要把这条路径当成「生产也在跑」。</b>它跑的是另一种 SQL：
 * LIKE 不走索引、没有相关度分数、按 {@code view_count} 粗排。
 * 用它通过测试不代表全文检索是对的 —— 那条 SQL 的验证在真 MySQL 上做。
 */
@Component
@ConditionalOnProperty(name = "mediquery.retrieval.strategy", havingValue = "like")
public class LikeRetrieval implements RetrievalStrategy {

    private final IllnessService illnessService;
    private final MedicineService medicineService;

    public LikeRetrieval(IllnessService illnessService, MedicineService medicineService) {
        this.illnessService = illnessService;
        this.medicineService = medicineService;
    }

    @Override
    public List<Illness> recallIllnesses(List<String> keywords, int limit) {
        return illnessService.searchByKeywords(keywords, limit);
    }

    @Override
    public List<Medicine> recallMedicines(List<String> keywords, int limit) {
        return medicineService.searchByKeywords(keywords, limit);
    }

    @Override
    public String name() {
        return "like";
    }
}
