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

/**
 * 阿里云OSS客户端：文件上传并返回公网可访问URL(UUID防重名)
 */
@Component
public class OssClient {
    private static final Logger log = LoggerFactory.getLogger(OssClient.class);

    @Value("${aliyun.oss.endpoint}")
    private String endpoint;

    @Value("${aliyun.oss.access-key-id}")
    private String accessKeyId;

    @Value("${aliyun.oss.access-key-secret}")
    private String accessKeySecret;

    @Value("${aliyun.oss.bucket-name}")
    private String bucketName;

    // 上传文件到OSS：UUID防重名 → putObject → 返回公网URL
    public String upload(MultipartFile file) {
        var originalName = file.getOriginalFilename();
        var ext = originalName != null && originalName.contains(".")
                ? originalName.substring(originalName.lastIndexOf("."))
                : "";
        var objectName = "mediquery/" + IdUtil.fastSimpleUUID() + ext;

        OSS oss = null;
        try {
            oss = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
            oss.putObject(bucketName, objectName, file.getInputStream());
            return "https://" + bucketName + "." + endpoint + "/" + objectName;
        } catch (IOException e) {
            log.error("OSS 上传失败", e);
            throw new RuntimeException("文件上传失败", e);
        } finally {
            if (oss != null) {
                oss.shutdown();
            }
        }
    }
}
