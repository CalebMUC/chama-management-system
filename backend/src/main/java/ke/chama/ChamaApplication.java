package ke.chama;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
@EnableScheduling   // Required for @Scheduled contribution cycle jobs
public class ChamaApplication {

	public static void main(String[] args) {
		SpringApplication.run(ChamaApplication.class, args);
	}
}