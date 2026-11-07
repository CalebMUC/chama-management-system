package ke.chama.modules.auth.infrastructure.security;

import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import ke.chama.shared.tenancy.TenantResolver;
import ke.chama.shared.tenancy.TenantContextFilter;
import ke.chama.modules.auth.domain.model.Role;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stateless API security. Every request carries a bearer access token; there is no server
 * session and no cookie, so CSRF protection is not needed.
 * <p>
 * Everything is authenticated unless listed here. Chama-level rules ("is this user a treasurer
 * of chama X?") are added with {@code @PreAuthorize} in Phase 2, which is why method security
 * is enabled now.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfig {

    static final String[] PUBLIC_AUTH_ENDPOINTS = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/auth/email/verify",
            "/api/v1/auth/email/verify/resend",
            "/api/v1/auth/password/forgot",
            "/api/v1/auth/password/reset"
    };

    static final String PLATFORM_ENDPOINTS = "/api/v1/platform/**";

    static final String[] API_DOCS_ENDPOINTS = {
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"
    };

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, JsonMapper jsonMapper, TenantResolver tenantResolver)
            throws Exception {
        ApiErrorSecurityHandlers handlers = new ApiErrorSecurityHandlers(jsonMapper);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, PUBLIC_AUTH_ENDPOINTS).permitAll()
                        // Platform administration: onboarding chamas, their licences, the list of chamas.
                        .requestMatchers(PLATFORM_ENDPOINTS).hasAuthority(Role.ADMIN)
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/error").permitAll()
                        // API docs. Springdoc is disabled under 'prod', so there these paths are 404.
                        .requestMatchers(HttpMethod.GET, API_DOCS_ENDPOINTS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers))
                // Right after the access token is validated, so the tenant is taken from the token.
                .addFilterAfter(new TenantContextFilter(tenantResolver), BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    /** BCrypt by default, with a {@code {bcrypt}} prefix so the algorithm can be upgraded later. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** Maps the {@code roles} claim (already "ROLE_*") to authorities, with {@code sub} as the principal name. */
    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(JwtAccessTokenIssuer.ROLES_CLAIM);
        authorities.setAuthorityPrefix("");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        converter.setPrincipalClaimName("sub");
        return converter;
    }
}
