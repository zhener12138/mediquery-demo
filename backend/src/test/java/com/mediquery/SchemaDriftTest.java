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
 * 某天有人给 {@code init.sql} 加了一张表或一个字段，但忘了改 {@code schema-h2.sql}——
 * 测试继续全绿，生产上线直接炸。反过来也一样：测试里验的字段在生产根本不存在。
 *
 * <p>所以这个测试直接读两个 SQL 文件做文本比对，不连数据库。它比对的不是"能不能建表"
 * （那是 {@link ApplicationContextTest} 的事），而是"两份 DDL 描述的是不是同一个模型"。
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
 */
class SchemaDriftTest {

    private static final Path MYSQL_SCHEMA = Paths.get("src/main/resources/db/init.sql");
    private static final Path H2_SCHEMA = Paths.get("src/test/resources/schema-h2.sql");

    /** 匹配 CREATE TABLE `xxx` / CREATE TABLE IF NOT EXISTS `xxx`，` 可有可无 */
    private static final Pattern CREATE_TABLE =
            Pattern.compile("CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?(\\w+)`?",
                    Pattern.CASE_INSENSITIVE);

    @Test
    void bothSchemaFilesExist() {
        assertTrue(Files.exists(MYSQL_SCHEMA), "找不到生产 schema：" + MYSQL_SCHEMA.toAbsolutePath());
        assertTrue(Files.exists(H2_SCHEMA), "找不到测试 schema：" + H2_SCHEMA.toAbsolutePath());
    }

    @Test
    void tableNamesMatch() throws IOException {
        Set<String> mysqlTables = parseTables(read(MYSQL_SCHEMA)).keySet();
        Set<String> h2Tables = parseTables(read(H2_SCHEMA)).keySet();

        Set<String> onlyInMysql = new TreeSet<>(mysqlTables);
        onlyInMysql.removeAll(h2Tables);

        Set<String> onlyInH2 = new TreeSet<>(h2Tables);
        onlyInH2.removeAll(mysqlTables);

        assertTrue(onlyInMysql.isEmpty() && onlyInH2.isEmpty(),
                """
                        init.sql 与 schema-h2.sql 的表名集合不一致。
                        只在 init.sql 里（测试库缺表）：%s
                        只在 schema-h2.sql 里（生产库缺表）：%s
                        修法：把缺的那份补上，顺序和字段都对齐。""".formatted(onlyInMysql, onlyInH2));
    }

    @Test
    void columnNamesMatchPerTable() throws IOException {
        var mysql = parseTables(read(MYSQL_SCHEMA));
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
                        .append("只在 init.sql → ").append(onlyInMysql)
                        .append("；只在 schema-h2.sql → ").append(onlyInH2)
                        .append('\n');
            }
        }

        assertEquals("", problems.toString(),
                "init.sql 与 schema-h2.sql 的字段集合不一致：\n" + problems);
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

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
        return result;
    }

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
        for (String part : splitTopLevel(body)) {
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
