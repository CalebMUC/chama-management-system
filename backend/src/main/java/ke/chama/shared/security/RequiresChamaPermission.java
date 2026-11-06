package ke.chama.shared.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The caller must be an ACTIVE member of the chama, and one of their roles there must grant
 * the permission. The annotated method must have a {@code UUID chamaId} parameter.
 * <pre>{@code
 * @RequiresChamaPermission(ChamaPermission.LOAN_APPROVE)
 * @PostMapping("/chamas/{chamaId}/loans/{loanId}/approve")
 * LoanResponse approve(@PathVariable UUID chamaId, @PathVariable UUID loanId) { ... }
 * }</pre>
 * A non-member, a suspended member and an unknown chama all get the same 403, so the API does
 * not reveal which chamas exist.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("@chamaAccess.has(#chamaId, '{value}')")
public @interface RequiresChamaPermission {

    ChamaPermission value();
}
