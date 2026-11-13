package ke.chama.modules.dataimport.domain.model.history;

import ke.chama.modules.dataimport.domain.model.Problem;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The generic shape of a history workbook: which sheets and columns a template has, and the rows
 * read back from a filled-in file. Plain Java, so every check can be tested without Excel.
 */
public final class Sheets {

    private Sheets() {
    }

    public enum ColumnType {
        TEXT,
        /** In the chama's currency, e.g. 5000 or 5,000.50; read as minor units. */
        AMOUNT,
        DATE,
        INTEGER,
        /** 12.5, "12.5%" or a cell formatted as a percentage; read as basis points (1250). */
        PERCENT
    }

    /**
     * @param choices    the values offered in a drop-down (any other value is still read, and checked)
     * @param width      in characters
     * @param note       shown under the headings row of the Read me sheet's column list
     */
    public record Column(String heading, ColumnType type, boolean required, int width, List<String> choices,
                         String note) {

        public Column {
            Objects.requireNonNull(heading, "heading");
            Objects.requireNonNull(type, "type");
            choices = choices == null ? List.of() : List.copyOf(choices);
        }

        public static Column required(String heading, ColumnType type, int width, String note) {
            return new Column(heading, type, true, width, List.of(), note);
        }

        public static Column optional(String heading, ColumnType type, int width, String note) {
            return new Column(heading, type, false, width, List.of(), note);
        }

        public Column choosing(List<String> values) {
            return new Column(heading, type, required, width, values, note);
        }
    }

    /**
     * @param required   the sheet must be in the file (optional sheets may be deleted or left empty)
     * @param reference  a sheet only to look things up in (e.g. the member list): not read back
     */
    public record SheetSpec(String name, List<Column> columns, boolean required, boolean reference) {

        public SheetSpec {
            columns = List.copyOf(columns);
        }

        public static SheetSpec data(String name, boolean required, Column... columns) {
            return new SheetSpec(name, List.of(columns), required, false);
        }

        public static SheetSpec lookup(String name, Column... columns) {
            return new SheetSpec(name, List.of(columns), false, true);
        }
    }

    /** One template sheet, possibly with rows filled in (e.g. the chama's members). */
    public record TemplateSheet(SheetSpec spec, List<List<Object>> rows) {

        public TemplateSheet {
            rows = rows == null ? List.of() : rows.stream().map(r -> (List<Object>) new ArrayList<>(r)).toList();
        }
    }

    /** @param readMe the instructions, one paragraph per line; the first is the title */
    public record Template(List<String> readMe, List<TemplateSheet> sheets) {

        public Template {
            readMe = List.copyOf(readMe);
            sheets = List.copyOf(sheets);
        }
    }

    /**
     * One row read from a sheet: values by heading, already typed (String, Long minor units or basis
     * points or whole numbers, LocalDate). A cell that could not be read is absent and was reported.
     *
     * @param row        as Excel numbers it (the headings are row 1)
     * @param unreadable headings of cells that were filled in but could not be read (already reported)
     */
    public record Row(String sheet, int row, Map<String, Object> values, java.util.Set<String> unreadable) {

        public Row {
            values = values == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
            unreadable = unreadable == null ? java.util.Set.of() : java.util.Set.copyOf(unreadable);
        }

        public Row(String sheet, int row, Map<String, Object> values) {
            this(sheet, row, values, java.util.Set.of());
        }

        /** Filled in but not readable: a problem was already reported for it. */
        public boolean unreadable(String heading) {
            return unreadable.contains(heading);
        }

        /** Basis points read from a PERCENT column. */
        public Integer basisPoints(String heading) {
            return values.get(heading) instanceof Long l ? Math.toIntExact(l) : null;
        }

        public String text(String heading) {
            Object v = values.get(heading);
            return v == null ? null : v.toString();
        }

        public Long amount(String heading) {
            return values.get(heading) instanceof Long l ? l : null;
        }

        public Integer integer(String heading) {
            return values.get(heading) instanceof Long l ? Math.toIntExact(l) : null;
        }

        public LocalDate date(String heading) {
            return values.get(heading) instanceof LocalDate d ? d : null;
        }

    }

    /** Everything read from a file: rows by sheet name, and the cells that could not be read. */
    public record Workbook(Map<String, List<Row>> sheets, List<Problem> problems) {

        public Workbook {
            sheets = Map.copyOf(sheets);
            problems = List.copyOf(problems);
        }

        public List<Row> rows(String sheet) {
            return sheets.getOrDefault(sheet, List.of());
        }
    }
}
