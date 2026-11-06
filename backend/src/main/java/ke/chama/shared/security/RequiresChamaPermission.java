package ke.chama.shared.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The caller must be an ACTIVE member of their chama, and one of their roles there must grant
 * the permission. The chama is the caller's tenant (from the access token), never a URL value.
 * <pre>{@code
 * @RequiresChamaPermission(ChamaPermission.LOAN_APPROVE)
 * @PostMapping("/loans/{loanId}/approve")
 * LoanResponse approve(@PathVariable UUID loanId) { ... }
 * }</pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("@chamaAccess.has('{value}')")
public @interface RequiresChamaPermission {

    ChamaPermission value();
}
