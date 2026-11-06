package com.mediquery.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis 序列化配置。
 *
 * <h3>为什么这里要关心"安全"</h3>
 *
 * <p>缓存里存的是对象，读回来时要把 JSON 还原成具体的类，所以 payload 里必须带上类名
 * （Jackson 的 {@code @class} 字段）。这就带来一个经典风险：如果校验器什么都放行，
 * 攻击者只要能往 Redis 写一个 {@code {"@class":"某个可以执行代码的类", ...}}，
 * 应用在反序列化时就会真的去执行它——不需要接触应用代码，只要碰到 Redis 就行。
 *
 * <h3>原来错在哪</h3>
 *
 * <p>原配置用的是 {@code LaissezFaireSubTypeValidator.instance}，名字很诚实：
 * 法语的"放任不管"。它对任何类名一律放行，等于上面说的防线完全不存在。
 * 有人可能会说"Redis 在内网"——但内网不等于可信，开发机上的 Redis 常年裸奔在 6379。
 *
 * <h3>现在怎么限制</h3>
 *
 * <p>换成 {@link BasicPolymorphicTypeValidator}，只放行四类：
 *
 * <ul>
 *   <li>{@code com.mediquery.} 下的所有类 —— 我们自己的实体和 DTO，可控；</li>
 *   <li>{@code java.util.} —— 缓存的值可能是 List / Map / Set，这些必须能还原；</li>
 *   <li>{@code java.time.} —— LocalDateTime 等，实体的 createTime/updateTime 要用；</li>
 *   <li>{@code java.math.} —— {@code Medicine.price} 是 {@code BigDecimal}。
 *       这条是后来补的：少了它，药品详情首次走库正常、再次命中缓存 500。</li>
 * </ul>
 *
 * <p>其余一律拒绝。将来如果新加了实体在别的包下，读缓存会抛
 * {@code InvalidTypeIdException}，那时把它加进白名单即可——
 * 这个报错本身就是提醒：有个不在白名单里的类被序列化进去了。
 *
 * <h3>不用 LaissezFaire 之后，序列化格式变了吗</h3>
 *
 * <p>变了，但只是"能不能读回来"的规则变了，payload 里仍然写 {@code @class}。
 * 唯一要注意的是：旧版本写进 Redis 的缓存里可能有过白名单之外的类型，
 * 那些键读回来会失败。缓存本来就是可以丢的数据，清掉即可（或等 TTL 过期）。
 */
@Configuration
@EnableCaching
public class RedisConfig {

    /**
     * 只允许这几类包名被反序列化。
     *
     * <p>写成常量而不是内联，是为了让"白名单有哪几条"可以被一眼看到、被测试断言。
     */
    private static final String[] ALLOWED_PACKAGES = {
            "com.mediquery.",
            "java.util.",
            "java.time.",
            // java.math：Medicine.price 是 BigDecimal。漏掉它的时候，
            // 药品详情第一次查（走库）正常，第二次查（命中缓存）直接
            // SerializationException → 500。缓存往返测试就是为了钉住这类漏洞。
            "java.math.",
    };

    private PolymorphicTypeValidator typeValidator() {
        var builder = BasicPolymorphicTypeValidator.builder();
        for (String pkg : ALLOWED_PACKAGES) {
            builder.allowIfSubType(pkg);
        }
        return builder.build();
    }

    /**
     * 构造序列化器。
     *
     * <p>用 {@link ObjectMapper#activateDefaultTyping(PolymorphicTypeValidator, ObjectMapper.DefaultTyping)}
     * 而不是自己往每个值里塞 {@code @class}，这样缓存代码不用关心类型信息。
     *
     * <p>{@code NON_FINAL} 的含义：所有非 final 类型都写类型信息。
     * 不能改成 {@code NON_CONCRETE_AND_ARRAYS}（太窄，List 的实现类还原不回来），
     * 也不能去掉（去掉之后读回来全是 LinkedHashMap，强转就炸）。
     */
    private GenericJackson2JsonRedisSerializer createJsonSerializer() {
        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.activateDefaultTyping(typeValidator(), ObjectMapper.DefaultTyping.NON_FINAL);
        return new GenericJackson2JsonRedisSerializer(mapper);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        var template = new RedisTemplate<String, Object>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(createJsonSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(createJsonSerializer());
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        var serializer = createJsonSerializer();
        var config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(30))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer))
                // 缓存 null 会让 @Cacheable 把"查不到"也缓存住，而 Redis 不能存 null 值，
                // 会直接抛 IllegalArgumentException。禁止掉，让空值走 unless 分支。
                .disableCachingNullValues();

        return RedisCacheManager.builder(factory)
                .cacheDefaults(config)
                .build();
    }
}
