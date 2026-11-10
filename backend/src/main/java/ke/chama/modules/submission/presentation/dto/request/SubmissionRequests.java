package ke.chama.modules.submission.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import ke.chama.modules.submission.application.port.in.PaymentSubmissionUseCases.NewSubmission;
import ke.chama.modules.submission.domain.model.PaymentSubmission;

import java.time.LocalDate;
import java.util.UUID;

public final class SubmissionRequests {

    private SubmissionRequests() {
    }

    /**
     * @param targetId  the penalty (PENALTY_PAYMENT) or loan (LOAN_REPAYMENT) paid towards; leave out for a CONTRIBUTION
     * @param reference required for MPESA (transaction code) and BANK (slip number)
     * @param paidOn    when you paid; defaults to today, cannot be in the future
     */
    public record SubmitPaymentRequest(@NotNull PaymentSubmission.Kind kind,
                                       UUID targetId,
                                       @NotNull @Positive Long amountMinor,
                                       @NotNull @Pattern(regexp = "CASH|BANK|MPESA", message = "must be CASH, BANK or MPESA")
                                       String method,
                                       @Size(max = 100) String reference,
                                       LocalDate paidOn,
                                       @Size(max = 300) String notes) {

        public NewSubmission toCommand() {
            return new NewSubmission(kind, targetId, amountMinor, method, reference, paidOn, notes);
        }
    }

    public record ApproveSubmissionRequest(@Size(max = 300) String note) {
    }

    public record RejectSubmissionRequest(@NotBlank @Size(max = 300) String reason) {
    }
}
