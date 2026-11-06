package ke.chama.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.annotation.AnnotationTemplateExpressionDefaults;

/**
 * Lets meta-annotations fill placeholders in {@code @PreAuthorize}, so
 * {@code @RequiresChamaPermission(LOAN_APPROVE)} becomes
 * {@code @PreAuthorize("@chamaAccess.has(#chamaId, 'LOAN_APPROVE')")}.
 */
@Configuration(proxyBeanMethods = false)
class MethodSecurityConfig {

    @Bean
    static AnnotationTemplateExpressionDefaults annotationTemplateExpressionDefaults() {
        return new AnnotationTemplateExpressionDefaults();
    }
}
