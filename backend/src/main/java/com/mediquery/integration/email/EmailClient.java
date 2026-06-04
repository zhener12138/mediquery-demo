package com.mediquery.integration.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
public class EmailClient {
    private static final Logger log = LoggerFactory.getLogger(EmailClient.class);

    private final JavaMailSender mailSender;

    public EmailClient(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Value("${spring.mail.username}")
    private String from;

    @Value("${spring.mail.title}")
    private String title;

    @Value("${spring.mail.template}")
    private String template;

    @Value("${spring.mail.valid}")
    private int validMinutes;

    public void sendVerificationCode(String to, String code, int expireMinutes) {
        var content = String.format(template, code, expireMinutes);
        send(to, title, content);
    }

    public void send(String to, String subject, String content) {
        try {
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(content, true);
            mailSender.send(message);
            log.info("邮件发送成功: {} -> {}", subject, to);
        } catch (MessagingException e) {
            log.error("邮件发送失败: {}", e.getMessage());
            throw new RuntimeException("邮件发送失败", e);
        }
    }
}
