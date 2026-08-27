package ke.chama.modules.auth.domain.exception;

public class AuthException extends RuntimeException{
    //constructor
    public AuthException(String message){
        super(message);
    }
}
