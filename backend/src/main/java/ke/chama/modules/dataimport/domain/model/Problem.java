package ke.chama.modules.dataimport.domain.model;

/**
 * Something wrong with an import file. An error stops the import; a warning does not.
 *
 * @param sheet  the sheet, or null for the whole file
 * @param row    the row as Excel numbers it, or null
 * @param column the column heading, or null
 */
public record Problem(Severity severity, String sheet, Integer row, String column, String message) {

    public enum Severity {
        ERROR,
        WARNING
    }

    public static Problem error(String sheet, Integer row, String column, String message) {
        return new Problem(Severity.ERROR, sheet, row, column, message);
    }

    public static Problem warning(String sheet, Integer row, String column, String message) {
        return new Problem(Severity.WARNING, sheet, row, column, message);
    }

    public boolean blocking() {
        return severity == Severity.ERROR;
    }
}
