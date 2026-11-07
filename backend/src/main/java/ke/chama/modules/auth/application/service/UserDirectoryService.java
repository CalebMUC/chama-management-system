package ke.chama.modules.auth.application.service;

import ke.chama.modules.auth.api.UserDirectory;
import ke.chama.modules.auth.api.UserSummary;
import ke.chama.modules.auth.application.port.out.UserRepository;
import ke.chama.modules.auth.domain.model.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
class UserDirectoryService implements UserDirectory {

    private final UserRepository users;

    UserDirectoryService(UserRepository users) {
        this.users = users;
    }

    @Override
    public Optional<UserSummary> findById(UUID userId) {
        return users.findById(userId).map(UserDirectoryService::toSummary);
    }

    @Override
    public Optional<UserSummary> findByEmail(String email) {
        // A malformed address cannot belong to anyone: nothing to find, nothing to complain about.
        if (email == null || email.isBlank() || !email.contains("@")) {
            return Optional.empty();
        }
        return users.findByEmail(User.normalizeEmail(email)).map(UserDirectoryService::toSummary);
    }

    @Override
    public Map<UUID, UserSummary> findByIds(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(userIds).stream()
                .map(UserDirectoryService::toSummary)
                .collect(Collectors.toMap(UserSummary::id, Function.identity()));
    }

    private static UserSummary toSummary(User user) {
        return new UserSummary(user.getId(), user.getEmail(), user.isEmailVerified(), user.getPhoneNumber(),
                user.getFirstName(), user.getLastName(), user.isEnabled());
    }
}
