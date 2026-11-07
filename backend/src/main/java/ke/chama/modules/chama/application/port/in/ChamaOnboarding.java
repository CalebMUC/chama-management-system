package ke.chama.modules.chama.application.port.in;

import ke.chama.modules.chama.domain.model.ChamaSettings;

import java.util.Currency;
import java.util.UUID;

/**
 * Registers a new chama (tenant) on the platform: the chama, its trial licence and its first
 * administrator (chairperson), all in one transaction.
 */
public interface ChamaOnboarding {

    OnboardingResult onboard(Command command);

    /**
     * @param license the starting licence terms, replacing the trial's; only platform
     *                administrators may set them. Null means the standard trial.
     */
    record Command(String name,
                   String code,
                   String description,
                   Currency currency,
                   ChamaSettings settings,
                   Admin admin,
                   PlatformChamaAdmin.LicenseChange license) {
    }

    record Admin(String email, String phoneNumber, String password, String firstName, String lastName) {

        @Override
        public String toString() {
            return "Admin[email=" + email + ", password=***]";
        }
    }

    record OnboardingResult(ChamaView chama, LicenseView license, UUID adminUserId, String adminEmail) {
    }
}
