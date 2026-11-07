package ke.chama.modules.dataimport.infrastructure.spreadsheet;

import ke.chama.modules.dataimport.application.port.out.SpreadsheetFormat;
import ke.chama.modules.dataimport.domain.exception.ImportExceptions.InvalidImportFileException;
import ke.chama.modules.dataimport.domain.model.OpeningBalanceSheets;
import ke.chama.modules.dataimport.domain.model.OpeningBalanceSheets.AccountRow;
import ke.chama.modules.dataimport.domain.model.OpeningBalanceSheets.InvestmentRow;
import ke.chama.modules.dataimport.domain.model.OpeningBalanceSheets.LoanRow;
import ke.chama.modules.dataimport.domain.model.OpeningBalanceSheets.MemberRow;
import ke.chama.modules.dataimport.domain.model.Problem;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static ke.chama.modules.dataimport.domain.model.OpeningBalanceSheets.ACCOUNTS;
import static ke.chama.modules.dataimport.domain.model.OpeningBalanceSheets.INVESTMENTS;
import static ke.chama.modules.dataimport.domain.model.OpeningBalanceSheets.LOANS;
import static ke.chama.modules.dataimport.domain.model.OpeningBalanceSheets.MEMBERS;

/**
 * The opening-balance workbook in .xlsx, read and written with Apache POI. Columns are found by
 * their heading (case and a trailing * ignored), so their order does not matter; blank rows are
 * skipped.
 */
@Component
class PoiSpreadsheetFormat implements SpreadsheetFormat {

    static final int MAX_ROWS = 2_000;

    // Headings, exactly as written in the template.
    static final String EMAIL = "Email", FIRST_NAME = "First name", LAST_NAME = "Last name", PHONE = "Phone",
            JOINED_ON = "Joined on", ROLES = "Roles", SAVINGS = "Savings (KES)", PENALTIES = "Penalties owed (KES)";
    static final String MEMBER_EMAIL = "Member email", PRINCIPAL = "Principal (KES)", INTEREST = "Interest (KES)",
            TERM = "Term (months)", DISBURSED_ON = "Disbursed on", REPAID = "Repaid so far (KES)", PURPOSE = "Purpose";
    static final String ACCOUNT = "Account", BALANCE = "Balance (KES)";
    static final String NAME = "Name", TYPE = "Type", INSTITUTION = "Institution", INVESTED_ON = "Invested on",
            AMOUNT_INVESTED = "Amount invested (KES)", CURRENT_VALUE = "Current value (KES)",
            EXPECTED_RETURN = "Expected return (%)";

    private static final List<String> MEMBER_COLUMNS = List.of(EMAIL, FIRST_NAME, LAST_NAME, PHONE, JOINED_ON, ROLES,
            SAVINGS, PENALTIES);
    private static final List<String> LOAN_COLUMNS = List.of(MEMBER_EMAIL, PRINCIPAL, INTEREST, TERM, DISBURSED_ON,
            REPAID, PURPOSE);
    private static final List<String> ACCOUNT_COLUMNS = List.of(ACCOUNT, BALANCE);
    private static final List<String> INVESTMENT_COLUMNS = List.of(NAME, TYPE, INSTITUTION, INVESTED_ON,
            AMOUNT_INVESTED, CURRENT_VALUE, EXPECTED_RETURN);

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d/M/uuuu"),
            DateTimeFormatter.ofPattern("d-M-uuuu"),
            DateTimeFormatter.ofPattern("d.M.uuuu"),
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH));

    // ---- reading --------------------------------------------------------------------------------

    @Override
    public OpeningBalanceSheets read(byte[] file, int fractionDigits) {
        if (file == null || file.length == 0) {
            throw new InvalidImportFileException("The file is empty");
        }
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(file))) {
            List<Problem> problems = new ArrayList<>();
            Reader reader = new Reader(problems, fractionDigits);
            List<MemberRow> members = new ArrayList<>();
            reader.rows(workbook, MEMBERS, MEMBER_COLUMNS, List.of(EMAIL), true, (r, row) -> members.add(new MemberRow(row,
                    r.text(EMAIL), r.text(FIRST_NAME), r.text(LAST_NAME), r.phone(PHONE), r.date(JOINED_ON), r.list(ROLES),
                    r.amount(SAVINGS), r.amount(PENALTIES))));
            List<LoanRow> loans = new ArrayList<>();
            reader.rows(workbook, LOANS, LOAN_COLUMNS, List.of(MEMBER_EMAIL), false, (r, row) -> loans.add(new LoanRow(row,
                    r.text(MEMBER_EMAIL), r.amount(PRINCIPAL), r.amount(INTEREST), r.integer(TERM), r.date(DISBURSED_ON),
                    r.amount(REPAID), r.text(PURPOSE))));
            List<AccountRow> accounts = new ArrayList<>();
            reader.rows(workbook, ACCOUNTS, ACCOUNT_COLUMNS, List.of(ACCOUNT, BALANCE), false,
                    (r, row) -> accounts.add(new AccountRow(row, r.text(ACCOUNT), r.amount(BALANCE))));
            List<InvestmentRow> investments = new ArrayList<>();
            reader.rows(workbook, INVESTMENTS, INVESTMENT_COLUMNS, List.of(NAME), false, (r, row) -> investments.add(
                    new InvestmentRow(row, r.text(NAME), r.text(TYPE), r.text(INSTITUTION), r.date(INVESTED_ON),
                            r.amount(AMOUNT_INVESTED), r.amount(CURRENT_VALUE), r.percent(EXPECTED_RETURN))));
            return new OpeningBalanceSheets(members, loans, accounts, investments, problems);
        } catch (IOException | RuntimeException e) {
            if (e instanceof InvalidImportFileException invalid) {
                throw invalid;
            }
            throw new InvalidImportFileException("The file could not be read as an Excel workbook (.xlsx). "
                    + "Download the template and save your copy as .xlsx.");
        }
    }

    private interface RowHandler {
        void handle(Cells cells, int excelRow);
    }

    /** Reads the rows of one sheet, reporting unreadable cells as problems. */
    private static final class Reader {

        private final List<Problem> problems;
        private final int fractionDigits;

        Reader(List<Problem> problems, int fractionDigits) {
            this.problems = problems;
            this.fractionDigits = fractionDigits;
        }

        void rows(Workbook workbook, String sheetName, List<String> columns, List<String> requiredColumns,
                  boolean requiredSheet, RowHandler handler) {
            Sheet sheet = sheet(workbook, sheetName);
            if (sheet == null) {
                if (requiredSheet) {
                    problems.add(Problem.error(sheetName, null, null, "The " + sheetName + " sheet is missing"));
                }
                return;
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
            for (String column : requiredColumns) {
                if (!index.containsKey(normalize(column))) {
                    problems.add(Problem.error(sheetName, 1, column, "The heading \"" + column + "\" is missing"));
                    missing = true;
                }
            }
            if (missing) {
                return;
            }
            int first = sheet.getFirstRowNum() + 1;
            int last = Math.min(sheet.getLastRowNum(), first + MAX_ROWS);
            if (sheet.getLastRowNum() > last) {
                problems.add(Problem.error(sheetName, null, null, "At most " + MAX_ROWS + " rows can be imported per sheet"));
            }
            for (int i = first; i <= last; i++) {
                Row row = sheet.getRow(i);
                if (row == null || blank(row, columns, index)) {
                    continue;
                }
                handler.handle(new Cells(sheetName, row, index, problems, fractionDigits), i + 1);
            }
        }

        private static Sheet sheet(Workbook workbook, String name) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                if (workbook.getSheetName(i).trim().equalsIgnoreCase(name)) {
                    return workbook.getSheetAt(i);
                }
            }
            return null;
        }

        private static boolean blank(Row row, List<String> columns, Map<String, Integer> index) {
            for (String column : columns) {
                Integer at = index.get(normalize(column));
                if (at != null) {
                    Cell cell = row.getCell(at);
                    if (cell != null && cell.getCellType() != CellType.BLANK && !cell.toString().isBlank()) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    /** The cells of one row, by heading. */
    private record Cells(String sheet, Row row, Map<String, Integer> index, List<Problem> problems, int fractionDigits) {

        private Cell cell(String column) {
            Integer at = index.get(normalize(column));
            if (at == null) {
                return null;
            }
            Cell cell = row.getCell(at);
            return cell == null || cell.getCellType() == CellType.BLANK ? null : cell;
        }

        private CellType type(Cell cell) {
            return cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        }

        String text(String column) {
            Cell cell = cell(column);
            if (cell == null) {
                return null;
            }
            String value = switch (type(cell)) {
                case STRING -> cell.getStringCellValue();
                case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                default -> "";
            };
            return value.isBlank() ? null : value.trim();
        }

        /** Phone numbers typed as numbers lose their leading zero in Excel: 712345678 becomes 0712345678. */
        String phone(String column) {
            String value = text(column);
            if (value != null && value.matches("[17]\\d{8}")) {
                return "0" + value;
            }
            return value;
        }

        Set<String> list(String column) {
            String value = text(column);
            Set<String> items = new LinkedHashSet<>();
            if (value != null) {
                Arrays.stream(value.split("[,;/]")).map(String::trim).filter(s -> !s.isEmpty()).forEach(items::add);
            }
            return items;
        }

        Long amount(String column) {
            Cell cell = cell(column);
            if (cell == null) {
                return null;
            }
            BigDecimal value = number(cell);
            if (value == null) {
                problems.add(Problem.error(sheet, row.getRowNum() + 1, column, "Not an amount: " + cell));
                return null;
            }
            return value.setScale(fractionDigits, RoundingMode.HALF_UP).movePointRight(fractionDigits).longValueExact();
        }

        Integer integer(String column) {
            Cell cell = cell(column);
            if (cell == null) {
                return null;
            }
            BigDecimal value = number(cell);
            if (value == null || value.stripTrailingZeros().scale() > 0) {
                problems.add(Problem.error(sheet, row.getRowNum() + 1, column, "Not a whole number: " + cell));
                return null;
            }
            return value.intValueExact();
        }

        /** 12.5, "12.5%" or a cell formatted as a percentage (0.125) all mean 12.5% = 1250 basis points. */
        Integer percent(String column) {
            Cell cell = cell(column);
            if (cell == null) {
                return null;
            }
            BigDecimal value = number(cell);
            if (value == null) {
                problems.add(Problem.error(sheet, row.getRowNum() + 1, column, "Not a percentage: " + cell));
                return null;
            }
            boolean formattedAsPercent = type(cell) == CellType.NUMERIC
                    && cell.getCellStyle().getDataFormatString() != null
                    && cell.getCellStyle().getDataFormatString().contains("%");
            BigDecimal percent = formattedAsPercent ? value.movePointRight(2) : value;
            return percent.movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
        }

        LocalDate date(String column) {
            Cell cell = cell(column);
            if (cell == null) {
                return null;
            }
            if (type(cell) == CellType.NUMERIC) {
                double serial = cell.getNumericCellValue();
                if (DateUtil.isCellDateFormatted(cell) || (serial > 10_000 && serial < 100_000)) {
                    return DateUtil.getLocalDateTime(serial).toLocalDate();
                }
            } else if (type(cell) == CellType.STRING) {
                String text = cell.getStringCellValue().trim();
                for (DateTimeFormatter format : DATE_FORMATS) {
                    try {
                        return LocalDate.parse(text, format);
                    } catch (DateTimeParseException ignored) {
                        // try the next format
                    }
                }
            }
            problems.add(Problem.error(sheet, row.getRowNum() + 1, column, "Not a date: " + cell
                    + ". Use a date such as 2024-03-15."));
            return null;
        }

        private BigDecimal number(Cell cell) {
            CellType type = type(cell);
            if (type == CellType.NUMERIC) {
                return BigDecimal.valueOf(cell.getNumericCellValue());
            }
            if (type == CellType.STRING) {
                String text = cell.getStringCellValue().replaceAll("(?i)kes|ksh|%|,|\\s", "");
                try {
                    return text.isEmpty() ? null : new BigDecimal(text);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        }
    }

    static String normalize(String heading) {
        return heading == null ? "" : heading.replace("*", "").trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    // ---- writing the template -------------------------------------------------------------------

    @Override
    public byte[] template(Set<String> roleNames, Set<String> investmentTypes) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);
            readMe(workbook, styles, roleNames, investmentTypes);

            Sheet members = sheet(workbook, styles, MEMBERS, List.of(EMAIL + " *", FIRST_NAME + " *", LAST_NAME + " *",
                    PHONE + " *", JOINED_ON + " *", ROLES, SAVINGS, PENALTIES), new int[]{30, 16, 16, 16, 14, 24, 16, 20});
            members.setDefaultColumnStyle(3, styles.text);
            members.setDefaultColumnStyle(4, styles.date);
            members.setDefaultColumnStyle(6, styles.amount);
            members.setDefaultColumnStyle(7, styles.amount);

            Sheet loans = sheet(workbook, styles, LOANS, List.of(MEMBER_EMAIL + " *", PRINCIPAL + " *", INTEREST,
                    TERM + " *", DISBURSED_ON + " *", REPAID, PURPOSE), new int[]{30, 16, 16, 14, 14, 20, 30});
            loans.setDefaultColumnStyle(1, styles.amount);
            loans.setDefaultColumnStyle(2, styles.amount);
            loans.setDefaultColumnStyle(4, styles.date);
            loans.setDefaultColumnStyle(5, styles.amount);

            Sheet accounts = sheet(workbook, styles, ACCOUNTS, List.of(ACCOUNT + " *", BALANCE + " *"), new int[]{24, 18});
            accounts.setDefaultColumnStyle(1, styles.amount);
            String[] names = {"Cash on hand", "Bank account", "M-Pesa"};
            for (int i = 0; i < names.length; i++) {
                Row row = accounts.createRow(i + 1);
                row.createCell(0).setCellValue(names[i]);
                Cell balance = row.createCell(1);
                balance.setCellValue(0);
                balance.setCellStyle(styles.amount);
            }

            Sheet investments = sheet(workbook, styles, INVESTMENTS, List.of(NAME + " *", TYPE + " *", INSTITUTION,
                    INVESTED_ON + " *", AMOUNT_INVESTED + " *", CURRENT_VALUE, EXPECTED_RETURN),
                    new int[]{30, 22, 20, 14, 22, 20, 20});
            investments.setDefaultColumnStyle(3, styles.date);
            investments.setDefaultColumnStyle(4, styles.amount);
            investments.setDefaultColumnStyle(5, styles.amount);
            DataValidationHelper helper = investments.getDataValidationHelper();
            DataValidation types = helper.createValidation(
                    helper.createExplicitListConstraint(investmentTypes.stream().sorted().toArray(String[]::new)),
                    new CellRangeAddressList(1, MAX_ROWS, 1, 1));
            types.setShowErrorBox(true);
            investments.addValidationData(types);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the import template", e);
        }
    }

    private static Sheet sheet(Workbook workbook, Styles styles, String name, List<String> headings, int[] widths) {
        Sheet sheet = workbook.createSheet(name);
        Row header = sheet.createRow(0);
        for (int i = 0; i < headings.size(); i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headings.get(i));
            cell.setCellStyle(styles.header);
            sheet.setColumnWidth(i, widths[i] * 256);
        }
        sheet.createFreezePane(0, 1);
        return sheet;
    }

    private static void readMe(Workbook workbook, Styles styles, Set<String> roleNames, Set<String> investmentTypes) {
        Sheet sheet = workbook.createSheet("Read me");
        sheet.setColumnWidth(0, 110 * 256);
        String[] lines = {
                "Opening balances: bring your chama's records over from Excel",
                "",
                "Fill in the Members, Loans, Accounts and Investments sheets with the figures AS AT YOUR CUT-OVER DATE",
                "(for example the last day of the month before you start using the system). Then upload this file to",
                "preview it: every row is checked and nothing is saved. When the preview has no errors, import it.",
                "Opening balances are imported once; everything after the cut-over date is recorded in the system.",
                "",
                "Columns marked * are required. Leave rows you do not need empty. Do not rename the sheets or headings.",
                "Amounts are in KES (e.g. 5000 or 5,000.50). Dates like 2024-03-15.",
                "",
                "MEMBERS: one row per member, including officials already using the system (matched by email: their",
                "savings and penalties are brought in, nothing else changes). Every new member gets an email with a",
                "link to set their password. Savings = each member's total savings on the cut-over date.",
                "Penalties owed = fines and penalties they still owe. Roles (optional, comma-separated): "
                        + String.join(", ", roleNames.stream().sorted().toList()) + ".",
                "",
                "LOANS: loans still being repaid on the cut-over date (leave out loans that are fully repaid), one per",
                "member. Interest = the flat interest agreed for the whole term. Repaid so far = everything repaid by",
                "the cut-over date. The member keeps repaying monthly from the disbursement date.",
                "",
                "ACCOUNTS: the chama's money on the cut-over date: Cash on hand, Bank account and M-Pesa.",
                "",
                "INVESTMENTS: what the chama holds, e.g. a money market fund or an Airbnb unit. Amount invested = what",
                "was put in less anything taken out. Current value (optional) = what it is worth on the cut-over date.",
                "Types: " + String.join(", ", investmentTypes.stream().sorted().toList()) + ".",
                "",
                "The difference between what the chama holds (accounts, investments, loans and penalties owed to it)",
                "and the members' savings is brought forward as the chama's retained surplus.",
        };
        for (int i = 0; i < lines.length; i++) {
            Cell cell = sheet.createRow(i).createCell(0);
            cell.setCellValue(lines[i]);
            if (i == 0) {
                cell.setCellStyle(styles.title);
            }
        }
    }

    private static final class Styles {
        final CellStyle header;
        final CellStyle title;
        final CellStyle date;
        final CellStyle amount;
        final CellStyle text;

        Styles(Workbook workbook) {
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
            date = workbook.createCellStyle();
            date.setDataFormat(format.getFormat("yyyy-mm-dd"));
            amount = workbook.createCellStyle();
            amount.setDataFormat(format.getFormat("#,##0.00"));
            text = workbook.createCellStyle();
            text.setDataFormat(format.getFormat("@"));
        }
    }
}
