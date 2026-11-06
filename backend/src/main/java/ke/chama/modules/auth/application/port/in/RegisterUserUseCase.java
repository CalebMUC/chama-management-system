package ke.chama.modules.auth.application.port.in;

public interface RegisterUserUseCase {

    /** Creates an account with ROLE_USER and publishes UserRegisteredEvent. */
    UserProfile register(RegisterUserCommand command);
}
