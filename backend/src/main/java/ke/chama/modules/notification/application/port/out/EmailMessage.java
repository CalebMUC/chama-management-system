package ke.chama.modules.notification.application.port.out;

/**
 * A plain-text email. Plain text means names and other user input never need HTML
 * escaping; HTML templates can come later.
 */
public record EmailMessage(String to, String subject, String body) {

    /** Bodies contain live links, so they never go into logs. */
    @Override
    public String toString() {
        return "EmailMessage[to=" + to + ", subject=" + subject + "]";
    }
}
