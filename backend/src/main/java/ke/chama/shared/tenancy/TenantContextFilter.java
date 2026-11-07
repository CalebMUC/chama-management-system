package ke.chama.shared.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

/**
 * Sets the {@link TenantContext} for each request. Runs in the security chain right after the
 * access token is validated, so for logged-in users the tenant comes from the signed token and
 * cannot be changed by the client. Public requests name their chama with the
 * {@value TenantContext#HEADER} header; requests with neither belong to the platform tenant
 * (platform administrators' login, and onboarding a new chama).
 * <p>
 * Not a Spring bean on purpose: Boot would otherwise also register it as a servlet filter.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    public static final String TENANT_ID_CLAIM = "tenant_id";
    public static final String TENANT_CODE_CLAIM = "tenant_code";

    private final TenantResolver resolver;

    public TenantContextFilter(TenantResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            resolve(request);
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void resolve(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            String tenantId = token.getToken().getClaimAsString(TENANT_ID_CLAIM);
            if (tenantId != null) {
                TenantContext.set(UUID.fromString(tenantId), token.getToken().getClaimAsString(TENANT_CODE_CLAIM));
            }
            // A token without a tenant claim is treated as no tenant: every query returns nothing.
            return;
        }

        String header = request.getHeader(TenantContext.HEADER);
        if (header == null || header.isBlank()) {
            TenantContext.set(TenantContext.PLATFORM, null);
            return;
        }
        String code = header.trim().toLowerCase(Locale.ROOT);
        resolver.findIdByCode(code).ifPresentOrElse(
                id -> TenantContext.set(id, code),
                () -> TenantContext.setUnresolved(code));
    }
}
