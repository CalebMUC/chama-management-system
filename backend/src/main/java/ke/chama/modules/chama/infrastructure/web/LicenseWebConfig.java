package ke.chama.modules.chama.infrastructure.web;

import ke.chama.modules.chama.application.service.LicenseCache;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class LicenseWebConfig implements WebMvcConfigurer {

    private final LicenseCache licenseCache;

    LicenseWebConfig(LicenseCache licenseCache) {
        this.licenseCache = licenseCache;
    }

    /** Signing in, refreshing and recovering an account never depend on the licence. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LicenseInterceptor(licenseCache))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/v1/auth/**", "/api/v1/platform/**");
    }
}
