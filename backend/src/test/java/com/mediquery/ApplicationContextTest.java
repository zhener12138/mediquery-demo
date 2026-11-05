package com.mediquery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试基线：Spring 容器能不能起来。
 *
 * <p>这是整个测试体系的地基。它只验证一件事——把真实的 Controller / Service / Mapper
 * 全部装配起来、连上内存数据库，容器能正常启动。任何一个 Bean 的依赖写错、
 * 某个 {@code @Value} 占位符解析不到、循环依赖，都会在这里炸出来。
 *
 * <h3>为什么用 {@code @TestPropertySource} 而不是只靠 application-test.yml</h3>
 *
 * <p>项目引入了 spring-dotenv，{@code spring.dotenv.path=./.env}，而 {@code backend/.env}
 * 是真实存在的（里面有开发库密码、真实 API Key）。理论上 dotenv 的属性源可能盖掉
 * application-test.yml 里的 H2 地址，让测试连到真实 MySQL —— 而 {@code schema-h2.sql}
 * 第一行是 {@code DROP TABLE}，后果是开发库被清空。
 *
 * <p>实测（2026-11-05，替换 backend/.env 后跑探针）：dotenv 确实被加载了，
 * 但 {@code spring.datasource.url} 仍然来自 application-test.yml，dotenv 没有赢。
 * 所以这个风险目前是理论上的，不是已经发生的事实。
 *
 * <p>那为什么还显式钉死：这个优先级顺序属于 Spring Boot 内部实现，升级版本可能改变，
 * 而赌错的代价是清空开发库。把三个最危险的键写死，成本是一行注解，收益是这类事故不可能发生。
 *
 * <p>配套的 pom.xml 里 surefire 还有一条 {@code -Dspring.dotenv.path=./.env.not-used-in-tests}，
 * 那是第二道闸 —— 即使将来 Spring Boot 改了顺序，dotenv 也找不到文件。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:mediquery_ctx;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
})
class ApplicationContextTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void contextLoads() {
        // 能走到这里就说明容器装配成功，不需要额外断言
        assertNotNull(dataSource);
    }

    /**
     * 安全阀：确认真的连在 H2 上，而不是被 dotenv 引到了真实 MySQL。
     *
     * <p>这条断言是这个测试类里最重要的一条。它防的不是业务 bug，
     * 是"跑一次 mvn test 把开发库清空"这种事故。
     */
    @Test
    void datasourceIsH2NotRealMysql() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            String product = conn.getMetaData().getDatabaseProductName();
            assertTrue(product.toUpperCase().contains("H2"),
                    "测试必须跑在 H2 上，实际连到了：" + product
                            + "（很可能被 .env 里的 SPRING_DATASOURCE_URL 覆盖了）");
        }
    }

    /**
     * 确认 schema-h2.sql 确实执行了：核心表存在。
     *
     * <p>如果 spring.sql.init 没跑，这里的查询会抛异常，
     * 而不是等到某个业务用例莫名其妙地失败。
     */
    @Test
    void schemaIsInitialized() throws Exception {
        try (Connection conn = dataSource.getConnection();
             var rs = conn.getMetaData().getTables(null, null, "illness", new String[]{"TABLE"})) {
            assertTrue(rs.next(), "表 illness 不存在，schema-h2.sql 没有执行");
        }
    }
}
