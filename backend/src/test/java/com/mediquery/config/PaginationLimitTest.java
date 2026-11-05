package com.mediquery.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mediquery.module.illness.entity.Illness;
import com.mediquery.module.illness.mapper.IllnessMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分页上限的护栏测试。
 *
 * <h3>这些测试在防什么</h3>
 *
 * <p>{@code size} 是用户可控的查询参数。没有上限时，{@code ?size=1000000}
 * 会真的去查一百万条——不需要攻击工具，地址栏改个数字就行。
 *
 * <p>这个测试必须<b>真连库</b>跑：上限是 MyBatis-Plus 拦截器在生成 SQL 时施加的，
 * 不是业务代码里的 if 判断。用 mock 测等于测了个寂寞——它会告诉你"代码里有 setMaxLimit"，
 * 但不会告诉你"这条 SQL 真的被限制了"。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:mediquery_page;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
class PaginationLimitTest {

    @Autowired
    private IllnessMapper illnessMapper;

    @BeforeEach
    void seed() {
        // 插入 150 条，正好跨过 100 的上限，这样"被截断"和"没被截断"能区分开。
        // 如果只插 10 条，就算上限生效测试也会过——那样等于没测。
        // 用带条件的 delete 而不是 delete(null)：BlockAttackInnerInterceptor
        // 会拦截无 where 的全表删除（这是刻意的防线，测试也要守）。
        illnessMapper.delete(Wrappers.<Illness>lambdaQuery().gt(Illness::getId, 0));
        for (int i = 1; i <= 150; i++) {
            var illness = new Illness();
            illness.setCategoryId(1);
            illness.setName("测试疾病" + i);
            illness.setCause("病因" + i);
            illness.setSymptom("症状" + i);
            illness.setViewCount(0);
            illness.setStatus(1);
            illnessMapper.insert(illness);
        }
    }

    @Test
    @DisplayName("请求 size=1000000 时实际最多拿 100 条")
    void oversizedPageIsCapped() {
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<Illness>(1, 1_000_000);

        var result = illnessMapper.selectPage(page, null);

        assertEquals(100, result.getRecords().size(),
                "请求一百万条却拿回 " + result.getRecords().size() + " 条。"
                        + "如果不等于 100，说明 setMaxLimit 没生效——"
                        + "任何人都能用地址栏把整张表拉进内存。");
        // total 仍然应该是真实总数，不是被截断后的数——它用于分页控件显示总页数。
        assertEquals(150, result.getTotal(),
                "total 必须是真实总数 150，不能被上限影响，否则前端分页算错");
    }

    @Test
    @DisplayName("正常范围内的 size 不受影响")
    void normalPageSizeIsUnaffected() {
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<Illness>(1, 9);

        var result = illnessMapper.selectPage(page, null);

        // 这条防的是"为了安全把正常功能也砍了"——上限设成 10 也能让上面那条测试绿，
        // 但公开列表页每页 9 条就快踩线了。
        assertEquals(9, result.getRecords().size(), "正常分页不该被上限干扰");
    }

    @Test
    @DisplayName("边界值 100 本身是允许的")
    void exactlyMaxIsAllowed() {
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<Illness>(1, 100);

        var result = illnessMapper.selectPage(page, null);

        assertEquals(100, result.getRecords().size(),
                "100 是上限本身，应该原样返回，不该被误伤成更小的值");
    }

    @Test
    @DisplayName("page=1&size=100 能取到数据且 total 正确")
    void metadataIsConsistent() {
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<Illness>(2, 100);

        var result = illnessMapper.selectPage(page, null);

        assertEquals(150, result.getTotal());
        assertEquals(2, result.getPages(), "150 条按每页 100 算是 2 页");
        assertEquals(50, result.getRecords().size(), "第 2 页只剩 50 条");
        assertTrue(result.getRecords().stream().allMatch(i -> i.getDeleted() == null || i.getDeleted() == 0),
                "逻辑删除的行不该出现在结果里");
    }
}
