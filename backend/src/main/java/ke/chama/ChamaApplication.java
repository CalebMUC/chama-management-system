package ke.chama;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Chama Management System — Modular Monolith
 *
 * Single deployable Spring Boot application containing
 * all business modules: auth, member, contribution, loan,
 * penalty, investment, transaction, notification, report.
 *
 * Module boundaries are drawn to mirror a microservices design.
 * Each module is independently extractable when scale demands it.
 */
@SpringBootApplication
@ConfigurationPropertiesScan   // Binds @ConfigurationProperties records such as AuthProperties
@EnableScheduling              // Required for @Scheduled jobs (contribution cycles, token cleanup)
@EnableAsync                   // @ApplicationModuleListener runs async, so emails never slow down a request
public class ChamaApplication {

	public static void main(String[] args) {
		SpringApplication.run(ChamaApplication.class, args);
	}
}
