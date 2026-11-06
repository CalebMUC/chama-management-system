package ke.chama.modules.auth.application.service;

import ke.chama.shared.domain.exception.DomainValidationException;

import java.nio.charset.StandardCharsets;

/** One place for password rules, used by registration, reset and change. */
final class PasswordPolicy {

    static final int MIN_LENGTH = 8;
    /** BCrypt only uses the first 72 bytes; longer passwords would be silently truncated. */
    static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    static void requireAcceptable(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new DomainValidationException("AUTH_WEAK_PASSWORD",
                    "Password must be at least " + MIN_LENGTH + " characters");
        }
        if (exceedsMaxBytes(password)) {
            throw new DomainValidationException("AUTH_PASSWORD_TOO_LONG",
                    "Password must be at most " + MAX_BYTES + " bytes");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new DomainValidationException("AUTH_WEAK_PASSWORD",
                    "Password must contain at least one letter and one digit");
        }
    }

    static boolean exceedsMaxBytes(String password) {
        return password != null && password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES;
    }
}
