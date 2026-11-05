package ke.chama.modules.auth.domain.exception;

public class UserAlreadyExistsException extends AuthException{
    public UserAlreadyExistsException(String email){
        super("User Already Exists with :" + email);
    }
}
