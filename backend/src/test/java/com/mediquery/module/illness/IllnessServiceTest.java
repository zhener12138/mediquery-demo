package com.mediquery.module.illness;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mediquery.module.illness.entity.Illness;
import com.mediquery.module.illness.entity.IllnessCategory;
import com.mediquery.module.illness.entity.IllnessMedicineRel;
import com.mediquery.module.illness.mapper.IllnessCategoryMapper;
import com.mediquery.module.illness.mapper.IllnessMapper;
import com.mediquery.module.illness.mapper.IllnessMedicineRelMapper;
import com.mediquery.module.illness.service.IllnessService;
import com.mediquery.module.medicine.entity.Medicine;
import com.mediquery.module.medicine.mapper.MedicineMapper;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 疾病召回的集成测试：真连 H2、真走 MyBatis-Plus。
 *
 * <h3>为什么不用 Mock</h3>
 *
 * <p>这个项目最难查的 bug 都在「框架配置层」——逻辑删除字段名写错、
 * 自动填充没注册、拼接的 OR 条件多一层括号。用 Mock 测 Service 会把所有这些
 * 掩盖掉：Mock 的 Mapper 永远返回你让它返回的东西。所以这里用真库。
 *
 * <h3>为什么只剩这些用例</h3>
 *
 * <p>原来还有 5 条覆盖 {@code search}/{@code pagination}/{@code getById} 的用例，
 * 那套「疾病百科」的读接口随浏览页一起删了。现在这个 Service 只服务 RAG 召回，
 * 所以测试也只钉召回语义：状态过滤、三字段 OR、limit、科室名回填。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:mediquery_illness;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "jwt.secret=test-secret-key-must-be-at-least-256-bits-long-for-hs256",
        "mediquery.retrieval.strategy=like"
})
class IllnessServiceTest {

    @Autowired
    private IllnessService illnessService;

    @Autowired
    private IllnessMapper illnessMapper;

    @Autowired
    private IllnessCategoryMapper categoryMapper;

    @Autowired
    private IllnessMedicineRelMapper relMapper;

    @Autowired
    private MedicineMapper medicineMapper;

    @BeforeEach
    void cleanUp() {
        // 每个用例自己造数据，不依赖种子数据。
        // 必须带 where 条件：BlockAttackInnerInterceptor 会拦无 where 的全表删除，
        // delete(null) 会直接抛异常。这也是生产代码的防线，测试同样要守。
        // 关联表要先清：它没有逻辑删除列，留着会串到下一个用例。
        relMapper.delete(Wrappers.<IllnessMedicineRel>lambdaQuery().gt(IllnessMedicineRel::getId, 0));
        medicineMapper.delete(Wrappers.<Medicine>lambdaQuery().gt(Medicine::getId, 0));
        illnessMapper.delete(Wrappers.<Illness>lambdaQuery().gt(Illness::getId, 0));
        categoryMapper.delete(Wrappers.<IllnessCategory>lambdaQuery().gt(IllnessCategory::getId, 0));
    }

    private Illness newIllness(String name, String cause, String symptom, int status) {
        Illness i = new Illness();
        i.setCategoryId(1);
        i.setName(name);
        i.setCause(cause);
        i.setSymptom(symptom);
        i.setViewCount(0);
        i.setStatus(status);
        return i;
    }

    @Test
    @DisplayName("插入后 createTime 由 MyMetaObjectHandler 自动填充")
    void insertFillsCreateTimeAutomatically() {
        Illness i = newIllness("测试感冒", "受凉", "咳嗽", 1);
        illnessMapper.insert(i);

        assertNotNull(i.getId(), "插入后主键应回填");
        assertNotNull(i.getCreateTime(),
                "createTime 应该由 MyMetaObjectHandler 自动填充，是 null 说明 MetaObjectHandler 没注册上");
    }

    @Test
    @DisplayName("searchByKeywords 能命中 name / cause / symptom 三个字段")
    void searchByKeywordsMatchesThreeColumns() {
        illnessMapper.insert(newIllness("高血压", "遗传", "头晕", 1));
        illnessMapper.insert(newIllness("糖尿病", "胰岛素分泌不足", "多饮多尿", 1));

        assertThat(illnessService.searchByKeywords(List.of("高血压"), 10))
                .extracting(Illness::getName).containsExactly("高血压");
        assertThat(illnessService.searchByKeywords(List.of("遗传"), 10))
                .extracting(Illness::getName).containsExactly("高血压");
        assertThat(illnessService.searchByKeywords(List.of("多饮多尿"), 10))
                .extracting(Illness::getName).containsExactly("糖尿病");
    }

    @Test
    @DisplayName("多个关键词是 OR 语义，不是 AND")
    void searchByKeywordsIsOr() {
        illnessMapper.insert(newIllness("高血压", "遗传", "头晕", 1));
        illnessMapper.insert(newIllness("胃溃疡", "幽门螺杆菌", "胃痛", 1));

        // 一句话里两个症状词，只要命中任一就该被召回 —— RAG 要的是「宁可多召回再精排」
        var hits = illnessService.searchByKeywords(List.of("头晕", "胃痛"), 10);

        assertThat(hits).extracting(Illness::getName)
                .containsExactlyInAnyOrder("高血压", "胃溃疡");
    }

    @Test
    @DisplayName("只召回已发布（status=1）的条目")
    void searchByKeywordsOnlyReturnsPublished() {
        illnessMapper.insert(newIllness("已发布病", "原因", "症状", 1));
        illnessMapper.insert(newIllness("草稿病", "原因", "症状", 0));

        var hits = illnessService.searchByKeywords(List.of("原因"), 10);

        assertThat(hits).extracting(Illness::getName).containsExactly("已发布病");
    }

    @Test
    @DisplayName("空关键词返回空，不拼出 WHERE 1=1 查全表")
    void searchByKeywordsWithEmptyListReturnsEmpty() {
        illnessMapper.insert(newIllness("随便一条", "原因", "症状", 1));

        assertThat(illnessService.searchByKeywords(List.of(), 10)).isEmpty();
        assertThat(illnessService.searchByKeywords(null, 10)).isEmpty();
    }

    @Test
    @DisplayName("遵守 limit，避免一次召回把整张表拉进内存")
    void searchByKeywordsRespectsLimit() {
        for (int i = 1; i <= 5; i++) {
            illnessMapper.insert(newIllness("疾病" + i, "共同原因", "症状", 1));
        }

        assertThat(illnessService.searchByKeywords(List.of("共同原因"), 3)).hasSize(3);
    }

    @Test
    @DisplayName("回填科室名：模型要靠它给出真实存在的科室")
    void categoryNameIsFilled() {
        var category = new IllnessCategory();
        category.setName("心血管内科");
        category.setSortOrder(1);
        categoryMapper.insert(category);

        var illness = newIllness("高血压", "遗传", "头晕", 1);
        illness.setCategoryId(category.getId());
        illnessMapper.insert(illness);

        var hits = illnessService.searchByKeywords(List.of("高血压"), 10);

        // categoryName 是 @TableField(exist = false) 的关联字段，
        // 不显式回填就永远是 null —— 而 KnowledgeRetrievalService 靠它输出「常见科室」。
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getCategoryName()).isEqualTo("心血管内科");
    }

    @Test
    @DisplayName("逻辑删除的条目不再被召回")
    void logicalDeletedRowsAreNotRecalled() {
        var i = newIllness("要被删的病", "共同原因", "症状", 1);
        illnessMapper.insert(i);

        illnessMapper.deleteById(i.getId());

        assertThat(illnessService.searchByKeywords(List.of("共同原因"), 10)).isEmpty();
    }

    @Test
    @DisplayName("关联药品：RAG 补上下文用的那条 JOIN 真的能查出东西")
    void relatedMedicinesAreFound() {
        var illness = newIllness("病毒性感冒", "病毒感染", "流清涕、打喷嚏", 1);
        illnessMapper.insert(illness);

        var medicine = new Medicine();
        medicine.setName("999感冒灵颗粒");
        medicine.setKeyword("感冒");
        medicine.setType(1);
        medicine.setPrice(new BigDecimal("18.50"));
        medicine.setStatus(1);
        medicineMapper.insert(medicine);

        var rel = new IllnessMedicineRel();
        rel.setIllnessId(illness.getId());
        rel.setMedicineId(medicine.getId());
        relMapper.insert(rel);

        // 这条 SQL 在重构前唯一的调用方是已删除的 IllnessService.getById，
        // 也就是说它从上线起就没在真库上跑过。现在它是 RAG 的活路径，
        // 必须有测试真的执行一次 JOIN —— 否则哪天列名改了也没人发现。
        var found = relMapper.findMedicinesByIllnessId(illness.getId());

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getName()).isEqualTo("999感冒灵颗粒");
    }

    @Test
    @DisplayName("关联药品：逻辑删除的药品不出现在 JOIN 结果里")
    void deletedMedicineIsNotReturnedByJoin() {
        var illness = newIllness("风寒感冒", "受凉", "怕冷、清涕", 1);
        illnessMapper.insert(illness);

        var medicine = new Medicine();
        medicine.setName("已下架的药");
        medicine.setKeyword("感冒");
        medicine.setType(1);
        medicine.setPrice(new BigDecimal("10.00"));
        medicine.setStatus(1);
        medicineMapper.insert(medicine);

        var rel = new IllnessMedicineRel();
        rel.setIllnessId(illness.getId());
        rel.setMedicineId(medicine.getId());
        relMapper.insert(rel);

        medicineMapper.deleteById(medicine.getId());

        // mapper 里那句 "AND m.deleted = 0" 就是防这个的。
        // 药品下架后不能还作为「相关用药」喂给模型。
        assertThat(relMapper.findMedicinesByIllnessId(illness.getId())).isEmpty();
    }
}
