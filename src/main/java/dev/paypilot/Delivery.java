package dev.paypilot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
class Delivery {
    private static final Logger log = LoggerFactory.getLogger(Delivery.class);
    private final ObjectProvider<JavaMailSender> mail;
    private final String from;
    private final boolean demo;

    Delivery(ObjectProvider<JavaMailSender> mail, @Value("${paypilot.mail-from:}") String from,
             @Value("${paypilot.demo}") boolean demo) {
        this.mail = mail;
        this.from = from;
        this.demo = demo;
    }

    void verification(String email, String code) {
        if (send(email, "PayPilot verification code", "Your PayPilot code is " + code + ". It expires in 10 minutes.")) {
            return;
        }
        if (demo) {
            log.info("Local verification code for {}: {}", email, code);
            return;
        }
        throw new Problem(503, "Email delivery is unavailable. Contact your PayPilot administrator.");
    }

    boolean send(String email, String subject, String body) {
        if (email.endsWith("@example.invalid") || from.isBlank()) return false;
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null) return false;
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject(subject);
        message.setText(body);
        try {
            sender.send(message);
        } catch (MailException e) {
            throw new Problem(503, "Email delivery failed. Try again later.");
        }
        return true;
    }
}
