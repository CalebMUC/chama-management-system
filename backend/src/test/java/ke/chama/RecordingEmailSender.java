package ke.chama;

import ke.chama.modules.notification.application.port.out.EmailMessage;
import ke.chama.modules.notification.application.port.out.EmailSender;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captures emails instead of sending them. Listeners run asynchronously after commit, so
 * tests use {@link #awaitEmail} instead of asserting immediately.
 */
public class RecordingEmailSender implements EmailSender {

    private static final Pattern TOKEN = Pattern.compile("[?&]token=([A-Za-z0-9_-]+)");

    private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(EmailMessage message) {
        sent.add(message);
    }

    public List<EmailMessage> sentTo(String email) {
        return sent.stream().filter(m -> m.to().equalsIgnoreCase(email)).toList();
    }

    /** Waits up to 10 seconds for an email to {@code email} whose subject matches. */
    public EmailMessage awaitEmail(String email, Predicate<String> subject) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            Optional<EmailMessage> match = sentTo(email).stream().filter(m -> subject.test(m.subject())).reduce((a, b) -> b);
            if (match.isPresent()) {
                return match.get();
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("No email to " + email + " matching subject arrived within 10s; got " + sentTo(email));
    }

    public static String tokenFrom(EmailMessage message) {
        Matcher matcher = TOKEN.matcher(message.body());
        if (!matcher.find()) {
            throw new AssertionError("No token link in email: " + message.subject());
        }
        return matcher.group(1);
    }
}
