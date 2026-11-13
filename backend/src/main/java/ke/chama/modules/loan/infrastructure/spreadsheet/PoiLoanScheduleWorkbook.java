package ke.chama.modules.loan.infrastructure.spreadsheet;

import ke.chama.modules.loan.application.port.out.LoanScheduleWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;

/** The loan schedules workbook, with Apache POI. */
@Component
class PoiLoanScheduleWorkbook implements LoanScheduleWorkbook {

    @Override
    public byte[] write(String chamaName, String currency, LocalDate asOf, List<LoanLine> loans) {
        int digits = Math.max(Currency.getInstance(currency).getDefaultFractionDigits(), 0);
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);

            Sheet summary = workbook.createSheet("Summary");
            Row title = summary.createRow(0);
            cell(title, 0, chamaName + " - loans being repaid, as at " + asOf, styles.title);
            String c = " (" + currency + ")";
            String[] headings = {"Member no.", "Member", "Loan product", "Purpose", "Paid out", "Term (months)",
                    "Principal" + c, "Interest" + c, "Total" + c, "Repaid" + c, "Outstanding" + c, "Overdue" + c,
                    "Instalments overdue", "Days in arrears", "Next instalment due", "Next instalment" + c,
                    "Last instalment due"};
            header(summary, 2, headings, styles);
            int r = 3;
            long[] totals = new long[6];
            for (LoanLine l : loans) {
                Row row = summary.createRow(r++);
                int i = 0;
                if (l.memberNumber() != null) {
                    row.createCell(i).setCellValue(l.memberNumber());
                }
                i++;
                cell(row, i++, l.memberName(), null);
                cell(row, i++, l.product(), null);
                cell(row, i++, l.purpose(), null);
                date(row, i++, l.disbursedOn(), styles);
                row.createCell(i++).setCellValue(l.termMonths());
                long[] amounts = {l.principalMinor(), l.interestMinor(), l.totalMinor(), l.repaidMinor(),
                        l.outstandingMinor(), l.arrearsMinor()};
                for (int a = 0; a < amounts.length; a++) {
                    amount(row, i++, amounts[a], digits, styles);
                    totals[a] += amounts[a];
                }
                row.createCell(i++).setCellValue(l.instalmentsOverdue());
                row.createCell(i++).setCellValue(l.daysInArrears());
                date(row, i++, l.nextDueDate(), styles);
                amount(row, i++, l.nextDueMinor(), digits, styles);
                date(row, i, l.maturityDate(), styles);
            }
            Row total = summary.createRow(r + 1);
            cell(total, 1, "Total: " + loans.size() + " loan" + (loans.size() == 1 ? "" : "s"), styles.bold);
            for (int a = 0; a < totals.length; a++) {
                amount(total, 6 + a, totals[a], digits, styles);
            }
            int[] widths = {11, 26, 18, 24, 12, 9, 14, 13, 14, 14, 15, 14, 11, 10, 14, 15, 14};
            for (int i = 0; i < widths.length; i++) {
                summary.setColumnWidth(i, widths[i] * 256);
            }
            summary.createFreezePane(2, 3);

            Sheet schedules = workbook.createSheet("Schedules");
            header(schedules, 0, new String[]{"Member no.", "Member", "Loan", "Instalment", "Due on", "Amount" + c,
                    "Paid" + c, "Still to pay" + c, "Status"}, styles);
            r = 1;
            for (LoanLine l : loans) {
                String loan = l.product() + ", paid out " + l.disbursedOn();
                for (InstalmentLine in : l.schedule()) {
                    Row row = schedules.createRow(r++);
                    if (l.memberNumber() != null) {
                        row.createCell(0).setCellValue(l.memberNumber());
                    }
                    cell(row, 1, l.memberName(), null);
                    cell(row, 2, loan, null);
                    row.createCell(3).setCellValue(in.number() + " of " + l.termMonths());
                    date(row, 4, in.dueDate(), styles);
                    amount(row, 5, in.amountMinor(), digits, styles);
                    amount(row, 6, in.paidMinor(), digits, styles);
                    amount(row, 7, in.amountMinor() - in.paidMinor(), digits, styles);
                    cell(row, 8, status(in.status()), "OVERDUE".equals(in.status()) ? styles.overdue : null);
                }
            }
            int[] scheduleWidths = {11, 26, 34, 11, 12, 14, 14, 15, 12};
            for (int i = 0; i < scheduleWidths.length; i++) {
                schedules.setColumnWidth(i, scheduleWidths[i] * 256);
            }
            schedules.createFreezePane(0, 1);
            if (schedules.getLastRowNum() > 0) {
                schedules.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, schedules.getLastRowNum(), 0, 8));
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the loan schedules", e);
        }
    }

    private static String status(String status) {
        return switch (status) {
            case "PAID" -> "Paid";
            case "PARTLY_PAID" -> "Part paid";
            case "OVERDUE" -> "Overdue";
            default -> "Due";
        };
    }

    private static void header(Sheet sheet, int rowIndex, String[] headings, Styles styles) {
        Row row = sheet.createRow(rowIndex);
        for (int i = 0; i < headings.length; i++) {
            cell(row, i, headings[i], styles.header);
        }
    }

    private static void cell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        if (style != null) {
            cell.setCellStyle(style);
        }
    }

    private static void date(Row row, int column, LocalDate value, Styles styles) {
        if (value != null) {
            Cell cell = row.createCell(column);
            cell.setCellValue(value);
            cell.setCellStyle(styles.date);
        }
    }

    private static void amount(Row row, int column, long minor, int digits, Styles styles) {
        Cell cell = row.createCell(column);
        cell.setCellValue(BigDecimal.valueOf(minor, digits).doubleValue());
        cell.setCellStyle(styles.amount);
    }

    private static final class Styles {
        final CellStyle header;
        final CellStyle title;
        final CellStyle bold;
        final CellStyle date;
        final CellStyle amount;
        final CellStyle overdue;

        Styles(XSSFWorkbook workbook) {
            DataFormat format = workbook.createDataFormat();
            Font boldFont = workbook.createFont();
            boldFont.setBold(true);
            header = workbook.createCellStyle();
            header.setFont(boldFont);
            header.setWrapText(true);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            bold = workbook.createCellStyle();
            bold.setFont(boldFont);
            Font big = workbook.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            title = workbook.createCellStyle();
            title.setFont(big);
            date = workbook.createCellStyle();
            date.setDataFormat(format.getFormat("yyyy-mm-dd"));
            amount = workbook.createCellStyle();
            amount.setDataFormat(format.getFormat("#,##0.00"));
            Font red = workbook.createFont();
            red.setColor(IndexedColors.RED.getIndex());
            red.setBold(true);
            overdue = workbook.createCellStyle();
            overdue.setFont(red);
        }
    }
}
