package ke.chama.modules.chama.domain.model;

import ke.chama.shared.domain.exception.DomainValidationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChamaCodeTest {

    @ParameterizedTest
    @CsvSource({
            "mokica, mokica",
            "'  Jamii-Sacco ', jamii-sacco",
            "umoja2026, umoja2026"
    })
    void codesAreTrimmedAndLowerCased(String input, String expected) {
        assertThat(Chama.normalizeCode(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "has space", "double--hyphen", "-leading", "trailing-", "émoji", "platform", "admin",
            "this-code-is-far-too-long-to-be-used"})
    void malformedOrReservedCodesAreRejected(String code) {
        assertThatThrownBy(() -> Chama.normalizeCode(code))
                .isInstanceOf(DomainValidationException.class)
                .extracting("code").isEqualTo("CHAMA_INVALID_CODE");
    }
}
