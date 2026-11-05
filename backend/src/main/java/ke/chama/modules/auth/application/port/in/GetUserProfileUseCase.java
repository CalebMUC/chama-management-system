package ke.chama.modules.auth.application.port.in;

import java.util.UUID;

public interface GetUserProfileUseCase {

    UserProfile getProfile(UUID userId);
}
