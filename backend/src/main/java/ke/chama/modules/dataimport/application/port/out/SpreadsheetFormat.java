package ke.chama.modules.dataimport.application.port.out;

import ke.chama.modules.dataimport.domain.model.OpeningBalanceSheets;

import java.util.Set;

/** Reads and writes the opening-balance workbook (.xlsx). */
public interface SpreadsheetFormat {

    /**
     * Reads every row of every sheet. Cells that cannot be read (e.g. text where a date belongs)
     * are left null and reported as problems; a file that is not a readable workbook throws
     * {@code InvalidImportFileException}.
     *
     * @param fractionDigits decimal places of the chama's currency (2 for KES)
     */
    OpeningBalanceSheets read(byte[] file, int fractionDigits);

    /** The empty template, with instructions, headings and the allowed roles and investment types. */
    byte[] template(Set<String> roleNames, Set<String> investmentTypes);
}
