package ke.chama.shared.tenancy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The caller's chama licence must include this feature. Put it on a controller class or method:
 * <pre>{@code
 * @RequiresFeature(LicenseFeature.LOANS)
 * @RestController
 * class LoanController { ... }
 * }</pre>
 * Checked before the request reaches the controller, together with the licence being valid.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresFeature {

    LicenseFeature value();
}
