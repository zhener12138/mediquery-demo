package com.mediquery.module.knowledge.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediquery.module.knowledge.dto.SymptomHit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索质量基线：Recall@3 / MRR / 科室一致率 / 红旗召回。
 *
 * <h3>为什么要有这个测试</h3>
 *
 * <p>前面那套两跳链路做完，所有人都会问「那它到底准不准」，而在此之前
 * 唯一的答案是「我试了几个例子感觉还行」。感觉不能进 CI，也不能在改了
 * 打分公式之后回答「变好了还是变坏了」——每次改动都得靠它。
 *
 * <h3>为什么用真实种子数据，而不是 mock</h3>
 *
 * <p>评测的对象是「这套数据 + 这套算法」合起来的效果。用 mock 喂几条手工
 * 编的边，量出来的是 mock 的准确率。所以这里把 {@code seed.sql} 与
 * {@code seed-knowledge.sql} 原样灌进 H2，跑的就是生产的那 10 个病。
 *
 * <h3>指标口径</h3>
 *
 * <ul>
 *   <li><b>Recall@3</b>：Top3 里只要有一个是期望疾病就算命中。
 *       医疗场景宁可给三个候选让人挑，也不追求「榜首唯一正确」；</li>
 *   <li><b>MRR</b>：第一个正确疾病的排名倒数。榜首正确=1.0，第二位=0.5，第三位=0.33。
 *       Recall 看不出「排第一还是排第三」，MRR 看得到；</li>
 *   <li><b>科室一致率</b>：榜首疾病的建议科室 == 期望科室；</li>
 *   <li><b>红旗召回</b>：标注了 {@code expectRedFlag=true} 的用例，必须报出红旗。
 *       这一项没有「百分比及格线」，漏一个就是错的；</li>
 *   <li><b>误报</b>：期望疾病为空的用例（知识库里确实没有这种病），
 *       不该硬凑出候选。</li>
 * </ul>
 *
 * <p>数字本身会打印出来，跑一次 {@code mvn test -Dtest=RetrievalEvalTest} 就能看到全表。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:mediquery_eval;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "jwt.secret=test-secret-key-must-be-at-least-256-bits-long-for-hs256",
        "mediquery.retrieval.strategy=like"
})
class RetrievalEvalTest {

    /** 及格线。改了检索逻辑之后数字掉了，这里先红。 */
    private static final double MIN_RECALL_AT_3 = 0.55;
    private static final double MIN_MRR = 0.45;
    private static final double MIN_DEPARTMENT_AGREEMENT = 0.45;

    private static final Path SEED = Paths.get("src/main/resources/db/seed.sql");
    private static final Path SEED_KNOWLEDGE = Paths.get("src/main/resources/db/seed-knowledge.sql");
    private static final Path CASES = Paths.get("src/test/resources/eval/retrieval-cases.json");

    @Autowired
    private GraphRetrievalService graphRetrievalService;

    @Autowired
    private SymptomNormalizer normalizer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    private List<EvalCase> cases;

    /** 用例：一条主诉 + 期望。{@code expectDiseases} 为空表示「知识库确实没有这类病」。 */
    record EvalCase(String id,
                    String complaint,
                    List<String> expectDiseases,
                    String expectDepartment,
                    Boolean expectRedFlag,
                    String note) {
    }

    @BeforeAll
    void loadSeedData() throws Exception {
        jdbcTemplate.execute("DELETE FROM disease_department");
        jdbcTemplate.execute("DELETE FROM disease_symptom");
        jdbcTemplate.execute("DELETE FROM illness_medicine_rel");
        jdbcTemplate.execute("DELETE FROM medicine");
        jdbcTemplate.execute("DELETE FROM illness");
        jdbcTemplate.execute("DELETE FROM symptom_alias");
        jdbcTemplate.execute("DELETE FROM symptom");
        jdbcTemplate.execute("DELETE FROM illness_category");

        runScript(SEED);
        runScript(SEED_KNOWLEDGE);
        normalizer.invalidate();

        cases = new ObjectMapper().readValue(Files.readString(CASES, StandardCharsets.UTF_8),
                new TypeReference<List<EvalCase>>() {
                });
    }

    /**
     * 跑一份种子脚本。
     *
     * <p>只做一件事：把 {@code USE mediquery;} 删掉。那是给人手工执行时用的
     * 上下文切换，H2 没有库这个概念，留着会直接报语法错。
     * 其余语句原样交给 Spring 的 ScriptUtils —— 它认识注释和字符串里的分号，
     * 比自己 {@code split(";")} 安全。
     */
    private void runScript(Path path) throws Exception {
        String sql = Files.readString(path, StandardCharsets.UTF_8)
                .lines()
                .filter(line -> !line.trim().toUpperCase().startsWith("USE "))
                .collect(Collectors.joining("\n"));
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /**
     * 用例集自身的正确性：文件名对不对、疾病名有没有写错。
     *
     * <p>这条不是形式主义。{@code expectDiseases} 里写一个知识库里不存在的
     * 病名，Recall 会永远算成未命中，而人看到的是一个「命中率偏低」的假象，
     * 去翻检索算法 —— 实际上是用例写错了。
     */
    @Test
    @DisplayName("用例集：疾病名与科室名都真实存在于知识库")
    void datasetReferencesRealEntities() {
        Set<String> illnessNames = new LinkedHashSet<>(
                jdbcTemplate.queryForList("SELECT name FROM illness", String.class));
        Set<String> categories = new LinkedHashSet<>(
                jdbcTemplate.queryForList("SELECT name FROM illness_category", String.class));

        assertThat(illnessNames).contains("病毒性感冒", "便秘", "骨折");
        assertThat(cases).hasSizeGreaterThanOrEqualTo(30);
        assertThat(cases.stream().map(EvalCase::id).distinct().count())
                .as("用例 id 不能重复，否则报表里对不上是哪一条")
                .isEqualTo(cases.size());

        var badNames = cases.stream()
                .flatMap(c -> c.expectDiseases().stream())
                .filter(name -> !illnessNames.contains(name))
                .distinct()
                .toList();
        assertThat(badNames).as("用例里写了知识库不存在的疾病").isEmpty();

        var badDepartments = cases.stream()
                .map(EvalCase::expectDepartment)
                .filter(name -> name != null)
                .filter(name -> !categories.contains(name))
                .distinct()
                .toList();
        assertThat(badDepartments).as("用例里写了不存在的科室").isEmpty();
    }

    @Test
    @DisplayName("检索质量：Recall@3 / MRR / 科室一致率 / 红旗召回")
    void retrievalQualityMeetsBaseline() {
        int withExpectation = 0;
        int recallHits = 0;
        double reciprocalRankSum = 0;
        int departmentChecks = 0;
        int departmentHits = 0;
        int redFlagChecks = 0;
        int redFlagHits = 0;
        int negativeCases = 0;
        int falsePositives = 0;

        var report = new ArrayList<String>();
        report.add(String.format("%-5s %-6s %-4s %-6s %s",
                "id", "Hit@3", "RR", "科室", "Top3（期望：" + "…" + "）"));

        for (EvalCase evalCase : cases) {
            var result = graphRetrievalService.retrieve(evalCase.complaint());
            var names = result.entries().stream()
                    .map(entry -> entry.disease().getIllnessName())
                    .toList();

            String hit = "-";
            String rr = "-";
            String dept = "-";

            if (evalCase.expectDiseases().isEmpty()) {
                negativeCases++;
                boolean noisy = !names.isEmpty();
                if (noisy) {
                    falsePositives++;
                }
                hit = noisy ? "误报" : "空";
            } else {
                withExpectation++;
                int rank = firstMatchingRank(names, evalCase.expectDiseases());
                if (rank > 0) {
                    recallHits++;
                    reciprocalRankSum += 1.0 / rank;
                }
                hit = rank > 0 ? "Y" : "N";
                rr = rank > 0 ? String.format("%.2f", 1.0 / rank) : "0.00";

                if (evalCase.expectDepartment() != null) {
                    departmentChecks++;
                    String actual = result.entries().isEmpty()
                            ? null
                            : result.entries().get(0).primaryDepartment();
                    if (actual != null && actual.equals(evalCase.expectDepartment())) {
                        departmentHits++;
                        dept = "Y";
                    } else {
                        dept = "N(" + actual + ")";
                    }
                }

                if (evalCase.expectRedFlag() != null) {
                    redFlagChecks++;
                    boolean actual = result.entries().stream()
                            .anyMatch(entry -> Boolean.TRUE.equals(entry.disease().getHasRedFlag()));
                    if (actual == evalCase.expectRedFlag()) {
                        redFlagHits++;
                    }
                }
            }

            report.add(String.format("%-5s %-6s %-4s %-6s %s",
                    evalCase.id(), hit, rr, dept, String.join(" / ", names)));
        }

        double recall = withExpectation == 0 ? 0 : (double) recallHits / withExpectation;
        double mrr = withExpectation == 0 ? 0 : reciprocalRankSum / withExpectation;
        double deptRate = departmentChecks == 0 ? 0 : (double) departmentHits / departmentChecks;

        String summary = String.format(
                "EVAL cases=%d 有期望=%d 无期望=%d | Recall@3=%.1f%% (%d/%d) | MRR=%.3f | "
                        + "科室一致=%.1f%% (%d/%d) | 红旗=%d/%d | 误报=%d/%d",
                cases.size(), withExpectation, negativeCases,
                recall * 100, recallHits, withExpectation, mrr,
                deptRate * 100, departmentHits, departmentChecks,
                redFlagHits, redFlagChecks, falsePositives, negativeCases);

        // surefire 默认吞掉 System.out，所以同时落一份文件；
        // 调排序/改公式的时候，这张表比断言失败信息有用得多
        writeReport(report, summary);
        report.forEach(System.out::println);
        System.out.println();
        System.out.println(summary);

        // 红旗是硬要求：漏一个就意味着一个该去急诊的人被告知「没事」
        assertThat(redFlagHits)
                .as("红旗召回必须全中，漏一个就是安全事故")
                .isEqualTo(redFlagChecks);

        assertThat(recall).as("Recall@3 低于基线，检索退化了").isGreaterThanOrEqualTo(MIN_RECALL_AT_3);
        assertThat(mrr).as("MRR 低于基线，正确病种被挤到后面了").isGreaterThanOrEqualTo(MIN_MRR);
        assertThat(deptRate).as("科室一致率低于基线").isGreaterThanOrEqualTo(MIN_DEPARTMENT_AGREEMENT);
    }

    private int firstMatchingRank(List<String> names, List<String> expected) {
        for (int i = 0; i < names.size(); i++) {
            if (expected.contains(names.get(i))) {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * 把报表写到 {@code target/retrieval-eval.txt}。
     *
     * <p>不是为了好看：surefire 在 Windows 上经常连自己的临时目录都建不出来，
     * 控制台输出整段丢失（只剩一行「Unable to create temporary file」）。
     * 落文件是最省事的兜底 —— 而且这个文件在下一次 {@code mvn clean} 之前一直在，
     * 可以拿两次的结果直接 diff。
     */
    private void writeReport(List<String> lines, String summary) {
        try {
            Path out = Paths.get("target", "retrieval-eval.txt");
            Files.createDirectories(out.getParent());
            var content = new StringBuilder();
            lines.forEach(line -> content.append(line).append('\n'));
            content.append('\n').append(summary).append('\n');
            Files.writeString(out, content.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 报表写不出来不该让评测失败，数字已经在断言里了
            System.out.println("评测报表写入失败：" + e.getMessage());
        }
    }

    /** 备用：把结果打印成「疾病名 + 命中分数」，排查排序问题时用。 */
    @SuppressWarnings("unused")
    private String describe(List<SymptomHit> hits) {
        return hits.stream()
                .map(hit -> hit.getIllnessName() + "=" + hit.getScore())
                .collect(Collectors.joining(", "));
    }

    /** 备用：查看某条主诉归一到了哪些症状，排查召回为空的用例时最有用的一个。 */
    @SuppressWarnings("unused")
    private Map<String, Object> traceNormalization(String text) {
        return normalizer.normalize(text).stream()
                .collect(Collectors.toMap(
                        s -> s.getSymptomName(),
                        s -> s.getMatchedText() + "/" + s.getWeight(),
                        (a, b) -> a));
    }
}
