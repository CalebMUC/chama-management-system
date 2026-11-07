package ke.chama.modules.dataimport.infrastructure.spreadsheet;

import ke.chama.modules.dataimport.Workbooks;
import ke.chama.modules.dataimport.domain.model.OpeningBalanceSheets;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PoiSpreadsheetFormatTest {

    private final PoiSpreadsheetFormat format = new PoiSpreadsheetFormat();

    @Test
    void theTemplateFilledInLikeExcelReadsBack() {
        byte[] template = format.template(Set.of("MEMBER", "TREASURER"), Set.of("MONEY_MARKET_FUND", "BUSINESS"));
        byte[] filled = Workbooks.from(template)
                .row("Members", "alice@mokica.test", "Alice", "Wanjiku", 712345678, LocalDate.of(2023, 3, 15),
                        "Treasurer, Member", 20000.5, null)
                .row("Members", "bob@mokica.test", "Bob", "Otieno", "0722 000 111", "15/04/2024", null, "15,000", 500)
                .row("Loans", "alice@mokica.test", 30000, 3000, 12, LocalDate.of(2026, 5, 1), 11000, "School fees")
                .setRow("Accounts", 4, "M-Pesa", 3500)
                .row("Investments", "CIC money market fund", "MONEY_MARKET_FUND", "CIC", LocalDate.of(2025, 1, 10),
                        25000, 26500, "11%")
                .bytes();

        OpeningBalanceSheets sheets = format.read(filled, 2);

        assertThat(sheets.problems()).isEmpty();
        assertThat(sheets.members()).hasSize(2);
        assertThat(sheets.members().get(0).phone()).as("Excel dropped the leading zero").isEqualTo("0712345678");
        assertThat(sheets.members().get(0).joinedOn()).isEqualTo(LocalDate.of(2023, 3, 15));
        assertThat(sheets.members().get(0).roles()).containsExactly("Treasurer", "Member");
        assertThat(sheets.members().get(0).savingsMinor()).isEqualTo(2_000_050);
        assertThat(sheets.members().get(1).joinedOn()).isEqualTo(LocalDate.of(2024, 4, 15));
        assertThat(sheets.members().get(1).savingsMinor()).isEqualTo(1_500_000);
        assertThat(sheets.members().get(1).penaltiesMinor()).isEqualTo(50_000);
        assertThat(sheets.loans().getFirst().termMonths()).isEqualTo(12);
        assertThat(sheets.accounts()).hasSize(3);
        assertThat(sheets.accounts().get(2).balanceMinor()).isEqualTo(350_000);
        assertThat(sheets.investments().getFirst().expectedReturnBasisPoints()).isEqualTo(1_100);
        assertThat(sheets.investments().getFirst().row()).isEqualTo(2);
    }

    @Test
    void unreadableCellsAreReportedNotGuessed() {
        byte[] filled = Workbooks.from(format.template(Set.of("MEMBER"), Set.of("LAND")))
                .row("Members", "alice@mokica.test", "Alice", "W", "0712345678", "last March", null, "lots")
                .bytes();

        OpeningBalanceSheets sheets = format.read(filled, 2);

        assertThat(sheets.problems()).extracting(p -> p.row() + ":" + p.column())
                .containsExactlyInAnyOrder("2:Joined on", "2:Savings (KES)");
    }

    @Test
    void aFileThatIsNotAWorkbookIsRefused() {
        assertThatThrownBy(() -> format.read("name,savings\nalice,100".getBytes(), 2))
                .extracting("code").isEqualTo("IMPORT_INVALID_FILE");
        assertThatThrownBy(() -> format.read(new byte[0], 2)).extracting("code").isEqualTo("IMPORT_INVALID_FILE");
    }
}
