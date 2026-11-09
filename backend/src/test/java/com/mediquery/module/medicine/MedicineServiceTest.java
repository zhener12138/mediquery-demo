package com.mediquery.module.medicine;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mediquery.module.medicine.entity.Medicine;
import com.mediquery.module.medicine.mapper.MedicineMapper;
import com.mediquery.module.medicine.service.MedicineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 药品召回的行为约束。
 *
 * <p>原来还有覆盖 {@code search} 分页、{@code getById} 缓存、{@code save}/{@code delete}
 * 的用例，那套「药品百科」的读接口和管理 CRUD 随页面一起删了。现在只钉召回语义。
 *
 * <p>保留一条 {@code limit} 用例是有原因的：这句 {@code query.last("LIMIT " + limit)}
 * 是拼字符串，写错就是全表扫描 —— 知识库现在只有 7 条药品看不出来，
 * 真灌到几千条时 RAG 就会慢得莫名其妙。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:mediquery_medicine;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "jwt.secret=test-secret-key-must-be-at-least-256-bits-long-for-hs256",
        "mediquery.retrieval.strategy=like"
})
class MedicineServiceTest {

    @Autowired
    private MedicineService medicineService;

    @Autowired
    private MedicineMapper medicineMapper;

    @BeforeEach
    void setUp() {
        medicineMapper.delete(Wrappers.<Medicine>lambdaQuery().gt(Medicine::getId, 0));
    }

    private Medicine insert(String name, int type, int status) {
        var m = new Medicine();
        m.setName(name);
        m.setKeyword(name);
        m.setEffect(name + " 的功效");
        m.setType(type);
        m.setPrice(new BigDecimal("12.50"));
        m.setStatus(status);
        medicineMapper.insert(m);
        return m;
    }

    @Test
    @DisplayName("只召回上架药品：下架的方子不该出现在给模型的上下文里")
    void searchByKeywordsOnlyReturnsPublished() {
        insert("上架药", 0, 1);
        insert("下架药", 0, 0);

        var hits = medicineService.searchByKeywords(List.of("药"), 10);

        assertThat(hits).extracting(Medicine::getName).containsExactly("上架药");
    }

    @Test
    @DisplayName("关键词能命中 name / keyword / effect 三个字段")
    void keywordMatchesThreeColumns() {
        var a = insert("阿司匹林", 0, 1);
        a.setEffect("解热镇痛");
        medicineMapper.updateById(a);

        assertThat(medicineService.searchByKeywords(List.of("阿司匹林"), 10)).hasSize(1);
        assertThat(medicineService.searchByKeywords(List.of("解热镇痛"), 10)).hasSize(1);
    }

    @Test
    @DisplayName("多关键词是 OR 语义")
    void searchByKeywordsIsOr() {
        insert("阿司匹林", 0, 1);
        insert("布洛芬", 0, 1);
        insert("感冒灵", 1, 1);

        var hits = medicineService.searchByKeywords(List.of("阿司匹林", "布洛芬"), 10);

        assertThat(hits).extracting(Medicine::getName)
                .containsExactlyInAnyOrder("阿司匹林", "布洛芬");
    }

    @Test
    @DisplayName("空列表返回空，不拼出 WHERE 1=1 查全表")
    void searchByKeywordsWithEmptyListReturnsEmpty() {
        insert("随便一条", 0, 1);

        assertThat(medicineService.searchByKeywords(List.of(), 10)).isEmpty();
        assertThat(medicineService.searchByKeywords(null, 10)).isEmpty();
    }

    @Test
    @DisplayName("遵守 limit")
    void searchByKeywordsRespectsLimit() {
        for (int i = 1; i <= 5; i++) {
            insert("药品" + i, 0, 1);
        }

        assertThat(medicineService.searchByKeywords(List.of("药品"), 3)).hasSize(3);
    }

    @Test
    @DisplayName("逻辑删除的药品不再被召回")
    void logicalDeletedRowsAreNotRecalled() {
        var m = insert("待删", 0, 1);

        medicineMapper.deleteById(m.getId());

        assertThat(medicineService.searchByKeywords(List.of("待删"), 10)).isEmpty();
    }
}
