package ke.chama.modules.chama.domain.exception;

import ke.chama.shared.domain.exception.DomainException;
import ke.chama.shared.domain.exception.ErrorType;

import java.util.UUID;

public class ChamaNotFoundException extends DomainException {

    public ChamaNotFoundException(UUID chamaId) {
        super("CHAMA_NOT_FOUND", ErrorType.NOT_FOUND, "Chama not found: " + chamaId);
    }
}
