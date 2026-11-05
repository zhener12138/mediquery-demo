package com.mediquery.integration.oss;

import cn.hutool.core.util.IdUtil;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * 阿里云OSS客户端：文件上传并返回公网可访问URL(UUID防重名)
 *
 * <h3>改了什么，为什么</h3>
 *
 * <p>改之前 {@code upload} 只做一件事：拿到原始文件名、截出扩展名、直接传到 OSS。
 * 后端从不看 Content-Type 也不看大小，全靠前端 {@code <input accept="image/*">} 挡。
 * 那层"挡"是可以绕过的——curl 一个请求就进去了。
 *
 * <p>具体风险不是"理论上可能"，而是很具体的三条：
 * <ul>
 *   <li><b>上传任意文件类型。</b>传一个 {@code .html} 上去，OSS 返回的公网 URL 会按
 *       {@code text/html} 渲染。这就成了挂在你自己域名下的钓鱼页，
 *       （bucket 配成公共读时）还能当水坑站用。</li>
 *   <li><b>文件名 `..` 穿越。</b>原代码把 {@code getOriginalFilename()} 的扩展名原样拼进
 *       objectName，虽然 OSS 对象名不是文件系统不算真穿越，但会造出 {@code mediquery/uuid./../x}
 *       这种恶心键，清理和计费都麻烦。</li>
 *   <li><b>超大文件打爆内存/带宽。</b>虽然 {@code application.yml} 有 10MB multipart 限制，
 *       但那是 Spring 层的，且流量已经进来了。</li>
 * </ul>
 *
 * <p>现在：白名单扩展名 + 校验魔数（不信 Content-Type，那个客户端随便填）+ 大小上限。
 * 校验失败抛 {@code IllegalArgumentException}，走 400，不是 500——
 * 这是用户的输入问题，不是服务器故障。
 */
@Component
public class OssClient {
    private static final Logger log = LoggerFactory.getLogger(OssClient.class);

    /**
     * 允许上传的扩展名。
     *
     * <p>为什么是这几个：头像/商品图是实际需求，webp 现在很常见不能漏。
     * <b>没有 svg</b>——SVG 是 XML，能内嵌 script，浏览器直接打开就执行，
     * 放在公共读 bucket 上等于给自己开一个 XSS 入口。
     */
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp");

    /** 单文件上限 5MB。比 application.yml 的 10MB 更严——这里是业务上限，那里是传输上限。 */
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    /** 只读前 12 字节就够判断所有常见图片格式的魔数。 */
    private static final int MAGIC_PROBE_BYTES = 12;

    @Value("${aliyun.oss.endpoint}")
    private String endpoint;

    @Value("${aliyun.oss.access-key-id}")
    private String accessKeyId;

    @Value("${aliyun.oss.access-key-secret}")
    private String accessKeySecret;

    @Value("${aliyun.oss.bucket-name}")
    private String bucketName;

    // 上传文件到OSS：校验→UUID防重名 → putObject → 返回公网URL
    public String upload(MultipartFile file) {
        // 先把校验做完，一个字节都不读到 OSS 去。
        // 顺序有讲究：先看大小（最便宜），再看扩展名（字符串比较），
        // 最后读魔数（要读流）。越贵的检查越靠后。
        validate(file);

        var objectName = "mediquery/" + IdUtil.fastSimpleUUID() + normalizedExtension(file.getOriginalFilename());

        OSS oss = null;
        try {
            oss = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
            oss.putObject(bucketName, objectName, file.getInputStream());
            return "https://" + bucketName + "." + endpoint + "/" + objectName;
        } catch (IOException e) {
            log.error("OSS 上传失败 objectName={}", objectName, e);
            throw new RuntimeException("文件上传失败", e);
        } finally {
            if (oss != null) {
                oss.shutdown();
            }
        }
    }

    /**
     * 三层校验。任何一层不过就抛 {@link IllegalArgumentException}（映射成 HTTP 400）。
     *
     * <p>包级可见是给测试用的——不需要真连 OSS 就能验完整套规则。
     */
    void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的文件");
        }
        if (file.getSize() > MAX_BYTES) {
            // 报错里给 MB 数，不是字节数——用户看不懂 5242880。
            throw new IllegalArgumentException(
                    "文件太大了，请上传 " + (MAX_BYTES / 1024 / 1024) + "MB 以内的图片");
        }

        var ext = normalizedExtension(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException(
                    "不支持的文件类型" + (ext.isEmpty() ? "" : "：" + ext)
                            + "，只允许 " + String.join(" / ", ALLOWED_EXTENSIONS.stream().sorted().toList()));
        }

        // 为什么还要验魔数：Content-Type 和扩展名都是客户端给的，改起来零成本。
        // 把 shell 脚本改名成 .png，前两层全过，只有魔数能识破。
        if (!looksLikeImage(file)) {
            throw new IllegalArgumentException("文件内容不是有效图片，请检查后重传");
        }
    }

    /**
     * 按魔数判断是不是真图片。
     *
     * <p>只读前若干字节，不整份加载——大文件全读进内存正好是被攻击的点。
     *
     * @return 命中任一已知图片格式返回 true
     */
    private boolean looksLikeImage(MultipartFile file) {
        byte[] head = new byte[MAGIC_PROBE_BYTES];
        int read;
        try (var in = file.getInputStream()) {
            read = in.readNBytes(head, 0, MAGIC_PROBE_BYTES);
        } catch (IOException e) {
            log.warn("读取上传文件头失败", e);
            return false;
        }
        if (read < 4) {
            return false;
        }
        return isJpeg(head) || isPng(head) || isGif(head) || isWebp(head);
    }

    private static boolean isJpeg(byte[] b) {
        return (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    private static boolean isPng(byte[] b) {
        return (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    private static boolean isGif(byte[] b) {
        // GIF87a / GIF89a
        return b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8';
    }

    private static boolean isWebp(byte[] b) {
        // RIFF....WEBP：前四字节 RIFF，第 9-12 字节 WEBP
        return b.length >= 12
                && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }

    /**
     * 取小写扩展名（含点）。
     *
     * <p>做两件事：
     * <ol>
     *   <li>转小写——{@code .JPG} 和 {@code .jpg} 是同一回事，不转就会被白名单误杀；</li>
     *   <li>只取最后一段路径的文件名——{@code ../../etc/passwd} 这种，
     *       按最后一个分隔符切完就只剩 {@code passwd}，不会把路径拼进 objectName。</li>
     * </ol>
     */
    static String normalizedExtension(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "";
        }
        // 反斜杠也要切：Windows 客户端可能传 "C:\Users\a\b.png"
        int slash = Math.max(originalName.lastIndexOf('/'), originalName.lastIndexOf('\\'));
        String base = slash >= 0 ? originalName.substring(slash + 1) : originalName;

        int dot = base.lastIndexOf('.');
        if (dot < 0 || dot == base.length() - 1) {
            return "";
        }
        return base.substring(dot).toLowerCase(Locale.ROOT);
    }
}
