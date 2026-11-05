package ke.chama.modules.auth.domain.exception;

public class UserNotFoundException extends AuthException{
    public UserNotFoundException(String identifier){
        super("User not found: " + identifier);
    }
}
