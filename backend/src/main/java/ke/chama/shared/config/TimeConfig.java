package ke.chama.shared.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Single source of "now". Inject {@link Clock} instead of calling {@code LocalDate.now()}, so
 * due dates, penalties and loan schedules can be tested with a fixed clock.
 * <p>
 * Instants are zone-independent and stored as TIMESTAMPTZ (UTC). The zone only affects
 * calendar values such as "the contribution is due on the 5th", which follow the chama's local time.
 */
@Configuration(proxyBeanMethods = false)
class TimeConfig {

    @Bean
    Clock clock(@Value("${chama.time-zone:Africa/Nairobi}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
