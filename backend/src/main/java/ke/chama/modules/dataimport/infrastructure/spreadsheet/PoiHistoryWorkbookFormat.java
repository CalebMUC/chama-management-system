package ke.chama.modules.dataimport.infrastructure.spreadsheet;

import ke.chama.modules.dataimport.application.port.out.HistoryWorkbookFormat;
import ke.chama.modules.dataimport.domain.exception.ImportExceptions.InvalidImportFileException;
import ke.chama.modules.dataimport.domain.model.Problem;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Column;
import ke.chama.modules.dataimport.domain.model.history.Sheets.ColumnType;
import ke.chama.modules.dataimport.domain.model.history.Sheets.SheetSpec;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Template;
import ke.chama.modules.dataimport.domain.model.history.Sheets.TemplateSheet;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Workbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The historical-import workbooks in .xlsx, with Apache POI. Columns are found by heading (case,
 * spacing and a trailing * ignored), so their order does not matter.
 */
@Component
class PoiHistoryWorkbookFormat implements HistoryWorkbookFormat {

    /** Rows per sheet: three years of weekly contributions for a large chama fit comfortably. */
    static final int MAX_ROWS = 50_000;
    /** Drop-downs listed inline in the validation are limited by Excel to 255 characters. */
    private static final int MAX_INLINE_CHOICES_LENGTH = 250;
    private static final String LISTS = "Lists";

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d/M/uuuu"),
            DateTimeFormatter.ofPattern("d-M-uuuu"),
            DateTimeFormatter.ofPattern("d.M.uuuu"),
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d-MMM-uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d-MMM-uu", Locale.ENGLISH));

    // ---- reading --------------------------------------------------------------------------------

    @Override
    public Workbook read(byte[] file, int fractionDigits, List<SheetSpec> specs) {
        if (file == null || file.length == 0) {
            throw new InvalidImportFileException("The file is empty");
        }
        try (org.apache.poi.ss.usermodel.Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(file))) {
            List<Problem> problems = new ArrayList<>();
            Map<String, List<ke.chama.modules.dataimport.domain.model.history.Sheets.Row>> sheets = new HashMap<>();
            for (SheetSpec spec : specs) {
                if (!spec.reference()) {
                    sheets.put(spec.name(), readSheet(workbook, spec, fractionDigits, problems));
                }
            }
            return new Workbook(sheets, problems);
        } catch (IOException | RuntimeException e) {
            if (e instanceof InvalidImportFileException invalid) {
                throw invalid;
            }
            throw new InvalidImportFileException("The file could not be read as an Excel workbook (.xlsx). "
                    + "Download the template and save your copy as .xlsx.");
        }
    }

    private static List<ke.chama.modules.dataimport.domain.model.history.Sheets.Row> readSheet(
            org.apache.poi.ss.usermodel.Workbook workbook, SheetSpec spec, int fractionDigits, List<Problem> problems) {
        List<ke.chama.modules.dataimport.domain.model.history.Sheets.Row> rows = new ArrayList<>();
        Sheet sheet = find(workbook, spec.name());
        if (sheet == null) {
            if (spec.required()) {
                problems.add(Problem.error(spec.name(), null, null, "The " + spec.name() + " sheet is missing"));
            }
            return rows;
        }
        Row header = sheet.getRow(sheet.getFirstRowNum());
        Map<String, Integer> index = new HashMap<>();
        if (header != null) {
            for (Cell cell : header) {
                String heading = normalize(cell.toString());
                if (!heading.isEmpty()) {
                    index.putIfAbsent(heading, cell.getColumnIndex());
                }
            }
        }
        boolean missing = false;
        for (Column column : spec.columns()) {
            if (column.required() && !index.containsKey(normalize(column.heading()))) {
                problems.add(Problem.error(spec.name(), 1, column.heading(), "The heading \"" + column.heading()
                        + "\" is missing. Do not rename or delete the template's headings."));
                missing = true;
            }
        }
        if (missing) {
            return rows;
        }
        int first = sheet.getFirstRowNum() + 1;
        int last = Math.min(sheet.getLastRowNum(), first + MAX_ROWS - 1);
        for (int i = first; i <= last; i++) {
            Row row = sheet.getRow(i);
            if (row == null || blank(row, spec.columns(), index)) {
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            Set<String> unreadable = new HashSet<>();
            for (Column column : spec.columns()) {
                Integer at = index.get(normalize(column.heading()));
                Cell cell = at == null ? null : row.getCell(at);
                if (cell == null || cell.getCellType() == CellType.BLANK || cell.toString().isBlank()) {
                    continue;
                }
                Object value = value(cell, column.type(), fractionDigits);
                if (value == null) {
                    unreadable.add(column.heading());
                    problems.add(Problem.error(spec.name(), i + 1, column.heading(), unreadableMessage(column.type(), cell)));
                } else {
                    values.put(column.heading(), value);
                }
            }
            rows.add(new ke.chama.modules.dataimport.domain.model.history.Sheets.Row(spec.name(), i + 1, values, unreadable));
        }
        if (sheet.getLastRowNum() > last && hasDataAfter(sheet, last, spec.columns(), index)) {
            problems.add(Problem.error(spec.name(), null, null, "At most " + MAX_ROWS
                    + " rows can be imported per sheet: split the file (e.g. one file per year)"));
        }
        return rows;
    }

    private static boolean hasDataAfter(Sheet sheet, int last, List<Column> columns, Map<String, Integer> index) {
        for (int i = last + 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row != null && !blank(row, columns, index)) {
                return true;
            }
        }
        return false;
    }

    private static Sheet find(org.apache.poi.ss.usermodel.Workbook workbook, String name) {
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            if (workbook.getSheetName(i).trim().equalsIgnoreCase(name)) {
                return workbook.getSheetAt(i);
            }
        }
        return null;
    }

    private static boolean blank(Row row, List<Column> columns, Map<String, Integer> index) {
        for (Column column : columns) {
            Integer at = index.get(normalize(column.heading()));
            if (at != null) {
                Cell cell = row.getCell(at);
                if (cell != null && cell.getCellType() != CellType.BLANK && !cell.toString().isBlank()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String unreadableMessage(ColumnType type, Cell cell) {
        return switch (type) {
            case AMOUNT -> "Not an amount: " + cell;
            case DATE -> "Not a date: " + cell + ". Use a date such as 2024-03-15.";
            case INTEGER -> "Not a whole number: " + cell;
            case PERCENT -> "Not a percentage: " + cell;
            case TEXT -> "Cannot be read: " + cell;
        };
    }

    /** The cell's value for the column type, or null if it cannot be read as one. */
    static Object value(Cell cell, ColumnType type, int fractionDigits) {
        CellType cellType = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case TEXT -> text(cell, cellType);
            case AMOUNT -> {
                BigDecimal n = number(cell, cellType);
                yield n == null ? null : n.setScale(fractionDigits, RoundingMode.HALF_UP).movePointRight(fractionDigits)
                        .longValueExact();
            }
            case INTEGER -> {
                BigDecimal n = number(cell, cellType);
                yield n == null || n.stripTrailingZeros().scale() > 0 ? null : n.longValueExact();
            }
            case PERCENT -> {
                BigDecimal n = number(cell, cellType);
                if (n == null) {
                    yield null;
                }
                String format = cell.getCellStyle() == null ? null : cell.getCellStyle().getDataFormatString();
                boolean formattedAsPercent = cellType == CellType.NUMERIC && format != null && format.contains("%");
                BigDecimal percent = formattedAsPercent ? n.movePointRight(2) : n;
                yield percent.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
            }
            case DATE -> date(cell, cellType);
        };
    }

    private static String text(Cell cell, CellType type) {
        String value = switch (type) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? DateUtil.getLocalDateTime(cell.getNumericCellValue()).toLocalDate().toString()
                    : BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
        return value.isBlank() ? null : value.trim();
    }

    private static LocalDate date(Cell cell, CellType type) {
        if (type == CellType.NUMERIC) {
            double serial = cell.getNumericCellValue();
            if (DateUtil.isCellDateFormatted(cell) || (serial > 10_000 && serial < 100_000)) {
                return DateUtil.getLocalDateTime(serial).toLocalDate();
            }
            return null;
        }
        if (type == CellType.STRING) {
            String text = cell.getStringCellValue().trim();
            for (DateTimeFormatter format : DATE_FORMATS) {
                try {
                    return LocalDate.parse(text, format);
                } catch (DateTimeParseException ignored) {
                    // try the next format
                }
            }
        }
        return null;
    }

    private static BigDecimal number(Cell cell, CellType type) {
        if (type == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue());
        }
        if (type == CellType.STRING) {
            String text = cell.getStringCellValue().replaceAll("(?i)kes|ksh|kshs|%|,|\\s", "");
            try {
                return text.isEmpty() ? null : new BigDecimal(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    static String normalize(String heading) {
        return heading == null ? "" : heading.replace("*", "").trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    // ---- writing --------------------------------------------------------------------------------

    @Override
    public byte[] write(Template template) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);
            readMe(workbook, styles, template);
            Lists lists = new Lists(workbook);
            for (TemplateSheet ts : template.sheets()) {
                writeSheet(workbook, styles, lists, ts);
            }
            lists.finish();
            workbook.setActiveSheet(0);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the import template", e);
        }
    }

    private static void readMe(XSSFWorkbook workbook, Styles styles, Template template) {
        Sheet sheet = workbook.createSheet("Read me");
        sheet.setColumnWidth(0, 26 * 256);
        sheet.setColumnWidth(1, 26 * 256);
        sheet.setColumnWidth(2, 11 * 256);
        sheet.setColumnWidth(3, 90 * 256);
        int r = 0;
        for (int i = 0; i < template.readMe().size(); i++) {
            Cell cell = sheet.createRow(r++).createCell(0);
            cell.setCellValue(template.readMe().get(i));
            cell.setCellStyle(i == 0 ? styles.title : styles.wrap);
        }
        r++;
        Row head = sheet.createRow(r++);
        String[] headings = {"Sheet", "Column", "Required", "What to enter"};
        for (int i = 0; i < headings.length; i++) {
            Cell cell = head.createCell(i);
            cell.setCellValue(headings[i]);
            cell.setCellStyle(styles.header);
        }
        for (TemplateSheet ts : template.sheets()) {
            for (Column c : ts.spec().columns()) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(ts.spec().name());
                row.createCell(1).setCellValue(c.heading());
                row.createCell(2).setCellValue(c.required() ? "Yes" : "");
                row.createCell(3).setCellValue(c.note() == null ? "" : c.note());
            }
        }
    }

    private static void writeSheet(XSSFWorkbook workbook, Styles styles, Lists lists, TemplateSheet ts) {
        SheetSpec spec = ts.spec();
        Sheet sheet = workbook.createSheet(spec.name());
        Row header = sheet.createRow(0);
        List<Column> columns = spec.columns();
        for (int i = 0; i < columns.size(); i++) {
            Column c = columns.get(i);
            Cell cell = header.createCell(i);
            cell.setCellValue(c.heading() + (c.required() && !spec.reference() ? " *" : ""));
            cell.setCellStyle(styles.header);
            sheet.setColumnWidth(i, Math.max(c.width(), c.heading().length() + 3) * 256);
            sheet.setDefaultColumnStyle(i, styles.forType(c.type()));
        }
        sheet.createFreezePane(0, 1);
        int r = 1;
        for (List<Object> values : ts.rows()) {
            Row row = sheet.createRow(r++);
            for (int i = 0; i < values.size() && i < columns.size(); i++) {
                Object v = values.get(i);
                Cell cell = row.createCell(i);
                cell.setCellStyle(styles.forType(columns.get(i).type()));
                switch (v) {
                    case null -> cell.setBlank();
                    case LocalDate d -> cell.setCellValue(d);
                    case BigDecimal b -> cell.setCellValue(b.doubleValue());
                    case Number n -> cell.setCellValue(n.doubleValue());
                    default -> cell.setCellValue(v.toString());
                }
            }
        }
        if (spec.reference()) {
            return;
        }
        DataValidationHelper helper = sheet.getDataValidationHelper();
        for (int i = 0; i < columns.size(); i++) {
            Column c = columns.get(i);
            if (c.choices().isEmpty()) {
                continue;
            }
            DataValidationConstraint constraint = String.join(",", c.choices()).length() <= MAX_INLINE_CHOICES_LENGTH
                    && c.choices().stream().noneMatch(s -> s.contains(","))
                    ? helper.createExplicitListConstraint(c.choices().toArray(String[]::new))
                    : helper.createFormulaListConstraint(lists.range(c.choices()));
            DataValidation validation = helper.createValidation(constraint, new CellRangeAddressList(1, MAX_ROWS, i, i));
            // Typing a value that is not in the list is allowed: it is checked on preview instead
            // (e.g. a member number typed by hand).
            validation.setShowErrorBox(false);
            validation.setSuppressDropDownArrow(true);
            sheet.addValidationData(validation);
        }
    }

    /** Long drop-down lists live on a hidden sheet and are referenced by range. */
    private static final class Lists {
        private final XSSFWorkbook workbook;
        private Sheet sheet;
        private int column;

        Lists(XSSFWorkbook workbook) {
            this.workbook = workbook;
        }

        String range(List<String> values) {
            if (sheet == null) {
                sheet = workbook.createSheet(LISTS);
            }
            for (int i = 0; i < values.size(); i++) {
                Row row = sheet.getRow(i) == null ? sheet.createRow(i) : sheet.getRow(i);
                row.createCell(column).setCellValue(values.get(i));
            }
            String letter = org.apache.poi.ss.util.CellReference.convertNumToColString(column++);
            return "'" + LISTS + "'!$" + letter + "$1:$" + letter + "$" + Math.max(values.size(), 1);
        }

        void finish() {
            if (sheet != null) {
                workbook.setSheetHidden(workbook.getSheetIndex(sheet), true);
            }
        }
    }

    private static final class Styles {
        final CellStyle header;
        final CellStyle title;
        final CellStyle wrap;
        final CellStyle date;
        final CellStyle amount;
        final CellStyle text;
        final CellStyle general;

        Styles(XSSFWorkbook workbook) {
            DataFormat format = workbook.createDataFormat();
            Font bold = workbook.createFont();
            bold.setBold(true);
            header = workbook.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font big = workbook.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            title = workbook.createCellStyle();
            title.setFont(big);
            wrap = workbook.createCellStyle();
            date = workbook.createCellStyle();
            date.setDataFormat(format.getFormat("yyyy-mm-dd"));
            amount = workbook.createCellStyle();
            amount.setDataFormat(format.getFormat("#,##0.00"));
            text = workbook.createCellStyle();
            text.setDataFormat(format.getFormat("@"));
            general = workbook.createCellStyle();
        }

        CellStyle forType(ColumnType type) {
            return switch (type) {
                case DATE -> date;
                case AMOUNT -> amount;
                case TEXT -> text;
                case INTEGER, PERCENT -> general;
            };
        }
    }
}
