package ke.chama.modules.auth.domain.event;

import java.util.UUID;

/**
 * published after successful user registration
 * member-service listens to create a member profile
 */
public record UserRegisteredEvent(UUID userId,
                                  String email,
                                  String firstName,
                                  String lastName) {
}
