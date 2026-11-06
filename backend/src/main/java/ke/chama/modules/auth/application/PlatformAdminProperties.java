package ke.chama.modules.auth.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The first platform administrator, created at startup when {@code email} and {@code password}
 * are set (env {@code PLATFORM_ADMIN_EMAIL}, {@code PLATFORM_ADMIN_PASSWORD}). Platform
 * administrators onboard chamas and manage their licences; they belong to no chama.
 * <p>
 * Existing accounts are never changed, so the password can be rotated through the normal
 * change-password flow and removed from the environment afterwards.
 */
@ConfigurationProperties(prefix = "chama.platform.admin")
public record PlatformAdminProperties(String email,
                                      String password,
                                      @DefaultValue("Platform") String firstName,
                                      @DefaultValue("Administrator") String lastName,
                                      @DefaultValue("+254700000000") String phoneNumber) {

    public boolean configured() {
        return email != null && !email.isBlank() && password != null && !password.isBlank();
    }

    @Override
    public String toString() {
        return "PlatformAdminProperties[email=" + email + ", password=***]";
    }
}
