package com.mediquery.config;

import com.mediquery.module.illness.entity.Illness;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 Redis 序列化白名单真的在拦东西，而不是配了个看着安全的类名。
 *
 * <h3>这个测试在防什么</h3>
 *
 * <p>Redis 缓存的反序列化漏洞有一个特点：配错了不会报错，只在被攻击时才有反应。
 * 所以"配置看起来对不对"完全没有意义，必须拿真实的恶意 payload 打一发，
 * 看它是不是真的被拒。
 *
 * <h3>测哪些</h3>
 *
 * <ul>
 *   <li>正常路径：我们自己的实体能存能取，时间字段不丢——否则改完安全把功能改坏了；</li>
 *   <li>List/Map 这类集合能还原——白名单里必须包含 java.util，漏了缓存直接不可用；</li>
 *   <li>白名单之外的类被拒——这是安全性的核心断言。</li>
 * </ul>
 */
class RedisConfigTest {

    /**
     * 用和 RedisConfig 完全一样的规则构造序列化器。
     *
     * <p>这里没有直接 new RedisConfig() 调私有方法，是因为方法私有。
     * 之所以还这么写：如果 RedisConfig 的规则改了而测试没跟上，
     * {@link #whitelistRejectsForeignClass()} 会因为期望它拒绝却没拒绝而失败。
     * 换句话说，规则一放宽，这个测试就红。
     */
    private GenericJackson2JsonRedisSerializer serializer() throws Exception {
        var m = RedisConfig.class.getDeclaredMethod("createJsonSerializer");
        m.setAccessible(true);
        return (GenericJackson2JsonRedisSerializer) m.invoke(new RedisConfig());
    }

    @Test
    void entityRoundTripKeepsFields() throws Exception {
        var s = serializer();

        Illness illness = new Illness();
        illness.setId(7);
        illness.setCategoryId(2);
        illness.setName("病毒性感冒");
        illness.setCause("病毒感染");
        illness.setSymptom("发热、咳嗽");
        illness.setViewCount(12);
        illness.setStatus(1);
        illness.setCreateTime(LocalDateTime.of(2026, 3, 1, 10, 0));

        byte[] bytes = s.serialize(illness);
        Object back = s.deserialize(bytes);

        assertInstanceOf(Illness.class, back, "反序列化应还原成 Illness，而不是 LinkedHashMap");
        Illness got = (Illness) back;

        assertEquals(7, got.getId());
        assertEquals("病毒性感冒", got.getName());
        assertEquals(12, got.getViewCount());
        assertEquals(LocalDateTime.of(2026, 3, 1, 10, 0), got.getCreateTime(),
                "LocalDateTime 丢了说明 JavaTimeModule 或 java.time 白名单有问题");
    }

    @Test
    void collectionsRoundTrip() throws Exception {
        var s = serializer();

        List<Illness> list = new ArrayList<>();
        Illness a = new Illness();
        a.setName("甲");
        Illness b = new Illness();
        b.setName("乙");
        list.add(a);
        list.add(b);

        Object back = s.deserialize(s.serialize(list));

        assertInstanceOf(List.class, back, "List 白名单漏了的话这里会是别的类型");
        @SuppressWarnings("unchecked")
        List<Illness> got = (List<Illness>) back;
        assertEquals(2, got.size());
        assertEquals("甲", got.get(0).getName());
    }

    /**
     * 核心安全断言：白名单之外的类必须被拒。
     *
     * <p>靶子用的是 {@code com.evilprobe.DeserializationProbe}，不是 {@code java.lang.Runtime}。
     *
     * <p>这是一次返工。第一版拿 Runtime 当靶子，断言"会抛异常"——但 Runtime 没有公有无参构造器，
     * 就算白名单完全放开，Jackson 也实例化不出来，异常照样抛。那条测试无论防线在不在都是绿的，
     * 等于什么都没测（我是把白名单临时改回 LaissezFaire 做变异验证时发现的）。
     *
     * <p>换成这个探针类：它不在白名单里，但公有无参构造器 + setter 齐全，
     * 放任模式下它 100% 会被成功建出来。所以这条断言现在真的在区分"有防线"和"没防线"。
     */
    @Test
    void whitelistRejectsForeignClass() throws Exception {
        var s = serializer();

        String payload = "[\"" + com.evilprobe.DeserializationProbe.class.getName() + "\",{\"flag\":\"pwned\"}]";

        assertThrows(Exception.class,
                () -> s.deserialize(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "com.evilprobe 不在白名单里（只允许 com.mediquery / java.util / java.time），"
                        + "反序列化必须失败。通过了说明白名单没生效，Redis 反序列化 RCE 防线等于不存在。");
    }

    /**
     * 再打一发：Spring 内部的类也不该被放行。
     *
     * <p>这条用的 ClassPathXmlApplicationContext 有公有无参构造器，放任模式下能被建出来，
     * 所以它同样具备判别力——不是为了凑数量。
     */
    @Test
    void whitelistRejectsSpringInternalClass() throws Exception {
        var s = serializer();

        String payload = "[\"org.springframework.context.support.ClassPathXmlApplicationContext\",{}]";

        assertThrows(Exception.class,
                () -> s.deserialize(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "白名单只允许 com.mediquery / java.util / java.time，"
                        + "Spring 内部的类必须被拒");
    }
}
