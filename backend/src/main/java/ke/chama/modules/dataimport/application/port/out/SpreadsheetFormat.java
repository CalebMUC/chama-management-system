package ke.chama.modules.dataimport.application.port.out;

import ke.chama.modules.dataimport.domain.model.OpeningBalanceSheets;

import java.util.List;
import java.util.Set;

/** Reads and writes the opening-balance workbook (.xlsx). */
public interface SpreadsheetFormat {

    /**
     * Reads every row of every sheet. Cells that cannot be read (e.g. text where a date belongs)
     * are left null and reported as problems; a file that is not a readable workbook throws
     * {@code InvalidImportFileException}.
     *
     * @param fractionDigits      decimal places of the chama's currency (2 for KES)
     * @param otherSavingsColumns headings of the Members sheet's extra savings columns, one per savings
     *                            product besides the default; missing columns are simply empty
     */
    OpeningBalanceSheets read(byte[] file, int fractionDigits, List<String> otherSavingsColumns);

    default OpeningBalanceSheets read(byte[] file, int fractionDigits) {
        return read(file, fractionDigits, List.of());
    }

    /**
     * The empty template, with instructions, headings and the allowed roles and investment types,
     * and an extra savings column on the Members sheet per heading in {@code otherSavingsColumns}.
     */
    byte[] template(Set<String> roleNames, Set<String> investmentTypes, List<String> otherSavingsColumns);

    default byte[] template(Set<String> roleNames, Set<String> investmentTypes) {
        return template(roleNames, investmentTypes, List.of());
    }
}
