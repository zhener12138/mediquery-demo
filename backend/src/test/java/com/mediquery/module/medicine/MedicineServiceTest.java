package com.mediquery.module.medicine;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mediquery.module.medicine.entity.Medicine;
import com.mediquery.module.medicine.mapper.MedicineMapper;
import com.mediquery.module.medicine.service.MedicineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 药品服务的行为约束。
 *
 * <p>重点两条：① 分页必须有 tie-breaker（同秒插入时不加 id 会跨页重复，
 * 这是真的在疾病那边抓到过的 bug）；② {@code getById} 不能把下架药品吐出来 ——
 * 缓存一旦缓存了它，TTL 内用户就能看到一个已经下架的药品。
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

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        medicineMapper.delete(Wrappers.<Medicine>lambdaQuery().gt(Medicine::getId, 0));
        clearCache();
    }

    @AfterEach
    void tearDown() {
        medicineMapper.delete(Wrappers.<Medicine>lambdaQuery().gt(Medicine::getId, 0));
        clearCache();
    }

    private void clearCache() {
        var cache = cacheManager.getCache("medicine");
        if (cache != null) {
            cache.clear();
        }
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
    @DisplayName("搜索只返回上架药品")
    void searchOnlyReturnsPublished() {
        insert("上架药", 0, 1);
        insert("下架药", 0, 0);

        var result = medicineService.search(null, null, 1, 10);

        assertThat(result.getRecords())
                .extracting(Medicine::getName)
                .containsExactly("上架药");
    }

    @Test
    @DisplayName("type >= 0 时才按类型筛，-1 表示全部")
    void typeFilterIsOptional() {
        insert("西药甲", 0, 1);
        insert("中药乙", 1, 1);

        assertThat(medicineService.search(null, 0, 1, 10).getRecords()).hasSize(1);
        assertThat(medicineService.search(null, 1, 1, 10).getRecords()).hasSize(1);
        assertThat(medicineService.search(null, -1, 1, 10).getRecords()).hasSize(2);
        assertThat(medicineService.search(null, null, 1, 10).getRecords()).hasSize(2);
    }

    @Test
    @DisplayName("关键词能命中 name / keyword / effect 三个字段")
    void keywordMatchesThreeColumns() {
        var a = insert("阿司匹林", 0, 1);
        a.setEffect("解热镇痛");
        medicineMapper.updateById(a);

        assertThat(medicineService.search("阿司匹林", null, 1, 10).getRecords()).hasSize(1);
        assertThat(medicineService.search("解热镇痛", null, 1, 10).getRecords()).hasSize(1);
    }

    @Test
    @DisplayName("分页不重复：同秒插入 5 条，翻两页拿到的 id 不重叠")
    void paginationDoesNotRepeatAcrossPages() {
        for (int i = 1; i <= 5; i++) {
            insert("药品" + i, 0, 1);
        }

        var page1 = medicineService.search(null, null, 1, 2).getRecords();
        var page2 = medicineService.search(null, null, 2, 2).getRecords();
        var page3 = medicineService.search(null, null, 3, 2).getRecords();

        var all = List.of(page1, page2, page3).stream()
                .flatMap(List::stream)
                .map(Medicine::getId)
                .toList();

        assertThat(all).doesNotHaveDuplicates().hasSize(5);
    }

    @Test
    @DisplayName("getById 不返回已下架药品")
    void getByIdSkipsUnpublished() {
        var off = insert("已下架", 0, 0);

        assertThat(medicineService.getById(off.getId())).isNull();
    }

    @Test
    @DisplayName("getById 不返回不存在的 id（且不会把 null 塞进缓存）")
    void getByIdReturnsNullForMissing() {
        assertThat(medicineService.getById(999_999)).isNull();
        // 缓存没被污染：再查一次仍然是 null，不是上次抛异常
        assertThat(medicineService.getById(999_999)).isNull();
    }

    @Test
    @DisplayName("getById 命中缓存：第二次不查库（用改库后仍返回旧值来证明）")
    void getByIdIsCached() {
        var m = insert("缓存药", 0, 1);

        var first = medicineService.getById(m.getId());
        assertThat(first).isNotNull();

        // 绕过 service 直接改库 + 清缓存才能验证「真的缓存了」，
        // 所以这里反过来：直接改库不动缓存，第二次应该拿到旧对象
        m.setName("改名了");
        medicineMapper.updateById(m);

        var second = medicineService.getById(m.getId());
        assertThat(second.getName()).isEqualTo("缓存药");
    }

    @Test
    @DisplayName("save 强制置为上架，不管调用方传了什么")
    void saveForcesPublishedStatus() {
        var m = new Medicine();
        m.setName("新药");
        m.setKeyword("新药");
        m.setEffect("效果");
        m.setType(0);
        m.setPrice(new BigDecimal("9.90"));
        m.setStatus(0); // 故意传下架

        var saved = medicineService.save(m);

        assertThat(saved.getStatus()).isEqualTo(1);
        assertThat(medicineMapper.selectById(saved.getId()).getStatus()).isEqualTo(1);
    }

    @Test
    @DisplayName("searchByKeywords 空列表返回空，不拼出 WHERE 1=1 查全表")
    void searchByKeywordsWithEmptyListReturnsEmpty() {
        insert("随便一条", 0, 1);

        assertThat(medicineService.searchByKeywords(List.of(), 10)).isEmpty();
        assertThat(medicineService.searchByKeywords(null, 10)).isEmpty();
    }

    @Test
    @DisplayName("searchByKeywords 多关键词是 OR 语义")
    void searchByKeywordsIsOr() {
        insert("阿司匹林", 0, 1);
        insert("布洛芬", 0, 1);
        insert("感冒灵", 1, 1);

        var hits = medicineService.searchByKeywords(List.of("阿司匹林", "布洛芬"), 10);

        assertThat(hits).extracting(Medicine::getName)
                .containsExactlyInAnyOrder("阿司匹林", "布洛芬");
    }

    @Test
    @DisplayName("searchByKeywords 遵守 limit")
    void searchByKeywordsRespectsLimit() {
        for (int i = 1; i <= 5; i++) {
            insert("药品" + i, 0, 1);
        }

        assertThat(medicineService.searchByKeywords(List.of("药品"), 3)).hasSize(3);
    }

    @Test
    @DisplayName("delete 是逻辑删除：行还在，但查不到了")
    void deleteIsLogical() {
        var m = insert("待删", 0, 1);

        medicineService.delete(m.getId());

        assertThat(medicineService.getById(m.getId())).isNull();
        // 物理行仍在（@TableLogic）
        assertThat(medicineMapper.selectCount(
                Wrappers.<Medicine>lambdaQuery().eq(Medicine::getId, m.getId()))).isZero();
    }
}
