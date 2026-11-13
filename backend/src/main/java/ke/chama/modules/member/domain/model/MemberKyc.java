package ke.chama.modules.member.domain.model;

import ke.chama.shared.domain.exception.DomainValidationException;

import java.time.LocalDate;
import java.time.Period;
import java.util.Locale;
import java.util.Objects;

/**
 * Know-your-customer details of a member, recorded by a chama official: who they are (identity
 * document, date of birth, gender) and where they live. Values are normalised on creation:
 * text trimmed, blanks become null, the document number upper-cased without spaces.
 *
 * @param town            optional
 * @param physicalAddress optional, e.g. estate, street and house number
 */
public record MemberKyc(IdDocumentType idType,
                        String idNumber,
                        LocalDate dateOfBirth,
                        Gender gender,
                        String county,
                        String town,
                        String physicalAddress) {

    /** Members borrow, guarantee and sign for money: they must be adults. */
    public static final int MINIMUM_AGE = 18;

    public MemberKyc {
        Objects.requireNonNull(idType, "idType");
        Objects.requireNonNull(gender, "gender");
        idNumber = normaliseIdNumber(idNumber);
        if (idType == IdDocumentType.NATIONAL_ID ? !idNumber.matches("\\d{5,10}") : !idNumber.matches("[A-Z0-9]{5,20}")) {
            throw new DomainValidationException("MEMBER_INVALID_ID_NUMBER", idType == IdDocumentType.NATIONAL_ID
                    ? "A national ID number has 5 to 10 digits"
                    : "A document number has 5 to 20 letters and digits");
        }
        if (dateOfBirth == null) {
            throw new DomainValidationException("MEMBER_INVALID_DATE_OF_BIRTH", "Date of birth is required");
        }
        county = trimToNull(county);
        if (county == null) {
            throw new DomainValidationException("MEMBER_INVALID_COUNTY", "County is required");
        }
        town = trimToNull(town);
        physicalAddress = trimToNull(physicalAddress);
    }

    /** Checked against the chama's local date, which the record itself does not know. */
    public void requireAdultOn(LocalDate today) {
        if (dateOfBirth.isAfter(today) || dateOfBirth.isBefore(today.minusYears(120))) {
            throw new DomainValidationException("MEMBER_INVALID_DATE_OF_BIRTH", "Enter a real date of birth");
        }
        if (Period.between(dateOfBirth, today).getYears() < MINIMUM_AGE) {
            throw new DomainValidationException("MEMBER_UNDER_AGE",
                    "Members must be at least " + MINIMUM_AGE + " years old");
        }
    }

    static String normaliseIdNumber(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("MEMBER_INVALID_ID_NUMBER", "ID number is required");
        }
        return value.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
