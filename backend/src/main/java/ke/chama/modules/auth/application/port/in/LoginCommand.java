package ke.chama.modules.auth.application.port.in;

public record LoginCommand(String email, String password) {
}
