package com.mediquery.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JWT 密钥启动校验的护栏测试。
 *
 * <h3>这些测试在防什么</h3>
 *
 * <p>{@link JwtUtil#validateSecret} 存在的唯一理由是：不许用写死在仓库里的默认密钥。
 * 而"不许"这件事，如果没有测试，下一个人只要把 {@code ${JWT_SECRET:默认值}}
 * 里的兜底值加回去，一切照旧能跑，没人会察觉。
 *
 * <p>所以这里的断言全部围绕"旧默认值必须被拒"展开，而不是泛泛地测"密钥非空"。
 */
class JwtSecretValidationTest {

    private static final String VALID =
            "5bGqf3Kz1yTQm8Xh2pRw9Ln4Cv7Jd6Hs0EaUoIbNtYxWgZkM=";

    /**
     * 曾经提交在 git 历史里的默认值。它已经公开，永远不能再当密钥用——
     * 哪怕有人"认真地"把它配到环境变量里也一样。
     */
    private static final String LEAKED_DEFAULT =
            "mediquery-jwt-secret-key-2026-must-be-at-least-256-bits-long";

    @Test
    @DisplayName("未配置密钥时启动失败，并且报错里说清怎么修")
    void blankSecretIsRejectedWithActionableMessage() {
        var empty = assertThrows(IllegalStateException.class, () -> JwtUtil.validateSecret(""));
        var nul = assertThrows(IllegalStateException.class, () -> JwtUtil.validateSecret(null));
        var spaces = assertThrows(IllegalStateException.class, () -> JwtUtil.validateSecret("    "));

        // 报错信息是给凌晨两点看部署日志的人用的。只说"密钥无效"，他就得去翻代码；
        // 写上 JWT_SECRET 和生成命令，他三十秒就能修好。
        for (var e : new IllegalStateException[]{empty, nul, spaces}) {
            assertTrue(e.getMessage().contains("JWT_SECRET"),
                    "报错必须点名 JWT_SECRET，否则看日志的人不知道该改哪个变量：" + e.getMessage());
            assertTrue(e.getMessage().contains("openssl rand -base64 48"),
                    "报错要给出可直接复制的生成命令：" + e.getMessage());
        }
    }

    @Test
    @DisplayName("旧默认值即使被正确配置也必须拒绝")
    void leakedDefaultIsRejectedEvenWhenConfigured() {
        var e = assertThrows(IllegalStateException.class,
                () -> JwtUtil.validateSecret(LEAKED_DEFAULT));

        // 这条是整组测试的核心：密钥长度是够的（60字节），如果只校验长度它会被放行。
        // 必须单独识别"这是那个公开过的值"。
        assertTrue(e.getMessage().contains("git") || e.getMessage().contains("公开"),
                "拒掉之后要说明原因——这个值长度达标，只靠长度检查是拦不住的：" + e.getMessage());
    }

    @Test
    @DisplayName("长度不足的密钥被拒，并报出实际字节数")
    void shortSecretIsRejected() {
        // "too-short" 是 9 个 ASCII 字符 = 9 字节。写这个断言时我先写了 10，测试红了——
        // 说明这里的数字必须真数一遍，靠感觉会错。
        var e = assertThrows(IllegalStateException.class, () -> JwtUtil.validateSecret("too-short"));

        assertTrue(e.getMessage().contains("9"),
                "报错应说出实际字节数，方便判断是配置截断了还是本来就这么短：" + e.getMessage());
    }

    @Test
    @DisplayName("正常长度的随机密钥能通过")
    void validSecretIsAccepted() {
        assertDoesNotThrow(() -> JwtUtil.validateSecret(VALID));

        // 顺便确认 32 字节这个边界是"够用"的，不是把门槛定得比实际需要更高。
        assertDoesNotThrow(() -> JwtUtil.validateSecret("a".repeat(32)));
    }

    @Test
    @DisplayName("32 字节是下界，31 字节就该拒")
    void thirtyTwoBytesIsTheBoundary() {
        assertDoesNotThrow(() -> JwtUtil.validateSecret("a".repeat(32)));
        assertThrows(IllegalStateException.class, () -> JwtUtil.validateSecret("a".repeat(31)),
                "HS256 需要 256 位 = 32 字节，31 字节必须被拒");
    }

    @Test
    @DisplayName("多字节字符按字节算，不是按字符算")
    void multibyteSecretIsMeasuredInBytes() {
        // 中文密钥看着"有 16 个字很长"，UTF-8 下是 48 字节，够用。
        // 如果实现里误用 length()（字符数）当判据，某些情况会误判。
        String chinese = "这是一个十六个字的密钥用来测试字节长度判断";
        assertTrue(chinese.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= 32);
        assertDoesNotThrow(() -> JwtUtil.validateSecret(chinese));
    }

    @Test
    @DisplayName("配置里不再有兜底默认值")
    void applicationYmlHasNoFallbackSecret() throws Exception {
        // 直接读配置文件而不是靠"我记得改过了"——这是唯一能防止
        // 有人把 ${JWT_SECRET:mediquery-...} 加回去的地方。
        var yml = java.nio.file.Path.of("src/main/resources/application.yml");
        assertTrue(java.nio.file.Files.exists(yml), "找不到 application.yml，测试运行目录不对");

        String content = java.nio.file.Files.readString(yml);

        assertTrue(content.contains("secret: ${JWT_SECRET:}"),
                "application.yml 里 jwt.secret 应该是 ${JWT_SECRET:}（无兜底值）。"
                        + "如果这里红了，说明有人又加回了默认密钥——那等于把签名密钥提交进仓库。");
        assertTrue(!content.contains(LEAKED_DEFAULT),
                "application.yml 里又出现了那个已公开的默认密钥：" + LEAKED_DEFAULT);
    }
}
