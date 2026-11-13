package ke.chama.modules.dataimport.application.service.history;

import ke.chama.modules.dataimport.domain.model.history.HistoryChama;
import ke.chama.modules.dataimport.domain.model.history.HistoryChama.MemberRef;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Column;
import ke.chama.modules.dataimport.domain.model.history.Sheets.SheetSpec;
import ke.chama.modules.dataimport.domain.model.history.Sheets.TemplateSheet;

import java.util.ArrayList;
import java.util.List;

import static ke.chama.modules.dataimport.domain.model.history.Sheets.ColumnType.DATE;
import static ke.chama.modules.dataimport.domain.model.history.Sheets.ColumnType.TEXT;

/** Pieces every historical-import template shares. */
final class HistoryTemplates {

    static final String MEMBERS_LOOKUP = "Members";

    private HistoryTemplates() {
    }

    /** The chama's members, to look up numbers in; not read back. */
    static TemplateSheet membersLookup(HistoryChama chama) {
        SheetSpec spec = SheetSpec.lookup(MEMBERS_LOOKUP,
                Column.optional("Member", TEXT, 28, "How to name the member in the other sheets"),
                Column.optional("Name", TEXT, 28, null),
                Column.optional("Email", TEXT, 30, null),
                Column.optional("Joined on", DATE, 14, null));
        List<List<Object>> rows = new ArrayList<>();
        for (MemberRef m : chama.members()) {
            List<Object> row = new ArrayList<>();
            row.add(m.label());
            row.add(m.name());
            row.add(m.email());
            row.add(m.joinedOn());
            rows.add(row);
        }
        return new TemplateSheet(spec, rows);
    }

    /** The Read me text: a title, then the lines, then what every template says. */
    static List<String> readMe(String title, String... lines) {
        List<String> all = new ArrayList<>();
        all.add(title);
        all.add("");
        all.addAll(List.of(lines));
        all.add("");
        all.add("How it works: fill in the sheet(s), save the file as .xlsx and upload it to preview. Every row is checked "
                + "and nothing is saved; each problem is listed with its sheet, row and column. When the preview has no "
                + "errors, import it: the whole file goes in at once, or nothing does. The latest import can be undone.");
        all.add("Columns marked * are required. Do not rename the sheets or headings; you may reorder columns. "
                + "Amounts are in the chama's currency (5000 or 5,000.50). Dates like 2024-03-15 (15/03/2024 also works). "
                + "Members: pick from the list or type the member number (see the Members sheet).");
        all.add("Nothing is emailed to members while history is imported.");
        return all;
    }
}
