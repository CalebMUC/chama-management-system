package ke.chama.modules.chama.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Bound from {@code chama.licensing.*}.
 *
 * @param trialLength       how long the trial licence of a new chama lasts
 */
@ConfigurationProperties(prefix = "chama.licensing")
public record LicensingProperties(@DefaultValue("30d") Duration trialLength) {

    public LicensingProperties {
        if (trialLength == null || trialLength.isNegative() || trialLength.isZero()) {
            throw new IllegalStateException("chama.licensing.trial-length must be a positive duration");
        }
    }
}
