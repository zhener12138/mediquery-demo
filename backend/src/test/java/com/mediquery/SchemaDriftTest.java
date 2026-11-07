package com.mediquery;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 防漂移：保证测试用的 H2 schema 和生产用的 MySQL schema 表结构一致。
 *
 * <h3>这个测试为什么必须存在</h3>
 *
 * <p>测试跑在 H2 上，生产跑在 MySQL 上，两份 DDL 是分开维护的。这带来一个隐蔽的风险：
 * 某天有人加了一张表或一个字段，但只改了一边——测试继续全绿，生产上线直接炸。
 * 反过来也一样：测试里验的字段在生产根本不存在。
 *
 * <p>所以这个测试直接读 SQL 文件做文本比对，不连数据库。它比对的不是"能不能建表"
 * （那是 {@link ApplicationContextTest} 的事），而是"两边描述的是不是同一个模型"。
 *
 * <h3>为什么读的是 migration 目录而不是 init.sql</h3>
 *
 * <p>2026-11-07 引入 Flyway 之后，生产结构不再是一个静态文件，而是
 * {@code db/migration/V*.sql} 按版本号累加出来的结果：V1 建 8 张表，V2 给
 * consultation 加 4 列。所以本测试把所有迁移脚本拼起来再解析——单看 V1 会以为
 * consultation 少 4 个字段，那是错的。
 *
 * <p>{@code init.sql} 已经删掉了。留着它就得回答「哪份才是真的」，
 * 而两份都真的那天，就已经漂移了。
 *
 * <h3>比对什么</h3>
 *
 * <ul>
 *   <li><b>表名集合</b>：必须完全相等。多一张少一张都算漂移。</li>
 *   <li><b>每张表的字段名集合</b>：必须完全相等（忽略顺序）。</li>
 * </ul>
 *
 * <h3>刻意不比对什么</h3>
 *
 * <p>不比对索引、默认值、注释、引擎、字符集。这些在两边的写法本来就不同
 * （H2 没有 {@code ENGINE=InnoDB}、没有 {@code WITH PARSER ngram}），
 * 硬比会产生大量假阳性，反而让人习惯性忽略这个测试。
 *
 * <p>也挡不住 MySQL 方言写错（比如 ALTER 的语法）、迁移版本顺序错乱。那些只能在
 * 真 MySQL 上跑一遍才知道，属于 CI 的 mysql-schema 作业，不在单元测试里假装能验。
 */
class SchemaDriftTest {

    /** Flyway 迁移目录。生产结构 = 这里所有 V*.sql 按版本号拼接的结果。 */
    private static final Path MYSQL_MIGRATIONS = Paths.get("src/main/resources/db/migration");
    private static final Path H2_SCHEMA = Paths.get("src/test/resources/schema-h2.sql");

    /** 匹配 CREATE TABLE `xxx` / CREATE TABLE IF NOT EXISTS `xxx`，` 可有可无 */
    private static final Pattern CREATE_TABLE =
            Pattern.compile("CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?(\\w+)`?",
                    Pattern.CASE_INSENSITIVE);

    @Test
    void bothSchemaSourcesExist() throws IOException {
        assertTrue(Files.isDirectory(MYSQL_MIGRATIONS),
                "找不到 Flyway 迁移目录：" + MYSQL_MIGRATIONS.toAbsolutePath());
        assertTrue(Files.exists(H2_SCHEMA), "找不到测试 schema：" + H2_SCHEMA.toAbsolutePath());

        try (var files = Files.list(MYSQL_MIGRATIONS)) {
            long count = files.filter(p -> p.getFileName().toString().endsWith(".sql")).count();
            assertTrue(count > 0, "db/migration 下没有任何 .sql 迁移脚本");
        }
    }

    @Test
    void tableNamesMatch() throws IOException {
        Set<String> mysqlTables = parseTables(readMigrations()).keySet();
        Set<String> h2Tables = parseTables(read(H2_SCHEMA)).keySet();

        Set<String> onlyInMysql = new TreeSet<>(mysqlTables);
        onlyInMysql.removeAll(h2Tables);

        Set<String> onlyInH2 = new TreeSet<>(h2Tables);
        onlyInH2.removeAll(mysqlTables);

        assertTrue(onlyInMysql.isEmpty() && onlyInH2.isEmpty(),
                """
                        Flyway 迁移与 schema-h2.sql 的表名集合不一致。
                        只在迁移里（测试库缺表）：%s
                        只在 schema-h2.sql 里（生产库缺表）：%s
                        修法：把缺的那份补上，顺序和字段都对齐。""".formatted(onlyInMysql, onlyInH2));
    }

    @Test
    void columnNamesMatchPerTable() throws IOException {
        var mysql = parseTables(readMigrations());
        var h2 = parseTables(read(H2_SCHEMA));

        StringBuilder problems = new StringBuilder();

        for (var entry : mysql.entrySet()) {
            String table = entry.getKey();
            Set<String> h2Columns = h2.get(table);
            if (h2Columns == null) {
                // 表缺失已经由 tableNamesMatch 报过，这里不重复报
                continue;
            }
            Set<String> onlyInMysql = new TreeSet<>(entry.getValue());
            onlyInMysql.removeAll(h2Columns);

            Set<String> onlyInH2 = new TreeSet<>(h2Columns);
            onlyInH2.removeAll(entry.getValue());

            if (!onlyInMysql.isEmpty() || !onlyInH2.isEmpty()) {
                problems.append("表 ").append(table).append("：")
                        .append("只在迁移里 → ").append(onlyInMysql)
                        .append("；只在 schema-h2.sql → ").append(onlyInH2)
                        .append('\n');
            }
        }

        assertEquals("", problems.toString(),
                "Flyway 迁移与 schema-h2.sql 的字段集合不一致：\n" + problems);
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /**
     * 读迁移目录下所有 .sql 拼成一个字符串。
     *
     * <p>为什么拼起来而不是逐个比：迁移是增量的——V1 建表，V2 加列。
     * 单看 V1 的话 consultation 表少 4 个字段，那是对的。只有把整条历史
     * 拼起来，才等于「库里最终长什么样」。
     *
     * <p>按文件名排序就够了：Flyway 的命名规范是 V{数字}__{描述}.sql，
     * 字符串排序对两位数以上版本号会错序（V10 排在 V2 前），但本项目的
     * 迁移文件是手工维护的个位数版本，且每个文件只碰自己的表。真到两位数
     * 出现顺序问题，这个测试会以「字段缺失」的形式报出来，不会被静默放过。
     */
    private static String readMigrations() throws IOException {
        var sb = new StringBuilder();
        try (var files = Files.list(MYSQL_MIGRATIONS)) {
            var sqls = files.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
            for (Path p : sqls) {
                sb.append("\n-- >>> ").append(p.getFileName()).append('\n');
                sb.append(Files.readString(p, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return sb.toString();
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * 把一份 DDL 文件解析成「表名 → 字段名集合」。
     *
     * <p>解析策略：找到每个 {@code CREATE TABLE}，从它的左括号开始按括号配平找到表定义结束，
     * 再按顶层逗号切分（要跳过括号内的逗号，比如 {@code DECIMAL(10,2)}）。
     * 每一段的第一���单词如果是 {@code PRIMARY}/{@code UNIQUE}/{@code KEY}/{@code INDEX}/
     * {@code CONSTRAINT}/{@code FULLTEXT}，说明是约束不是字段，跳过。
     */
    private static java.util.Map<String, Set<String>> parseTables(String sql) {
        var result = new java.util.LinkedHashMap<String, Set<String>>();
        Matcher m = CREATE_TABLE.matcher(sql);

        while (m.find()) {
            String table = m.group(1).toLowerCase();
            int open = sql.indexOf('(', m.end());
            if (open < 0) {
                continue;
            }
            int close = findMatchingParen(sql, open);
            if (close < 0) {
                continue;
            }
            String body = sql.substring(open + 1, close);
            result.put(table, parseColumns(body));
        }
        applyAlters(sql, result);
        return result;
    }

    /**
     * 把 {@code ALTER TABLE x ADD COLUMN ...} 新增的字段合进已解析的表里。
     *
     * <p>为什么必须做这一步：迁移是增量的。V1 建表时 consultation 只有 11 个字段，
     * V2 用 ALTER 加了 4 个。如果只解析 CREATE TABLE，拼起来的「最终结构」会比
     * 真实的库少 4 列，测试会误报「schema-h2.sql 多了 duration_desc」。
     *
     * <p>支持的形式（本项目实际用到的）：
     * <pre>
     * ALTER TABLE `consultation`
     *     ADD COLUMN `duration_desc` VARCHAR(100) ...,
     *     ADD COLUMN `severity_scale` TINYINT ...;
     * </pre>
     * 不处理 DROP/MODIFY/CHANGE —— 遇到了就当一个字段都没加，宁可漏解析也不要
     * 悄悄把字段算错。真用到时这里应该补上，而不是让它静默通过。
     */
    private static void applyAlters(String sql, java.util.Map<String, Set<String>> tables) {
        Matcher m = ALTER_TABLE.matcher(sql);
        while (m.find()) {
            String table = m.group(1).toLowerCase();
            Set<String> columns = tables.get(table);
            if (columns == null) {
                continue;
            }
            String stmt = m.group(2);
            Matcher add = ADD_COLUMN.matcher(stmt);
            while (add.find()) {
                columns.add(add.group(1).toLowerCase());
            }
        }
    }

    /** 匹配 ALTER TABLE `xxx` 到分号为止的整段 */
    private static final Pattern ALTER_TABLE =
            Pattern.compile("ALTER\\s+TABLE\\s+`?(\\w+)`?(.*?);", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 匹配 ADD COLUMN `xxx` / ADD `xxx` */
    private static final Pattern ADD_COLUMN =
            Pattern.compile("ADD\\s+(?:COLUMN\\s+)?`(\\w+)`", Pattern.CASE_INSENSITIVE);

    private static int findMatchingParen(String s, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static Set<String> parseColumns(String body) {
        Set<String> columns = new LinkedHashSet<>();
        for (String part : splitTopLevel(stripLineComments(body))) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String firstWord = trimmed.split("\\s+", 2)[0]
                    .replace("`", "")
                    .toUpperCase();

            boolean isConstraint = firstWord.equals("PRIMARY")
                    || firstWord.equals("UNIQUE")
                    || firstWord.equals("KEY")
                    || firstWord.equals("INDEX")
                    || firstWord.equals("CONSTRAINT")
                    || firstWord.equals("FULLTEXT")
                    || firstWord.equals("SPATIAL")
                    || firstWord.equals("FOREIGN")
                    || firstWord.equals("CHECK");

            if (!isConstraint) {
                columns.add(firstWord.toLowerCase());
            }
        }
        return columns;
    }

    /**
     * 去掉 {@code --} 行注释。
     *
     * <p>踩过的坑：{@code schema-h2.sql} 的 consultation 表里有一行
     * {@code -- 问诊信息卡（1.1 结构化采集）：可填可不填…}，它夹在两个字段之间。
     * 不去掉的话，按逗号切分后这一段的首个"单词"是 {@code --}，
     * 会被当成一个叫 {@code --} 的字段加进去，测试报「只在 schema-h2.sql → [--]」。
     *
     * <p>只处理整行注释（trim 后以 {@code --} 开头）。行尾注释不处理——
     * 本项目的 DDL 里没有行尾注释，为它写状态机不值得；真出现了会以字段名异常
     * 的形式暴露出来，不会静默错。
     */
    private static String stripLineComments(String body) {
        var sb = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            if (line.trim().startsWith("--")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /**
     * 按顶层逗号切分。要跳过两种"逗号不是分隔符"的情况：
     *
     * <ul>
     *   <li>括号内：{@code DECIMAL(10,2)} 不能切；</li>
     *   <li>单引号内：{@code COMMENT '0-正常, 1-已禁用'} 不能切。</li>
     * </ul>
     *
     * <p>第二种是真正踩过的坑：不加引号判断，注释里的中文逗号会把一列切成两半，
     * 解析出一个叫 {@code 1-已禁用'} 的假字段，测试报一堆看不懂的漂移。
     */
    private static java.util.List<String> splitTopLevel(String body) {
        var parts = new java.util.ArrayList<String>();
        int depth = 0;
        boolean inQuote = false;
        int start = 0;

        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);

            if (c == '\'') {
                // 处理 '' 转义（SQL 里两个单引号表示一个）
                if (inQuote && i + 1 < body.length() && body.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                inQuote = !inQuote;
                continue;
            }

            if (inQuote) {
                continue;
            }

            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(body.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(body.substring(start));
        return parts;
    }
}
