package ke.chama.shared.tenancy;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Wires tenant isolation into the persistence stack:
 * <ul>
 *     <li>wraps the application DataSource in {@link TenantAwareDataSource} (row-level security);</li>
 *     <li>registers {@link TenantIdentifierResolver} with Hibernate (tenant filter on {@code @TenantId}).</li>
 * </ul>
 * Flyway migrates through its own connection as the schema owner ({@code spring.flyway.url/user}),
 * so migrations are not subject to row-level security.
 */
@Configuration(proxyBeanMethods = false)
public class TenancyConfig {

    static final String DATA_SOURCE_BEAN = "dataSource";

    @Bean
    static BeanPostProcessor tenantAwareDataSourcePostProcessor(
            @Value("${chama.tenancy.database-role:chama_app}") String databaseRole) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (DATA_SOURCE_BEAN.equals(beanName) && bean instanceof DataSource dataSource
                        && !(bean instanceof TenantAwareDataSource)) {
                    return new TenantAwareDataSource(dataSource, databaseRole);
                }
                return bean;
            }
        };
    }

    @Bean
    HibernatePropertiesCustomizer tenantIdentifierResolverCustomizer() {
        TenantIdentifierResolver resolver = new TenantIdentifierResolver();
        return properties -> properties.put("hibernate.tenant_identifier_resolver", resolver);
    }
}
