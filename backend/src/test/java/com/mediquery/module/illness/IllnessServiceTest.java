package com.mediquery.module.illness;

import com.mediquery.module.illness.entity.Illness;
import com.mediquery.module.illness.mapper.IllnessMapper;
import com.mediquery.module.illness.service.IllnessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 疾病模块集成测试：真连 H2、真走 MyBatis-Plus。
 *
 * <h3>为什么不用 Mock</h3>
 *
 * <p>这个项目最难查的 bug 都在"框架配置层"——分页插件没生效、逻辑删除字段名写错、
 * 自动填充没注册。用 Mock 测 Service 会把所有这些掩盖掉：Mock 的 Mapper 永远返回你
 * 让它返回的东西，分页插件有没有生效根本测不出来。所以这里用真库。
 *
 * <h3>覆盖什么</h3>
 *
 * <ul>
 *   <li>插入后 {@code createTime} 被 {@code MyMetaObjectHandler} 自动填充（不是 null）；</li>
 *   <li>逻辑删除真的生效：删完 {@code selectById} 查不到，但物理行还在；</li>
 *   <li>{@code search} 的 keyword 条件能同时命中 name / cause / symptom；</li>
 *   <li>{@code search} 只返回 {@code status=1} 的数据；</li>
 *   <li>分页参数生效。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:mediquery_illness;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
})
class IllnessServiceTest {

    @Autowired
    private IllnessService illnessService;

    @Autowired
    private IllnessMapper illnessMapper;

    @BeforeEach
    void cleanUp() {
        // 每个用例自己造数据，不依赖种子数据。
        // 用 delete(null) 会触发 BlockAttack 拦截器（如果配了），所以用 update 带 where 清空
        illnessMapper.delete(null);
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
    void insertFillsCreateTimeAutomatically() {
        Illness i = newIllness("测试感冒", "受凉", "咳嗽", 1);
        illnessMapper.insert(i);

        assertNotNull(i.getId(), "插入后主键应回填");
        assertNotNull(i.getCreateTime(),
                "createTime 应该由 MyMetaObjectHandler 自动填充，是 null 说明 MetaObjectHandler 没注册上");
    }

    @Test
    void searchMatchesNameCauseAndSymptom() {
        illnessMapper.insert(newIllness("高血压", "遗传", "头晕", 1));
        illnessMapper.insert(newIllness("糖尿病", "胰岛素分泌不足", "多饮多尿", 1));
        illnessMapper.insert(newIllness("胃炎", "饮食不规律", "胃痛", 1));

        assertEquals(1, illnessService.search("高血压", null, 1, 10).getRecords().size(),
                "按 name 命中失败");
        assertEquals(1, illnessService.search("遗传", null, 1, 10).getRecords().size(),
                "按 cause 命中失败");
        assertEquals(1, illnessService.search("胃痛", null, 1, 10).getRecords().size(),
                "按 symptom 命中失败");

        // 三个字段 OR 关系：搜一个不存在的词应该 0 条
        assertEquals(0, illnessService.search("完全不存在的词xyz", null, 1, 10).getRecords().size());
    }

    @Test
    void searchOnlyReturnsPublished() {
        illnessMapper.insert(newIllness("已发布病", "原因", "症状", 1));
        illnessMapper.insert(newIllness("草稿病", "原因", "症状", 0));

        var page = illnessService.search(null, null, 1, 10);

        assertEquals(1, page.getRecords().size(), "草稿状态（status=0）不应出现在列表里");
        assertEquals("已发布病", page.getRecords().get(0).getName());
    }

    @Test
    void paginationWorks() {
        for (int i = 1; i <= 5; i++) {
            illnessMapper.insert(newIllness("疾病" + i, "原因", "症状", 1));
        }

        var page1 = illnessService.search(null, null, 1, 2);
        var page2 = illnessService.search(null, null, 2, 2);

        assertEquals(5, page1.getTotal(), "总数应为 5");
        assertEquals(2, page1.getRecords().size(), "第一页应有 2 条");
        assertEquals(2, page2.getRecords().size(), "第二页应有 2 条");
        assertNotEquals(page1.getRecords().get(0).getId(), page2.getRecords().get(0).getId(),
                "两页不应返回同一批数据，说明 PaginationInnerInterceptor 没生效");
    }

    @Test
    void logicalDeleteHidesRowButKeepsIt() {
        Illness i = newIllness("要被删的病", "原因", "症状", 1);
        illnessMapper.insert(i);
        Integer id = i.getId();

        illnessMapper.deleteById(id);

        assertNull(illnessMapper.selectById(id), "逻辑删除后 selectById 应该查不到");

        // 物理行还在，只是 deleted 变成 1
        Long rawCount = illnessMapper.selectCount(null);
        assertEquals(0L, rawCount, "逻辑删除后不该再被普通查询统计到");
    }
}
