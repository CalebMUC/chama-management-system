package ke.chama.modules.auth.application.port.in;

public interface LoginUseCase {

    AuthTokens login(LoginCommand command);
}
