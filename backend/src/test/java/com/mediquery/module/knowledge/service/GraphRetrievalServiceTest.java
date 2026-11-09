package com.mediquery.module.knowledge.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mediquery.module.illness.entity.Illness;
import com.mediquery.module.illness.entity.IllnessMedicineRel;
import com.mediquery.module.illness.mapper.IllnessMapper;
import com.mediquery.module.illness.mapper.IllnessMedicineRelMapper;
import com.mediquery.module.knowledge.entity.DiseaseDepartment;
import com.mediquery.module.knowledge.entity.DiseaseSymptom;
import com.mediquery.module.knowledge.entity.Symptom;
import com.mediquery.module.knowledge.entity.SymptomAlias;
import com.mediquery.module.knowledge.mapper.DiseaseDepartmentMapper;
import com.mediquery.module.knowledge.mapper.DiseaseSymptomMapper;
import com.mediquery.module.knowledge.mapper.SymptomAliasMapper;
import com.mediquery.module.knowledge.mapper.SymptomMapper;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两跳召回：症状 → 疾病 → 科室/用药，真连 H2。
 *
 * <h3>这个测试要钉住的是什么</h3>
 *
 * <p>不是「能不能查出来」，而是三个具体的口径：
 * <ol>
 *   <li><b>排序</b>：{@code SUM(weight)} 高的病在前，同分时命中核心症状多的在前。
 *       排序写反了，一次问诊里排第一的就是个不相关的病，用户直接失去信任；</li>
 *   <li><b>截断</b>：候选二十个，注入上下文的只有 Top3。不截断，
 *       模型会被一堆边缘疾病带跑，而且 prompt 会撑满；</li>
 *   <li><b>红旗不参与打分</b>：{@code hasRedFlag} 是独立字段。
 *       如果把它塞进 SUM，一个「命中五个平常症状」的感冒会压过
 *       「命中一个红旗症状」的心梗。</li>
 * </ol>
 *
 * <p>科室与用药的批量装载也在这里验：它们的 SQL 是另外两条，写错了
 * 主链路照样跑得通、只是永远少一节上下文 —— 那种静默降级最难发现。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:mediquery_graph;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "jwt.secret=test-secret-key-must-be-at-least-256-bits-long-for-hs256",
        "mediquery.retrieval.strategy=like"
})
class GraphRetrievalServiceTest {

    @Autowired
    private GraphRetrievalService graphRetrievalService;

    @Autowired
    private SymptomNormalizer normalizer;

    @Autowired
    private SymptomMapper symptomMapper;

    @Autowired
    private SymptomAliasMapper aliasMapper;

    @Autowired
    private DiseaseSymptomMapper diseaseSymptomMapper;

    @Autowired
    private DiseaseDepartmentMapper departmentMapper;

    @Autowired
    private IllnessMapper illnessMapper;

    @Autowired
    private MedicineMapper medicineMapper;

    @Autowired
    private IllnessMedicineRelMapper relMapper;

    @BeforeEach
    void cleanUp() {
        // 从外到内清：边先于实体，否则外键语义上的孤儿数据会串到下一个用例
        departmentMapper.delete(Wrappers.<DiseaseDepartment>lambdaQuery().gt(DiseaseDepartment::getId, 0));
        diseaseSymptomMapper.delete(Wrappers.<DiseaseSymptom>lambdaQuery().gt(DiseaseSymptom::getId, 0));
        relMapper.delete(Wrappers.<IllnessMedicineRel>lambdaQuery().gt(IllnessMedicineRel::getId, 0));
        medicineMapper.delete(Wrappers.<Medicine>lambdaQuery().gt(Medicine::getId, 0));
        illnessMapper.delete(Wrappers.<Illness>lambdaQuery().gt(Illness::getId, 0));
        aliasMapper.delete(Wrappers.<SymptomAlias>lambdaQuery().gt(SymptomAlias::getId, 0));
        symptomMapper.delete(Wrappers.<Symptom>lambdaQuery().gt(Symptom::getId, 0));
        normalizer.invalidate();
    }

    private Integer symptom(String name) {
        var s = new Symptom();
        s.setName(name);
        s.setBodyPart("腹部");
        s.setCategory("消化");
        s.setStatus(1);
        symptomMapper.insert(s);
        return s.getId();
    }

    private Integer illness(String name, String symptomText) {
        var i = new Illness();
        i.setCategoryId(2);
        i.setName(name);
        i.setCause("测试用");
        i.setSymptom(symptomText);
        i.setViewCount(0);
        i.setStatus(1);
        illnessMapper.insert(i);
        return i.getId();
    }

    private void edge(Integer illnessId, Integer symptomId, String weight, boolean cardinal, boolean redFlag) {
        var e = new DiseaseSymptom();
        e.setIllnessId(illnessId);
        e.setSymptomId(symptomId);
        e.setWeight(new BigDecimal(weight));
        e.setIsCardinal(cardinal);
        e.setIsRedFlag(redFlag);
        diseaseSymptomMapper.insert(e);
    }

    private void department(Integer illnessId, String name, int priority, String reason) {
        var d = new DiseaseDepartment();
        d.setIllnessId(illnessId);
        d.setDepartment(name);
        d.setPriority(priority);
        d.setReason(reason);
        departmentMapper.insert(d);
    }

    @Test
    @DisplayName("两跳召回：口语主诉经症状归一后命中疾病，并带出科室")
    void twoHopRetrievalCarriesDepartments() {
        Integer strain = symptom("排便费力");
        var a = new SymptomAlias();
        a.setSymptomId(strain);
        a.setAlias("拉不出来");
        a.setWeight(new BigDecimal("0.95"));
        aliasMapper.insert(a);

        Integer constipation = illness("便秘", "排便次数减少、粪便干硬");
        edge(constipation, strain, "0.90", true, false);
        department(constipation, "内科", 1, "功能性便秘先看消化内科");
        normalizer.invalidate();

        var result = graphRetrievalService.retrieve("我拉不出来");

        assertThat(result.isEmpty()).isFalse();
        assertThat(result.entries()).hasSize(1);
        var entry = result.entries().get(0);
        assertThat(entry.disease().getIllnessName()).isEqualTo("便秘");
        assertThat(entry.primaryDepartment()).isEqualTo("内科");
        // 归一出来的症状一起带出去，前端才能显示「你说的是拉不出来，理解为排便费力」
        assertThat(result.symptoms()).extracting("symptomName").containsExactly("排便费力");
    }

    @Test
    @DisplayName("SUM(weight) 高的排前面；同分时核心症状多的排前面")
    void heavierAndMoreCardinalWins() {
        Integer cough = symptom("咳嗽");
        Integer fever = symptom("发热");
        Integer sputum = symptom("咳痰");

        // 甲：命中两个症状，权重 0.50 + 0.50 = 1.00，都不是核心
        Integer light = illness("轻症甲", "");
        edge(light, cough, "0.50", false, false);
        edge(light, fever, "0.50", false, false);

        // 乙：命中一个症状，权重 0.90，是核心 —— 分数低但更「像」
        Integer core = illness("核心乙", "");
        edge(core, cough, "0.90", true, false);

        // 丙：命中三个，总分 1.20，排第一
        Integer heavy = illness("高分丙", "");
        edge(heavy, cough, "0.40", false, false);
        edge(heavy, fever, "0.40", false, false);
        edge(heavy, sputum, "0.40", false, false);

        normalizer.invalidate();

        // 输入必须真的命中三个症状：上一版写成「有痰」，
        // 而标准症状名是「咳痰」—— 归一化不认「有痰」，第三个症状压根没命中
        var result = graphRetrievalService.retrieve("咳嗽发热咳痰");

        assertThat(result.entries()).extracting(e -> e.disease().getIllnessName())
                .containsExactly("高分丙", "轻症甲", "核心乙");
    }

    @Test
    @DisplayName("只注入 Top3：候选二十个也只带三个进上下文")
    void onlyTopThreeAreInjected() {
        Integer cough = symptom("咳嗽");
        for (int i = 1; i <= 6; i++) {
            Integer id = illness("疾病" + i, "");
            // 权重递减，保证排序稳定
            edge(id, cough, "0." + (90 - i * 10), false, false);
        }
        normalizer.invalidate();

        var result = graphRetrievalService.retrieve("咳嗽");

        assertThat(result.entries()).hasSize(GraphRetrievalService.TOP_K);
        assertThat(result.entries()).extracting(e -> e.disease().getIllnessName())
                .containsExactly("疾病1", "疾病2", "疾病3");
    }

    @Test
    @DisplayName("用药边必须带适应症，没写适应症的药不会被带出来")
    void medicineWithoutIndicationIsNotCarried() {
        Integer bone = symptom("骨擦感");
        Integer fracture = illness("骨折", "畸形、异常活动");
        edge(fracture, bone, "0.90", true, true);
        department(fracture, "外科", 1, "疑似骨折先固定再看外科");

        var painkiller = new Medicine();
        painkiller.setName("布洛芬");
        painkiller.setStatus(1);
        medicineMapper.insert(painkiller);

        var rel = new IllnessMedicineRel();
        rel.setIllnessId(fracture);
        rel.setMedicineId(painkiller.getId());
        // 刻意不写 indication：这条边本来就是「不知道为什么要关联」的那种
        rel.setLineOfTreatment(0);
        rel.setEvidenceLevel(0);
        relMapper.insert(rel);
        normalizer.invalidate();

        // 直接说标准名：本条要验的是用药边的过滤，不是归一化
        var result = graphRetrievalService.retrieve("骨擦感");

        // 边还在（Medicines 非空），但格式化层会按 indication 过滤掉
        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().get(0).medicines()).hasSize(1);
        assertThat(result.entries().get(0).medicines().get(0).getIndication()).isNull();
    }

    @Test
    @DisplayName("红旗单独带出，不参与分数 —— 高危病不能因为症状少就沉底")
    void redFlagIsCarriedSeparately() {
        Integer chestPain = symptom("剧烈胸痛");
        Integer cough = symptom("咳嗽");
        Integer fever = symptom("发热");
        Integer sputum = symptom("咳痰");

        // 感冒：三个平常症状，总分 1.50
        Integer cold = illness("感冒", "");
        edge(cold, cough, "0.50", false, false);
        edge(cold, fever, "0.50", false, false);
        edge(cold, sputum, "0.50", false, false);

        // 心梗：一个红旗症状，总分 0.30
        Integer mi = illness("急性心肌梗死", "");
        edge(mi, chestPain, "0.30", false, true);
        normalizer.invalidate();

        var result = graphRetrievalService.retrieve("剧烈胸痛还咳嗽发热咳痰");

        var entry = result.entries().stream()
                .filter(e -> e.disease().getIllnessName().equals("急性心肌梗死"))
                .findFirst()
                .orElseThrow();
        // 分数低，但红旗标记必须为真 —— 格式化层靠它把这条单列进「需要立即就医」
        assertThat(entry.disease().getHasRedFlag()).isTrue();
        assertThat(result.entries().get(0).disease().getIllnessName()).isEqualTo("感冒");
    }

    @Test
    @DisplayName("没有命中症状时返回空结果，不抛异常也不退回全表")
    void noSymptomHitReturnsEmpty() {
        Integer cough = symptom("咳嗽");
        Integer cold = illness("感冒", "");
        edge(cold, cough, "0.90", true, false);
        normalizer.invalidate();

        var result = graphRetrievalService.retrieve("我最近工作压力有点大");

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.entries()).isEmpty();
    }

    @Test
    @DisplayName("逻辑删除或下架的疾病不参与召回")
    void unpublishedIllnessIsSkipped() {
        Integer cough = symptom("咳嗽");

        Integer published = illness("已发布", "");
        edge(published, cough, "0.50", false, false);

        // 直接以 status=0 入库：先建再改要多一次 update，没有额外价值
        var draft = new Illness();
        draft.setCategoryId(2);
        draft.setName("草稿");
        draft.setCause("测试用");
        draft.setSymptom("");
        draft.setViewCount(0);
        draft.setStatus(0);
        illnessMapper.insert(draft);
        // 草稿的权重更高 —— 如果查询漏了 status 过滤，它会排到第一位
        edge(draft.getId(), cough, "0.90", true, false);

        normalizer.invalidate();

        var result = graphRetrievalService.retrieve("咳嗽");

        assertThat(result.entries()).extracting(e -> e.disease().getIllnessName())
                .containsExactly("已发布");
    }
}
