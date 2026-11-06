package ke.chama.shared.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The caller must be an ACTIVE member of the chama; no specific permission needed.
 * For "my own records" endpoints. The method must have a {@code UUID chamaId} parameter.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("@chamaAccess.isActiveMember(#chamaId)")
public @interface RequiresChamaMembership {
}
