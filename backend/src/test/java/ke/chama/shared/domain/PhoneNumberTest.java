package ke.chama.shared.domain;

import ke.chama.shared.domain.exception.DomainValidationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhoneNumberTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "0712345678", "0712 345 678", "712345678", "254712345678", "+254712345678", "+254-712-345-678", "(0712) 345678"
    })
    void normalizesKenyanSafaricomFormats(String raw) {
        assertThat(PhoneNumber.of(raw).value()).isEqualTo("+254712345678");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0110123456", "110123456", "254110123456"})
    void acceptsNewer01Prefixes(String raw) {
        assertThat(PhoneNumber.of(raw).value()).isEqualTo("+254110123456");
    }

    @ParameterizedTest
    @ValueSource(strings = {"+256772123456", "+255754123456"})
    void keepsOtherCountriesInE164(String raw) {
        assertThat(PhoneNumber.of(raw).value()).isEqualTo(raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "0812345678", "+2547123", "+254212345678", "07123456789", "phone", "+0712345678"})
    void rejectsInvalidNumbers(String raw) {
        assertThatThrownBy(() -> PhoneNumber.of(raw))
                .isInstanceOf(DomainValidationException.class)
                .extracting("code").isEqualTo("INVALID_PHONE_NUMBER");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void requiresAValue(String raw) {
        assertThatThrownBy(() -> PhoneNumber.of(raw)).isInstanceOf(DomainValidationException.class);
    }
}
