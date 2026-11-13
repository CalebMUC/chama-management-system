package ke.chama.modules.dataimport.application.port.out;

import ke.chama.modules.dataimport.domain.model.history.Sheets.SheetSpec;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Template;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Workbook;

import java.util.List;

/** Reads and writes the historical-import workbooks (.xlsx). */
public interface HistoryWorkbookFormat {

    /**
     * Reads the data sheets among {@code sheets} (lookup sheets are skipped). Columns are found by
     * heading, in any order; blank rows are skipped. Cells that cannot be read are reported as
     * problems; a file that is not a readable workbook throws {@code InvalidImportFileException}.
     *
     * @param fractionDigits decimal places of the chama's currency (2 for KES)
     */
    Workbook read(byte[] file, int fractionDigits, List<SheetSpec> sheets);

    /** The template: a Read me sheet with the instructions and every column explained, then each sheet. */
    byte[] write(Template template);
}
