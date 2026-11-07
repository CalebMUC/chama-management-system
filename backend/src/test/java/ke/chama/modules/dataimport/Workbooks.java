package ke.chama.modules.dataimport;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;

/** Fills in the downloaded template the way a treasurer would in Excel. */
public final class Workbooks {

    private final Workbook workbook;

    private Workbooks(Workbook workbook) {
        this.workbook = workbook;
    }

    public static Workbooks from(byte[] template) {
        try {
            return new Workbooks(WorkbookFactory.create(new ByteArrayInputStream(template)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes {@code values} into the next empty row of {@code sheet} (numbers, dates and text). */
    public Workbooks row(String sheet, Object... values) {
        Sheet s = workbook.getSheet(sheet);
        Row row = s.createRow(s.getLastRowNum() + 1);
        set(row, values);
        return this;
    }

    /** Overwrites row {@code excelRow} (1-based, as Excel shows it). */
    public Workbooks setRow(String sheet, int excelRow, Object... values) {
        Sheet s = workbook.getSheet(sheet);
        Row row = s.getRow(excelRow - 1) == null ? s.createRow(excelRow - 1) : s.getRow(excelRow - 1);
        set(row, values);
        return this;
    }

    private void set(Row row, Object... values) {
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            if (value == null) {
                continue;
            }
            Cell cell = row.createCell(i);
            switch (value) {
                case Number n -> cell.setCellValue(n.doubleValue());
                case LocalDate d -> {
                    cell.setCellValue(d);
                    cell.setCellStyle(workbook.getSheet(row.getSheet().getSheetName()).getColumnStyle(i));
                }
                default -> cell.setCellValue(value.toString());
            }
        }
    }

    public byte[] bytes() {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
