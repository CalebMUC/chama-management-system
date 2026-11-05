package ke.chama.modules.auth.application.port.in;

public record RegisterUserCommand(String email,
                                  String phoneNumber,
                                  String password,
                                  String firstName,
                                  String lastName) {
}
