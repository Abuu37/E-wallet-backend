package com.application.e_wallet.otp.service;

import com.application.e_wallet.otp.config.OtpProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;
    private final OtpProperties otpProperties;

    @Value("${spring.mail.username}")
    private String fromAddress;

    /** The one email the NotificationSender uses. Failures are logged, never thrown. */
    @Async
    public void sendPlain(String to, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            log.info("Email '{}' sent to {}", subject, to);
        } catch (Exception e) {
            log.error("Failed to send email '{}' to {}. Reason: {}", subject, to, e.getMessage());
        }
    }
}
