package ke.chama.modules.notification.application.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import ke.chama.modules.member.domain.event.MemberInvited;
import ke.chama.modules.member.domain.event.MembershipActivated;
import ke.chama.modules.notification.application.port.out.EmailMessage;
import ke.chama.modules.notification.application.port.out.EmailSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Membership emails, sent after the member transaction commits. */
@Component
class MembershipEmailNotifier {

    private final EmailSender emailSender;
    private final String frontendBaseUrl;

    MembershipEmailNotifier(EmailSender emailSender, @Value("${chama.frontend-base-url}") String frontendBaseUrl) {
        this.emailSender = emailSender;
        this.frontendBaseUrl = frontendBaseUrl.endsWith("/")
                ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1)
                : frontendBaseUrl;
    }

    @ApplicationModuleListener
    void on(MemberInvited event) {
        String joinUrl = frontendBaseUrl + "/join?chama=" + URLEncoder.encode(event.chamaCode(), StandardCharsets.UTF_8);
        emailSender.send(new EmailMessage(event.email(), "You're invited to join " + event.chamaName(), """
                Hello,

                %s has invited you to join %s.

                Create your account here: %s
                (or register in the app with the chama code: %s)

                Use this email address (%s). After you register, a chama official will approve
                your membership.

                If you weren't expecting this invitation, you can ignore this email.
                """.formatted(event.invitedByName(), event.chamaName(), joinUrl, event.chamaCode(), event.email())));
    }

    @ApplicationModuleListener
    void on(MembershipActivated event) {
        emailSender.send(new EmailMessage(event.email(), "Welcome to " + event.chamaName(), """
                Hello %s,

                Your membership of %s has been approved. Your member number is %d.

                Open %s to see your chama.
                """.formatted(event.firstName(), event.chamaName(), event.memberNumber(), frontendBaseUrl)));
    }
}
