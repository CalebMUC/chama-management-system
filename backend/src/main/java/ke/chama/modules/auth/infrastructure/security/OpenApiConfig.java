package ke.chama.modules.auth.infrastructure.security;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import ke.chama.shared.security.RequiresChamaMembership;
import ke.chama.shared.security.RequiresChamaPermission;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;

/**
 * OpenAPI document behind Swagger UI ({@code /swagger-ui.html}).
 * <p>
 * Lives next to {@link SecurityConfig} because it describes the same rules: every endpoint needs a
 * bearer token except {@link SecurityConfig#PUBLIC_AUTH_ENDPOINTS}, and chama endpoints also
 * need the permission named by {@link RequiresChamaPermission}. That permission is added to each
 * operation's description and as the {@code x-chama-permission} extension, so clients can read it.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    static final String BEARER_SCHEME = "bearerAuth";
    static final String PERMISSION_EXTENSION = "x-chama-permission";

    @Bean
    OpenAPI chamaOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Chama Management API")
                        .version("v1")
                        .description("""
                                Contributions, loans, expenses and members for Kenyan chamas.

                                **Authorize:** call `POST /api/v1/auth/login`, copy `accessToken` from the \
                                response and paste it into **Authorize** (no `Bearer ` prefix). Tokens last \
                                15 minutes; use `POST /api/v1/auth/refresh` for a new one.

                                **Permissions:** chama endpoints list the permission they need. A caller \
                                without it, or who is not an active member of the chama, gets 403.

                                **Money** is in minor units (cents): `500000` is KES 5,000.00. \
                                **Errors** are RFC 9457 problem details with a machine-readable `code`."""))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token from POST /api/v1/auth/login")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }

    /** Removes the padlock from endpoints that work without a token. */
    @Bean
    OpenApiCustomizer publicEndpointsNeedNoToken() {
        Set<String> publicPaths = Set.of(SecurityConfig.PUBLIC_AUTH_ENDPOINTS);
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, item) -> {
                if (publicPaths.contains(path) && item.getPost() != null) {
                    item.getPost().setSecurity(List.of());
                }
            });
        };
    }

    /** Documents the chama-level access rule on each operation. */
    @Bean
    OperationCustomizer chamaAccessRequirements() {
        return (operation, handlerMethod) -> {
            RequiresChamaPermission permission = find(handlerMethod, RequiresChamaPermission.class);
            if (permission != null) {
                String name = permission.value().name();
                operation.addExtension(PERMISSION_EXTENSION, name);
                appendDescription(operation, "**Requires chama permission:** `" + name + "`");
            } else if (find(handlerMethod, RequiresChamaMembership.class) != null) {
                appendDescription(operation, "**Requires:** active membership of this chama");
            }
            return operation;
        };
    }

    private static <A extends Annotation> A find(HandlerMethod handlerMethod, Class<A> type) {
        A onMethod = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), type);
        return onMethod != null ? onMethod : AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), type);
    }

    private static void appendDescription(Operation operation, String line) {
        String existing = operation.getDescription();
        operation.setDescription(existing == null || existing.isBlank() ? line : existing + "\n\n" + line);
    }
}
