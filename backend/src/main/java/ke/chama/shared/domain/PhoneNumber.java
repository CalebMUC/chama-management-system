package ke.chama.shared.domain;

import ke.chama.shared.domain.exception.DomainValidationException;

import java.util.regex.Pattern;

/**
 * Phone number in E.164 format ({@code +254712345678}), the format M-Pesa and SMS gateways expect.
 * <p>
 * Members type numbers many ways, so {@link #of} accepts the common Kenyan forms and
 * normalizes them:
 * {@code 0712 345 678}, {@code 712345678}, {@code 254712345678}, {@code +254-712-345-678}.
 * Other countries must be entered with their {@code +} country code.
 */
public record PhoneNumber(String value) {

    private static final Pattern E164 = Pattern.compile("\\+[1-9]\\d{7,14}");
    private static final Pattern KENYA_E164 = Pattern.compile("\\+254[17]\\d{8}");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-().]");

    public PhoneNumber {
        if (value == null || !E164.matcher(value).matches()) {
            throw invalid();
        }
    }

    public static PhoneNumber of(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new DomainValidationException("INVALID_PHONE_NUMBER", "Phone number is required");
        }
        String digits = SEPARATORS.matcher(raw.trim()).replaceAll("");

        String normalized;
        if (digits.startsWith("+")) {
            normalized = digits;
        } else if (digits.matches("254[17]\\d{8}")) {
            normalized = "+" + digits;
        } else if (digits.matches("0[17]\\d{8}")) {
            normalized = "+254" + digits.substring(1);
        } else if (digits.matches("[17]\\d{8}")) {
            normalized = "+254" + digits;
        } else {
            throw invalid();
        }

        if (normalized.startsWith("+254") && !KENYA_E164.matcher(normalized).matches()) {
            throw invalid();
        }
        return new PhoneNumber(normalized);
    }

    private static DomainValidationException invalid() {
        return new DomainValidationException("INVALID_PHONE_NUMBER",
                "Phone number must be a valid mobile number, e.g. 0712345678 or +254712345678");
    }

    @Override
    public String toString() {
        return value;
    }
}
