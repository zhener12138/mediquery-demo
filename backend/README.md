# 医问通 (MediQuery) — 后端

在线问诊 AI 分诊机器人的后端。SpringBoot 3.4 + Spring Security + JWT + MyBatis-Plus + Redis + MySQL 8 + 阿里通义千问（DashScope SDK）。

项目定位、接口清单、SSE 事件契约、关键设计取舍见上级目录的 [README](../README.md) 与 [设计文档](../docs/在线问诊机器人-开发设计文档.md)。这里只讲怎么把后端跑起来和它自己的约定。

## 环境要求

- Java 21（`pom.xml` 的 `<java.version>` 是 21，用 17 编译不了）
- Maven 3.6+
- MySQL 8.0+
- Redis 7+

## 跑起来

```bash
# 1. 建库（只要库本身，表由 Flyway 建）
mysql -h127.0.0.1 -P3306 -uroot -p -e "
  CREATE DATABASE IF NOT EXISTS mediquery
  DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci;"

# 2. 配置密钥
cp ../.env.example ../.env    # JWT_SECRET / DASHSCOPE KEY / OSS / MAIL / DB 密码

# 3. 启动（Flyway 会自动建表，缺 JWT_SECRET 会直接拒绝启动——这是有意的）
mvn spring-boot:run
```

API 文档：http://localhost:8080/swagger-ui.html
健康检查：http://localhost:8080/actuator/health

### 可选：灌种子数据

不给种子也能跑，只是知识库为空、AI 没有可召回的条目、首页统计全是 0。

```bash
mysql -h127.0.0.1 -P3306 -uroot -p mediquery < src/main/resources/db/seed.sql
```

`seed.sql` 只能跑一次，第二次会主键冲突——这是刻意的，它不是幂等脚本。

## 数据库迁移

建表归 Flyway，种子归人。

```
src/main/resources/db/
├── migration/
│   ├── V1__baseline.sql      # 8 张表的基线结构（已发布，不再改）
│   └── V2__intake_slots.sql  # 问诊信息卡 4 列
└── seed.sql                  # 种子数据，手动执行
```

配置在 `application.yml` 的 `spring.flyway`：`baseline-on-migrate: true`、`validate-on-migrate: true`、`baseline-version: ${FLYWAY_BASELINE_VERSION:1}`。

**老库注意**：如果这个库是早先由 `init.sql` 手工建的（结构已经等于 V2），首次启动前要设 `FLYWAY_BASELINE_VERSION=2`，否则 Flyway 会从 V1 开始重放、在 `CREATE TABLE` 上撞"表已存在"。

**改结构怎么走**：加一个 `V3__xxx.sql`，不要回头改 V1/V2。改已发布的迁移等于让"已部署的库"和"没部署的库"走出两条不同的历史，`validate-on-migrate` 会直接拒绝启动。

**测试为什么不用 Flyway**：`application-test.yml` 里 `spring.flyway.enabled: false`，测试走 `src/test/resources/schema-h2.sql`（H2 内存库）。H2 不认 `ENGINE=InnoDB` 和 `WITH PARSER ngram`，跑不了 MySQL 迁移脚本。代价是迁移脚本本身没有自动化兜底，靠 `SchemaDriftTest` 比对「迁移累加后的字段集合」与 H2 schema 是否一致来兜一层。

## 默认账户

需要先灌 `seed.sql` 才有。

| 角色 | 用户名 | 密码 |
|------|--------|------|
| 管理员 | admin | admin123 |
| 用户 | test | test123 |

## 模块划分

```
src/main/java/com/mediquery/
├── common/          # Result / PageResult / 异常 / TraceId 过滤器
├── config/          # Security / CORS / MyBatis-Plus / Redis / 异步线程池
├── security/        # JWT 工具与认证过滤器
├── module/
│   ├── auth/        # 登录 / 注册 / 验证码 / 令牌续期
│   ├── user/        # 个人资料 / 头像上传
│   ├── consultation/# 问诊单、SSE 对话、分诊规则、异步小结、超时回收
│   ├── illness/     # 疾病知识底座（只供 RAG 召回，无对外页面）
│   ├── medicine/    # 药品知识底座（同上）
│   ├── stats/       # 公开统计（首页用）
│   └── operationlog/# 审计日志（AOP 切面）
└── integration/
    ├── ai/          # DashScope 封装 + 知识召回 + 全文/模糊双检索策略
    ├── email/       # 邮件
    └── oss/         # 阿里云 OSS
```

## 约定

- **`severity` 宁低勿高**：模型给 2 才报高危，解析失败一律降级成 0。宁可漏报，不可因为脏数据炸出假告警。
- **`SummaryService` 不加 `@Transactional`**：AI 调用三十秒，会把 Druid 的 `max-active: 20` 占死。它靠状态机（`summaryStatus`）而不是事务来保证一致性。
- **SSE 走独立线程池**（`sseExecutor`），小结走 `summaryExecutor`。用 `AbortPolicy` 不用 `CallerRunsPolicy`——后者会让 Tomcat 请求线程去跑 AI 调用。
- **`incrementalOutput(true)` 必须开**：不开的话流式的每一片都是累积全文，前端拿到的字数会翻几十倍（实测 37113 字 vs 681 字）。

## License

MIT
