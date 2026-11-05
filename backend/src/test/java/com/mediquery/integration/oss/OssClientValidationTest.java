package com.mediquery.integration.oss;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OssClient} 上传校验的护栏测试。
 *
 * <h3>这些测试在防什么</h3>
 *
 * <p>改之前 {@code upload()} 只看 {@code getOriginalFilename()} 截扩展名，Content-Type 和
 * 文件内容一律不看。也就是说：把 {@code shell.sh} 改名成 {@code avatar.png} 能直接传上去，
 * 拿回一个公网 URL。
 *
 * <p>注意这里<b>不测上传成功</b>——那需要真连 OSS，属于集成测试。
 * 这里只测"什么该被挡住"，因为校验才是这次改动的内容。
 */
class OssClientValidationTest {

    private final OssClient ossClient = new OssClient();

    // ---------- 合法图片 ----------

    @Test
    @DisplayName("真 PNG 能通过校验")
    void realPngPasses() {
        // 1x1 透明 PNG 的前 8 字节 + 填充。魔数对了就行，校验不解析整张图。
        byte[] png = pngHeader(100);

        assertDoesNotThrow(() -> ossClient.validate(new MockMultipartFile(
                "file", "avatar.png", "image/png", png)));
    }

    @Test
    @DisplayName("真 JPEG 能通过校验")
    void realJpegPasses() {
        byte[] jpeg = new byte[100];
        jpeg[0] = (byte) 0xFF;
        jpeg[1] = (byte) 0xD8;
        jpeg[2] = (byte) 0xFF;
        jpeg[3] = (byte) 0xE0;

        assertDoesNotThrow(() -> ossClient.validate(new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", jpeg)));
    }

    @Test
    @DisplayName("webp 必须被允许——现在前端默认就产 webp")
    void webpIsAllowed() {
        byte[] webp = new byte[100];
        webp[0] = 'R'; webp[1] = 'I'; webp[2] = 'F'; webp[3] = 'F';
        webp[8] = 'W'; webp[9] = 'E'; webp[10] = 'B'; webp[11] = 'P';

        assertDoesNotThrow(() -> ossClient.validate(new MockMultipartFile(
                "file", "pic.webp", "image/webp", webp)));
    }

    @Test
    @DisplayName("大写扩展名不该被误杀")
    void uppercaseExtensionPasses() {
        byte[] png = pngHeader(50);

        assertDoesNotThrow(() -> ossClient.validate(new MockMultipartFile(
                "file", "AVATAR.PNG", "image/png", png)),
                ".JPG 和 .jpg 是同一回事。不统一转小写会误杀正常用户，"
                        + "而用户根本不知道该怎么改——这种 bug 极难排查");
    }

    // ---------- 该被挡住的 ----------

    @ParameterizedTest(name = "{0} 被拒")
    @ValueSource(strings = {"shell.sh", "page.html", "doc.pdf", "app.exe", "noext", "trailingdot."})
    @DisplayName("白名单外的扩展名一律拒绝")
    void foreignExtensionsAreRejected(String filename) {
        byte[] png = pngHeader(50);

        var e = assertThrows(IllegalArgumentException.class, () -> ossClient.validate(
                new MockMultipartFile("file", filename, "image/png", png)),
                filename + " 应该被拒——只允许图片");

        assertTrue(e.getMessage().contains("不支持的文件类型"),
                "错误信息该说清是类型问题，不是泛泛的「上传失败」：" + e.getMessage());
    }

    @Test
    @DisplayName("HTML 尤其要挡——它会被浏览器当页面执行")
    void htmlIsRejectedEvenWithImageExtension() {
        // 这条单列，因为它的后果比其他被拒类型严重得多：
        // .html 上传到公共读 bucket，OSS 按 text/html 返回，就是挂在你自己域名下的钓鱼页。
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> ossClient.validate(
                new MockMultipartFile("file", "evil.html", "text/html", html)));

        // 改名成 .png 也不行——魔数会识破。
        assertThrows(IllegalArgumentException.class, () -> ossClient.validate(
                new MockMultipartFile("file", "evil.png", "image/png", html)),
                "改了扩展名和 Content-Type 也要挡住，这两样都是客户端随便填的");
    }

    @Test
    @DisplayName("改名伪装的脚本被魔数识破")
    void disguisedScriptIsRejectedByMagicBytes() {
        byte[] shell = "#!/bin/sh\nrm -rf /\n".getBytes(StandardCharsets.UTF_8);

        var e = assertThrows(IllegalArgumentException.class, () -> ossClient.validate(
                new MockMultipartFile("file", "avatar.png", "image/png", shell)),
                "扩展名和 Content-Type 都伪装成图片，但内容不是——只有魔数检查能拦住这个");

        assertTrue(e.getMessage().contains("不是有效图片") || e.getMessage().contains("图片"),
                "错误信息要说明是内容不对，不是类型不对：" + e.getMessage());
    }

    @Test
    @DisplayName("超过 5MB 直接拒，且报错用 MB 不用字节")
    void oversizedFileIsRejected() {
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(pngHeader(8), 0, big, 0, 8);

        var e = assertThrows(IllegalArgumentException.class, () -> ossClient.validate(
                new MockMultipartFile("file", "big.png", "image/png", big)));

        assertTrue(e.getMessage().contains("5MB"),
                "报错要给 MB 数，5242880 这种数字用户看不懂：" + e.getMessage());
    }

    @Test
    @DisplayName("空文件被拒")
    void emptyFileIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ossClient.validate(
                new MockMultipartFile("file", "empty.png", "image/png", new byte[0])));
        assertThrows(IllegalArgumentException.class, () -> ossClient.validate(null));
    }

    @Test
    @DisplayName("太短的文件不当图片处理")
    void tooShortToBeAnImage() {
        assertThrows(IllegalArgumentException.class, () -> ossClient.validate(
                new MockMultipartFile("file", "tiny.png", "image/png", new byte[]{(byte) 0x89, 'P'})),
                "只有 2 字节连魔数都比不了，不该放过");
    }

    // ---------- 扩展名解析 ----------

    @Test
    @DisplayName("路径穿越的写法只取最后一段文件名")
    void pathTraversalIsStripped() {
        assertEquals(".png", OssClient.normalizedExtension("../../etc/passwd.png"));
        assertEquals(".png", OssClient.normalizedExtension("C:\\Users\\a\\b.png"));
        assertEquals(".png", OssClient.normalizedExtension("a/b/c.png"));
    }

    @Test
    @DisplayName("没有扩展名时返回空串，不抛异常")
    void noExtensionReturnsEmpty() {
        assertEquals("", OssClient.normalizedExtension("noext"));
        assertEquals("", OssClient.normalizedExtension("trailingdot."));
        assertEquals("", OssClient.normalizedExtension(null));
        assertEquals("", OssClient.normalizedExtension(""));
    }

    @Test
    @DisplayName("upload() 自己会走校验，不能只测 validate() 就以为防住了")
    void uploadMethodItselfRunsValidation() {
        // 这条是返工补的。我第一版只测了 validate() 单独调用，做变异验证时
        // 把 upload() 里那行 validate(file) 注释掉——测试照样全绿。
        // 也就是说前面那一堆断言保护的是一段"可能根本没被调用"的代码。
        //
        // 现在从 upload() 入口打：非法文件必须在碰到 OSS 之前就被拦下。
        // 如果校验被摘掉，执行会一路走到 new OSSClientBuilder().build(...)，
        // 抛出来的是别的异常（这里没配 endpoint/key），断言就会失败。
        byte[] shell = "#!/bin/sh\necho pwned\n".getBytes(StandardCharsets.UTF_8);
        var evil = new MockMultipartFile("file", "avatar.png", "image/png", shell);

        assertThrows(IllegalArgumentException.class, () -> ossClient.upload(evil),
                "upload() 必须自己调用 validate()。如果这里抛的不是 IllegalArgumentException，"
                        + "说明校验根本没进 upload 的调用链——那所有校验测试都是空转。");
    }

    @Test
    @DisplayName("扩展名不合法的文件也进不到 OSS")
    void uploadRejectsForeignExtensionBeforeTouchingOss() {
        byte[] png = pngHeader(50);
        var evil = new MockMultipartFile("file", "payload.html", "image/png", png);

        assertThrows(IllegalArgumentException.class, () -> ossClient.upload(evil));
    }

    private static byte[] pngHeader(int size) {
        byte[] b = new byte[size];
        b[0] = (byte) 0x89;
        b[1] = 'P';
        b[2] = 'N';
        b[3] = 'G';
        return b;
    }
}
