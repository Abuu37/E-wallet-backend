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

    @Async
    public void sendVerificationOtp(String to, String otp) {

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(to);
            message.setSubject("Verify Your E-wallet Account");
            message.setText(
                    "Hello,\n\n" +
                            "Thank you for registering with E-Wallet.\n\n" +
                            "To complete your registration, please use the verification code below:\n\n" +
                            "Verification Code: " + otp + "\n\n" +
                            "This verification code will expire in " + otpProperties.getExpirationMinutes() + " minutes.\n\n" +
                            "Please do not share this code with anyone. " +
                            "Regards,\n" +
                            "E-Wallet Team"
            );
            mailSender.send(message);
            log.info("OTP email successfully sent to {}", to);

        } catch (Exception e) {

            log.error("Failed to send OTP email to {}. Reason: {}", to, e.getMessage());

        }
    }
}
