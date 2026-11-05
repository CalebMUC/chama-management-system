package ke.chama.shared.domain.exception;

import java.time.Duration;

/** A failure that resolves by itself after some time. The web layer sends it as a {@code Retry-After} header. */
public interface RetryAfterAware {

    Duration retryAfter();
}
