package ke.chama.modules.notification.infrastructure.adapter;

import ke.chama.modules.notification.application.port.out.EmailMessage;
import ke.chama.modules.notification.application.port.out.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends via the SMTP server configured under {@code spring.mail.*}. Locally that is Mailpit
 * from docker-compose; open http://localhost:18025 to read sent emails.
 */
@Component
class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;
    private final String from;

    SmtpEmailSender(JavaMailSender mailSender, @Value("${chama.notification.mail-from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(EmailMessage message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(message.to());
        mail.setSubject(message.subject());
        mail.setText(message.body());
        mailSender.send(mail);
        log.debug("Sent email '{}' to {}", message.subject(), message.to());
    }
}
