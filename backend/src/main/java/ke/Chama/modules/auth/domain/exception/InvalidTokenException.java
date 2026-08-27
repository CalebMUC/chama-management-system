package ke.chama.modules.auth.domain.exception;

public class InvalidTokenException extends AuthException {
    public InvalidTokenException(String message){
        super(message);
    }
}
