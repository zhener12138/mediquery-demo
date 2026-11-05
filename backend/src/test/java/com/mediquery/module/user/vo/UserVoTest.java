package com.mediquery.module.user.vo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mediquery.module.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UserVO} 的护栏测试。
 *
 * <h3>这些测试在防什么</h3>
 *
 * <p>改之前，{@code /user/me} 返回的是 {@code User} 实体，靠 {@code user.setPassword(null)} 抹掉密码。
 * 那种写法最大的问题不是"当时漏了"，而是 <b>它靠人记住</b>。所以这里的测试不测"现在的代码有没有改对"，
 * 测的是"以后会不会悄悄漏回去"——一旦有人把 {@code password} 字段加进 VO，或者干脆把返回类型改回实体，
 * 这几条断言会立刻红。
 *
 * <p>断言方式是检查 <b>JSON 键</b>而不是 Java 字段：哪怕将来有人改用 {@code @JsonIgnore}，
 * 只要键不出现就算安全，这是真正对外暴露的事实。
 */
class UserVoTest {

    /**
     * 用和生产同一套时间序列化配置的 ObjectMapper。
     *
     * <p>裸 {@code new ObjectMapper()} 序列化 {@code LocalDateTime} 会直接抛
     * {@code InvalidDefinitionException}——Spring Boot 自动配上 JavaTimeModule，
     * 但这里没有容器，得自己装。这跟被测代码无关，是测试环境要自己补齐的部分。
     */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private User fullUser() {
        var u = new User();
        u.setId(7);
        u.setUsername("test");
        u.setPassword("$2a$10$abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMN");
        u.setRealName("张三");
        u.setAge(28);
        u.setSex("男");
        u.setEmail("zhangsan@example.com");
        u.setPhone("13800138000");
        u.setAvatarUrl("https://example.com/a.png");
        u.setRoleStatus(0);
        u.setStatus(1);
        u.setCreateTime(LocalDateTime.of(2026, 3, 1, 10, 0));
        return u;
    }

    @Test
    @DisplayName("密码字段绝不能出现在 JSON 里——包括值为 null 的情况")
    void jsonMustNotContainPasswordKey() throws Exception {
        String json = objectMapper.writeValueAsString(UserVO.from(fullUser()));

        assertFalse(json.contains("\"password\""),
                "UserVO 的 JSON 里出现了 password 键。字段本身就不该存在，"
                        + "哪怕值是 null——那等于告诉所有能看到响应的人这里有个密码字段。实际 JSON：" + json);
        assertFalse(json.toLowerCase().contains("bcrypt") && json.contains("$2a$"),
                "响应里出现了 BCrypt 哈希片段，密码漏出去了。实际 JSON：" + json);
    }

    @Test
    @DisplayName("逻辑删除标记是实现细节，不该进响应体")
    void jsonMustNotContainDeletedKey() throws Exception {
        String json = objectMapper.writeValueAsString(UserVO.from(fullUser()));

        assertFalse(json.contains("\"deleted\""),
                "deleted 是 MyBatis-Plus 的逻辑删除标记，属于实现细节，不该暴露给前端。实际 JSON：" + json);
    }

    @Test
    @DisplayName("该出去的字段一个都不能少")
    void allBusinessFieldsArePresent() throws Exception {
        String json = objectMapper.writeValueAsString(UserVO.from(fullUser()));

        // 前端确实在用的字段：用户名显示、头像、角色决定菜单、status 决定禁用提示。
        // 少一个前端就白屏或者按钮消失，这类回归比多泄露一个字段更难被立刻发现。
        Set<String> expected = Set.of(
                "id", "username", "realName", "age", "sex", "email", "phone",
                "avatarUrl", "roleStatus", "status", "createTime");

        for (String key : expected) {
            assertTrue(json.contains("\"" + key + "\""),
                    "UserVO 少了字段 " + key + "，前端会拿不到这个值。实际 JSON：" + json);
        }
    }

    @Test
    @DisplayName("null 实体返回 null，由调用方决定怎么处理")
    void fromNullReturnsNull() {
        assertNull(UserVO.from(null),
                "查不到用户时 from(null) 应该返回 null，而不是抛 NPE——"
                        + "控制器据此可以返回 404 或者空结果");
    }

    @Test
    @DisplayName("转换忠实复制字段值，不是只做了个空壳")
    void fromCopiesValuesFaithfully() {
        var user = fullUser();
        var vo = UserVO.from(user);

        assertEquals(7, vo.getId());
        assertEquals("test", vo.getUsername());
        assertEquals("张三", vo.getRealName());
        assertEquals(28, vo.getAge());
        assertEquals(0, vo.getRoleStatus());
        assertEquals(LocalDateTime.of(2026, 3, 1, 10, 0), vo.getCreateTime());
    }
}
