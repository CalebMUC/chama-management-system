package ke.chama.modules.member.presentation.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import ke.chama.modules.member.application.port.in.MembershipCommands.NewMember;
import ke.chama.modules.member.domain.model.ChamaRole;
import ke.chama.modules.member.domain.model.Gender;
import ke.chama.modules.member.domain.model.IdDocumentType;
import ke.chama.modules.member.domain.model.MemberKyc;
import ke.chama.shared.security.ChamaPermission;

import java.time.LocalDate;
import java.util.Set;

public final class MemberRequests {

    private MemberRequests() {
    }

    public record InviteMemberRequest(@NotBlank @Email @Size(max = 255) String email) {
        public InviteMemberRequest {
            email = email == null ? null : email.trim();
        }
    }

    /**
     * An official opens a member's account. {@code temporaryPassword} follows the registration
     * password rules; the member must replace it at first sign-in.
     */
    public record AddMemberRequest(
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotBlank @Email @Size(max = 255) String email,
            /* Normalized to +254... by the domain; format errors return INVALID_PHONE_NUMBER. */
            @NotBlank @Size(max = 20) String phoneNumber,
            @NotBlank @Size(min = 8, max = 72)
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "must contain at least one letter and one digit")
            String temporaryPassword,
            @NotNull @Valid KycRequest kyc) {

        public AddMemberRequest {
            email = email == null ? null : email.trim();
        }

        public NewMember toCommand() {
            return new NewMember(email, phoneNumber, firstName, lastName, temporaryPassword, kyc.toKyc());
        }

        @Override
        public String toString() {
            return "AddMemberRequest[email=" + email + ", temporaryPassword=***]";
        }
    }

    /** A member's KYC details. {@code town} and {@code physicalAddress} are optional. */
    public record KycRequest(
            @NotNull IdDocumentType idType,
            @NotBlank @Size(max = 30) String idNumber,
            @NotNull @Past LocalDate dateOfBirth,
            @NotNull Gender gender,
            @NotBlank @Size(max = 60) String county,
            @Size(max = 100) String town,
            @Size(max = 300) String physicalAddress) {

        public MemberKyc toKyc() {
            return new MemberKyc(idType, idNumber, dateOfBirth, gender, county, town, physicalAddress);
        }
    }

    public record SuspendMemberRequest(@NotBlank @Size(max = 500) String reason) {
    }

    /** MEMBER is always kept even if omitted. */
    public record ChangeRolesRequest(@NotNull Set<ChamaRole> roles) {
    }

    public record RolePermissionsRequest(@NotNull Set<ChamaPermission> permissions) {
    }
}
