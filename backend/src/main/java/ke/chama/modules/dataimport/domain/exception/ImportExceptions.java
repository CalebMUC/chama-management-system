package ke.chama.modules.dataimport.domain.exception;

import ke.chama.shared.domain.exception.DomainException;
import ke.chama.shared.domain.exception.ErrorType;

/** Import errors. Codes are prefixed IMPORT_. */
public final class ImportExceptions {

    private ImportExceptions() {
    }

    /** Not an .xlsx workbook, empty, too large, or unreadable. */
    public static class InvalidImportFileException extends DomainException {
        public InvalidImportFileException(String message) {
            super("IMPORT_INVALID_FILE", ErrorType.VALIDATION, message);
        }
    }

    public static class ImportHasProblemsException extends DomainException {
        public ImportHasProblemsException(long errors) {
            super("IMPORT_HAS_PROBLEMS", ErrorType.BUSINESS_RULE, "The file has " + errors + " problem"
                    + (errors == 1 ? "" : "s") + " to fix first. Preview it to see each one with its sheet, row and column.");
        }
    }

    /** Opening balances are brought over once; afterwards everything is recorded in the system. */
    public static class AlreadyImportedException extends DomainException {
        public AlreadyImportedException(String asOf) {
            super("IMPORT_ALREADY_DONE", ErrorType.CONFLICT, "Opening balances were already imported (as at " + asOf
                    + "). Record later changes as contributions, loans, expenses and so on.");
        }
    }
}
