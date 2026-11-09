package com.mediquery.module.knowledge.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mediquery.module.knowledge.dto.NormalizedSymptom;
import com.mediquery.module.knowledge.entity.Symptom;
import com.mediquery.module.knowledge.entity.SymptomAlias;
import com.mediquery.module.knowledge.mapper.SymptomAliasMapper;
import com.mediquery.module.knowledge.mapper.SymptomMapper;
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
 * 症状归一化：真连 H2、真走 MyBatis-Plus。
 *
 * <h3>为什么这层必须用真库测</h3>
 *
 * <p>归一化最容易错的地方不是匹配算法本身，是 {@code selectVocabulary} 那条
 * {@code UNION ALL}：标准名与别名有没有真的拍平、停用症状有没有被排除、
 * 权重有没有被正确带出。这些全是 SQL 层的事，用 Mock 的 Mapper
 * 喂一个列表进去，等于把要测的东西整个替换掉了 —— 测试会绿，
 * 而生产上「睡不着」照样召不到「失眠」。
 *
 * <h3>为什么每条用例都要 invalidate</h3>
 *
 * <p>{@link SymptomNormalizer} 把词表缓存在实例字段里，TTL 五分钟。
 * 同一个 Spring 上下文里所有用例共用这一个实例，不主动失效的话，
 * 第二条用例看到的是第一条用例入库的词表 —— 断言会莫名其妙地失败，
 * 而且第一次跑过、第二次跑挂。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:mediquery_symptom;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "jwt.secret=test-secret-key-must-be-at-least-256-bits-long-for-hs256",
        "mediquery.retrieval.strategy=like"
})
class SymptomNormalizerTest {

    @Autowired
    private SymptomNormalizer normalizer;

    @Autowired
    private SymptomMapper symptomMapper;

    @Autowired
    private SymptomAliasMapper aliasMapper;

    @BeforeEach
    void cleanUp() {
        // BlockAttackInnerInterceptor 会拦无 where 的全表删除，所以都要带条件
        aliasMapper.delete(Wrappers.<SymptomAlias>lambdaQuery().gt(SymptomAlias::getId, 0));
        symptomMapper.delete(Wrappers.<Symptom>lambdaQuery().gt(Symptom::getId, 0));
        normalizer.invalidate();
    }

    private Integer symptom(String name, String bodyPart, String category, int status) {
        var s = new Symptom();
        s.setName(name);
        s.setBodyPart(bodyPart);
        s.setCategory(category);
        s.setStatus(status);
        symptomMapper.insert(s);
        return s.getId();
    }

    private void alias(Integer symptomId, String text, String weight) {
        var a = new SymptomAlias();
        a.setSymptomId(symptomId);
        a.setAlias(text);
        a.setWeight(new BigDecimal(weight));
        aliasMapper.insert(a);
    }

    @Test
    @DisplayName("口语说法经别名归一到标准症状")
    void colloquialPhraseIsNormalized() {
        Integer strain = symptom("排便费力", "腹部", "消化", 1);
        alias(strain, "拉不出来", "0.95");
        alias(strain, "蹲半天", "0.75");
        normalizer.invalidate();

        var hits = normalizer.normalize("我拉不出来，蹲半天了");

        // 两个别名指向同一个症状，只应产出一条 —— 同一句话里重复说同一件事
        // 不该让疾病打分翻倍
        assertThat(hits).extracting(NormalizedSymptom::getSymptomName)
                .containsExactly("排便费力");
        // matchedText 保留原话，日志和「你说的是X」都靠它
        assertThat(hits.get(0).getMatchedText()).isEqualTo("拉不出来");
        // 同一症状命中多次时取权重最高的那次，「蹲半天」的 0.75 不该覆盖 0.95
        assertThat(hits.get(0).getWeight()).isEqualByComparingTo("0.95");
    }

    @Test
    @DisplayName("最长匹配优先：词表里同时有「喉咙」和「喉咙痛」时命中长的那个")
    void longestMatchWins() {
        Integer throat = symptom("咽部不适", "咽喉", "呼吸", 1);
        Integer soreThroat = symptom("咽痛", "咽喉", "呼吸", 1);
        alias(throat, "喉咙", "0.80");
        alias(soreThroat, "喉咙痛", "0.95");
        normalizer.invalidate();

        var hits = normalizer.normalize("喉咙痛得咽不下东西");

        // 如果短词先匹配，这里会是「咽部不适」，而且「痛」会变成没人认领的碎片
        assertThat(hits).extracting(NormalizedSymptom::getSymptomName)
                .containsExactly("咽痛");
    }

    @Test
    @DisplayName("否定词过滤：「没有发烧」不该归一成发热")
    void negatedSymptomIsSkipped() {
        Integer fever = symptom("发热", "全身", "全身", 1);
        alias(fever, "发烧", "0.95");
        normalizer.invalidate();

        assertThat(normalizer.normalize("没有发烧，也不咳嗽")).isEmpty();
        // 同一句话里没被否定的说法仍然要认出来
        assertThat(normalizer.normalize("有点发烧"))
                .extracting(NormalizedSymptom::getSymptomName)
                .containsExactly("发热");
    }

    @Test
    @DisplayName("双否定不算否定：「不排除发烧」应当命中发热")
    void doubleNegationStillMatches() {
        Integer fever = symptom("发热", "全身", "全身", 1);
        alias(fever, "发烧", "0.95");
        normalizer.invalidate();

        // 「不排除」是医生的保留说法，语义上是「很可能是」，
        // 当成否定会直接漏掉危险信号
        assertThat(normalizer.normalize("不排除发烧"))
                .extracting(NormalizedSymptom::getSymptomName)
                .containsExactly("发热");
    }

    @Test
    @DisplayName("停用的症状不出现在词表里")
    void disabledSymptomIsNotInVocabulary() {
        Integer retired = symptom("旧症状名", "全身", "其他", 0);
        alias(retired, "旧说法", "1.00");
        normalizer.invalidate();

        // 停用是「这个名字不用了」，不是「这个病没有了」，
        // 所以只屏蔽说法、不删关联 —— 但至少不能靠它召回疾病
        assertThat(normalizer.normalize("旧说法")).isEmpty();
    }

    @Test
    @DisplayName("空输入返回空列表，不抛异常")
    void blankInputReturnsEmpty() {
        assertThat(normalizer.normalize(null)).isEmpty();
        assertThat(normalizer.normalize("")).isEmpty();
        assertThat(normalizer.normalize("   ")).isEmpty();
    }
}
