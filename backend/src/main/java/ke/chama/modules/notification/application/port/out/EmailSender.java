package ke.chama.modules.notification.application.port.out;

/** Delivery channel for email. Production uses SMTP; tests swap in a recording fake. */
public interface EmailSender {

    /** Throws on failure, so the outbox keeps the event and retries it. */
    void send(EmailMessage message);
}
