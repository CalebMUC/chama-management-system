package ke.chama.modules.auth.infrastructure.security;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.DateTimeSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import ke.chama.shared.security.RequiresChamaMembership;
import ke.chama.shared.security.RequiresChamaPermission;
import ke.chama.shared.web.ApiError;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;

import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAPI document behind Swagger UI ({@code /swagger-ui.html}).
 * <p>
 * Lives next to {@link SecurityConfig} because it describes the same rules: every endpoint needs a
 * bearer token except {@link SecurityConfig#PUBLIC_AUTH_ENDPOINTS}, and chama endpoints also
 * need the permission named by {@link RequiresChamaPermission}. That permission is added to each
 * operation's description and as the {@code x-chama-permission} extension, so clients can read it.
 * Every operation also documents the shared error envelope for 400/401/403 and other 4xx.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    static final String BEARER_SCHEME = "bearerAuth";
    static final String PERMISSION_EXTENSION = "x-chama-permission";
    static final String MEMBERSHIP_EXTENSION = "x-chama-membership";
    static final String ERROR_SCHEMA = "ApiErrorResponse";

    @Bean
    OpenAPI chamaOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Chama Management API")
                        .version("v1")
                        .description("""
                                Contributions, loans, expenses and members for Kenyan chamas.

                                **Responses.** Every response, success or error, has the same shape:
                                `{ success, message, data, meta?, error?, timestamp }`.
                                Check `success`; on `true` read `data` (and `meta` for paged lists), on \
                                `false` show `message` and use `error.code` to branch and `error.fields` \
                                to mark invalid form fields. `message` is always safe to show to users.

                                **Authorize.** Call `POST /api/v1/auth/login`, copy `data.accessToken` from the \
                                response and paste it into **Authorize** (no `Bearer ` prefix). Tokens last \
                                15 minutes; use `POST /api/v1/auth/refresh` for a new one.

                                **Permissions.** Chama endpoints list the permission they need. A caller \
                                without it, or who is not an active member of the chama, gets 403.

                                **Money** is in minor units (cents): `500000` is KES 5,000.00."""))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token from POST /api/v1/auth/login (data.accessToken)")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
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
                operation.addExtension(MEMBERSHIP_EXTENSION, true);
                appendDescription(operation, "**Requires:** active membership of this chama");
            }
            return operation;
        };
    }

    /**
     * Runs once the document is built: removes the padlock from public endpoints, then adds the
     * error responses each operation can return.
     */
    @Bean
    OpenApiCustomizer securityAndErrorResponses() {
        Set<String> publicPaths = Set.of(SecurityConfig.PUBLIC_AUTH_ENDPOINTS);
        return openApi -> {
            registerErrorSchemas(openApi);
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> {
                boolean isPublic = publicPaths.contains(path);
                if (isPublic) {
                    operation.setSecurity(List.of());
                }
                addErrorResponses(operation, !isPublic);
            }));
        };
    }

    // ---- Error envelope documentation ---------------------------------------------------------

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerErrorSchemas(OpenAPI openApi) {
        Components components = openApi.getComponents();
        Map<String, Schema> generated = ModelConverters.getInstance().readAll(new AnnotatedType(ApiError.class));
        generated.forEach(components::addSchemas);

        Schema<?> envelope = new ObjectSchema()
                .description("Returned for every error. `data` is always null.")
                .addProperty("success", new BooleanSchema().example(false))
                .addProperty("message", new StringSchema()
                        .description("Human-readable reason, safe to show to the user"))
                .addProperty("data", new ObjectSchema().description("Always null on errors"))
                .addProperty("error", new Schema<>().$ref("#/components/schemas/ApiError"))
                .addProperty("timestamp", new DateTimeSchema());
        envelope.setRequired(List.of("success", "message", "data", "error", "timestamp"));
        components.addSchemas(ERROR_SCHEMA, envelope);
    }

    private static void addErrorResponses(Operation operation, boolean secured) {
        ApiResponses responses = operation.getResponses() == null ? new ApiResponses() : operation.getResponses();
        String path = "/api/v1/...";

        responses.putIfAbsent("400", error("Invalid input. `error.fields` lists each invalid field.",
                example("One or more fields are invalid", "VALIDATION_FAILED", 400, path,
                        List.of(Map.of("field", "email", "message", "must be a well-formed email address")))));
        if (secured) {
            responses.putIfAbsent("401", error("Missing, invalid or expired access token. Refresh it or log in again.",
                    example("Authentication is required", "UNAUTHENTICATED", 401, path, List.of())));
        }
        if (operation.getExtensions() != null
                && (operation.getExtensions().containsKey(PERMISSION_EXTENSION)
                    || operation.getExtensions().containsKey(MEMBERSHIP_EXTENSION))) {
            responses.putIfAbsent("403", error("The caller lacks the permission, or is not an active member of the chama.",
                    example("You do not have permission to perform this action", "ACCESS_DENIED", 403, path,
                            List.of())));
        }
        responses.putIfAbsent("4XX", error(
                "Other errors: 404 not found, 409 conflict, 422 business rule, 429 too many requests "
                        + "(with `error.retryAfterSeconds`). Branch on `error.code`.",
                example("Loan amount exceeds 3x your savings", "LOAN_NOT_ELIGIBLE", 422, path, List.of())));
        operation.setResponses(responses);
    }

    private static ApiResponse error(String description, Map<String, Object> example) {
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                        new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/" + ERROR_SCHEMA))
                                .example(example)));
    }

    private static Map<String, Object> example(String message, String code, int status, String path,
                                               List<Map<String, String>> fields) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("status", status);
        error.put("path", path);
        error.put("fields", fields);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        body.put("data", null);
        body.put("error", error);
        body.put("timestamp", "2026-11-06T09:00:00Z");
        return body;
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private static <A extends Annotation> A find(HandlerMethod handlerMethod, Class<A> type) {
        A onMethod = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), type);
        return onMethod != null ? onMethod : AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), type);
    }

    private static void appendDescription(Operation operation, String line) {
        String existing = operation.getDescription();
        operation.setDescription(existing == null || existing.isBlank() ? line : existing + "\n\n" + line);
    }
}
