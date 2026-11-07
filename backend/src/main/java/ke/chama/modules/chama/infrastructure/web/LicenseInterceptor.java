package ke.chama.modules.chama.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ke.chama.modules.chama.api.LicenseSummary;
import ke.chama.modules.chama.application.service.LicenseCache;
import ke.chama.modules.chama.domain.exception.LicenseExceptions.FeatureNotLicensedException;
import ke.chama.modules.chama.domain.exception.LicenseExceptions.LicenseExpiredException;
import ke.chama.modules.chama.domain.exception.LicenseExceptions.LicenseNotFoundException;
import ke.chama.modules.chama.domain.exception.LicenseExceptions.LicenseSuspendedException;
import ke.chama.shared.security.CurrentUser;
import ke.chama.shared.tenancy.RequiresFeature;
import ke.chama.shared.tenancy.TenantContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Enforces the chama's licence on every request by a chama's user, before the controller runs:
 * <ul>
 *     <li>the licence must be ACTIVE (not expired or suspended), or the request is refused with
 *     403 {@code LICENSE_EXPIRED} / {@code LICENSE_SUSPENDED};</li>
 *     <li>an endpoint marked {@link RequiresFeature} needs that feature in the licence.</li>
 * </ul>
 * A few read-only endpoints stay open on an expired licence, so members can still see what is
 * wrong. Platform administrators and public endpoints are not affected.
 */
class LicenseInterceptor implements HandlerInterceptor {

    /** GET endpoints that work even when the licence is not usable. */
    static final Set<String> OPEN_WHEN_UNLICENSED = Set.of("/api/v1/chama", "/api/v1/chama/license", "/api/v1/members/me");

    private final LicenseCache licenses;

    LicenseInterceptor(LicenseCache licenses) {
        this.licenses = licenses;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || CurrentUser.find().isEmpty()) {
            return true;
        }
        Optional<UUID> chamaId = TenantContext.id().filter(id -> !TenantContext.PLATFORM.equals(id));
        if (chamaId.isEmpty()) {
            return true;
        }
        LicenseSummary license = licenses.get(chamaId.get()).orElseThrow(LicenseNotFoundException::new);

        if (!licenses.usable(license) && !isOpenWhenUnlicensed(request)) {
            if ("SUSPENDED".equals(license.state())) {
                throw new LicenseSuspendedException();
            }
            throw new LicenseExpiredException();
        }
        RequiresFeature feature = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequiresFeature.class);
        if (feature == null) {
            feature = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequiresFeature.class);
        }
        if (feature != null && !license.includes(feature.value())) {
            throw new FeatureNotLicensedException(feature.value().name());
        }
        return true;
    }

    private static boolean isOpenWhenUnlicensed(HttpServletRequest request) {
        return HttpMethod.GET.matches(request.getMethod()) && OPEN_WHEN_UNLICENSED.contains(request.getRequestURI());
    }
}
