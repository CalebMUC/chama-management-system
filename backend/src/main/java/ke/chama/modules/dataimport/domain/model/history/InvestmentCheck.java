package ke.chama.modules.dataimport.domain.model.history;

import ke.chama.modules.dataimport.domain.model.Problem;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Column;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Row;
import ke.chama.modules.dataimport.domain.model.history.Sheets.SheetSpec;
import ke.chama.modules.dataimport.domain.model.history.Sheets.Workbook;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static ke.chama.modules.dataimport.domain.model.history.Sheets.ColumnType.DATE;
import static ke.chama.modules.dataimport.domain.model.history.Sheets.ColumnType.PERCENT;
import static ke.chama.modules.dataimport.domain.model.history.Sheets.ColumnType.TEXT;

/**
 * Step 5: what the chama invested in over the years, and everything that happened to each
 * investment since: top-ups, income, withdrawals, valuations and, if it ended, its closure.
 */
public final class InvestmentCheck {

    public static final String INVESTMENTS = "Investments";
    public static final String MOVEMENTS = "Investment activity";

    public static final String REF = "Investment ref", NAME = "Name", TYPE = "Type", INSTITUTION = "Institution",
            FUNDED_ON = "Invested on", AMOUNT_INVESTED = "Amount invested", METHOD = "Method", REFERENCE = "Reference",
            EXPECTED_RETURN = "Expected return (%)", MATURITY = "Maturity date", DESCRIPTION = "Description";
    public static final String DATE_OF = "Date", KIND = "What happened", AMOUNT = "Amount", NOTE = "Note";

    public static final List<String> KINDS = List.of("Deposit", "Income", "Withdrawal", "Valuation", "Closure");
    public static final List<String> METHODS = List.of("Cash", "Bank", "M-Pesa", "Reinvested");
    static final List<String> ORDER = List.of("DEPOSIT", "INCOME", "WITHDRAWAL", "VALUATION", "CLOSURE");

    private InvestmentCheck() {
    }

    public static List<SheetSpec> sheets(List<String> typeLabels) {
        return List.of(
                SheetSpec.data(INVESTMENTS, true,
                        Column.required(REF, TEXT, 14, "Any short name you choose, unique per investment, e.g. I001"),
                        Column.required(NAME, TEXT, 30, "e.g. CIC money market fund"),
                        Column.required(TYPE, TEXT, 22, "What kind of investment").choosing(typeLabels),
                        Column.optional(INSTITUTION, TEXT, 20, "e.g. CIC, KCB, the land seller"),
                        Column.required(FUNDED_ON, DATE, 14, "The day the money first went in"),
                        Column.required(AMOUNT_INVESTED, Sheets.ColumnType.AMOUNT, 18, "What first went in (top-ups go in the activity sheet)"),
                        Column.optional(METHOD, TEXT, 12, "How it was paid: Cash, Bank or M-Pesa (Cash if blank)")
                                .choosing(HistoryChama.METHODS),
                        Column.optional(REFERENCE, TEXT, 18, "M-Pesa code or bank slip number, if you have it"),
                        Column.optional(EXPECTED_RETURN, PERCENT, 18, "Expected yearly return, e.g. 11.5"),
                        Column.optional(MATURITY, DATE, 14, "For fixed-term investments"),
                        Column.optional(DESCRIPTION, TEXT, 30, null)),
                SheetSpec.data(MOVEMENTS, false,
                        Column.required(REF, TEXT, 14, "The investment, as in the Investments sheet"),
                        Column.required(DATE_OF, DATE, 14, "The day it happened"),
                        Column.required(KIND, TEXT, 16, "Deposit (a top-up), Income (interest, dividends, rent), "
                                + "Withdrawal (part taken out), Valuation (what it was worth), Closure (sold or redeemed: "
                                + "the amount received)").choosing(KINDS),
                        Column.required(AMOUNT, Sheets.ColumnType.AMOUNT, 16, "The amount (for a valuation, what it was worth)"),
                        Column.optional(METHOD, TEXT, 12, "Cash, Bank or M-Pesa; Reinvested for income that stayed in "
                                + "the investment. Not needed for a valuation.").choosing(METHODS),
                        Column.optional(REFERENCE, TEXT, 18, "M-Pesa code or bank slip number, if you have it"),
                        Column.optional(NOTE, TEXT, 30, null)));
    }

    /** @param kind DEPOSIT, INCOME, WITHDRAWAL, VALUATION or CLOSURE */
    public record Movement(int row, LocalDate date, String kind, long amountMinor, String method, String reference,
                           String note) {
    }

    /** Totals after replaying the movements. */
    public record Outcome(long putInMinor, long incomeMinor, long takenOutMinor, long bookValueMinor,
                          Long latestValueMinor, boolean closed, long realisedGainMinor) {
    }

    public record PlannedInvestment(int row, String ref, String name, String type, String institution,
                                    LocalDate fundedOn, long amountMinor, String method, String reference,
                                    Integer expectedReturnBasisPoints, LocalDate maturityDate, String description,
                                    List<Movement> movements, Outcome outcome) {
    }

    public record Result(List<PlannedInvestment> investments, int movementRows, List<Problem> problems) {
    }

    /** @param types the investment type names, e.g. MONEY_MARKET_FUND */
    public static Result check(Workbook workbook, HistoryChama chama, Set<String> types) {
        List<Problem> problems = new ArrayList<>(workbook.problems());
        Set<String> references = new HashSet<>(chama.usedReferences());
        int[] withoutReference = {0};
        Map<String, PlannedInvestment> byRef = new LinkedHashMap<>();
        Set<String> refsSeen = new HashSet<>();
        for (Row r : workbook.rows(INVESTMENTS)) {
            int before = problems.size();
            String ref = key(r.text(REF));
            if (ref == null) {
                problems.add(Problem.error(INVESTMENTS, r.row(), REF, "A ref is required, e.g. I001"));
            } else if (!refsSeen.add(ref)) {
                problems.add(Problem.error(INVESTMENTS, r.row(), REF, r.text(REF) + " appears more than once"));
            }
            String name = r.text(NAME);
            if (name == null || name.length() > 120) {
                problems.add(Problem.error(INVESTMENTS, r.row(), NAME, "A name of at most 120 characters is required"));
            }
            String type = type(r.text(TYPE));
            if (type == null || !types.contains(type)) {
                problems.add(Problem.error(INVESTMENTS, r.row(), TYPE, (r.text(TYPE) == null ? "The type is required"
                        : "Unknown type " + r.text(TYPE)) + "; pick one from the list"));
            }
            LocalDate funded = r.date(FUNDED_ON);
            if (funded == null && !r.unreadable(FUNDED_ON)) {
                problems.add(Problem.error(INVESTMENTS, r.row(), FUNDED_ON, "The date is required"));
            } else if (funded != null && funded.isAfter(chama.today())) {
                problems.add(Problem.error(INVESTMENTS, r.row(), FUNDED_ON, "Cannot be in the future"));
            }
            Long amount = r.amount(AMOUNT_INVESTED);
            if ((amount == null && !r.unreadable(AMOUNT_INVESTED)) || (amount != null && amount <= 0)) {
                problems.add(Problem.error(INVESTMENTS, r.row(), AMOUNT_INVESTED, "Must be greater than zero"));
            }
            String method = "CASH";
            if (r.text(METHOD) != null) {
                method = HistoryChama.method(r.text(METHOD)).orElse(null);
                if (method == null) {
                    problems.add(Problem.error(INVESTMENTS, r.row(), METHOD, "Unknown method " + r.text(METHOD)
                            + "; use Cash, Bank or M-Pesa"));
                }
            }
            Integer expected = r.basisPoints(EXPECTED_RETURN);
            if (expected != null && (expected < 0 || expected > 100_000)) {
                problems.add(Problem.error(INVESTMENTS, r.row(), EXPECTED_RETURN, "Must be between 0 and 1000"));
            }
            String description = r.text(DESCRIPTION);
            if (description != null && description.length() > 300) {
                problems.add(Problem.error(INVESTMENTS, r.row(), DESCRIPTION, "At most 300 characters"));
            }
            String institution = r.text(INSTITUTION);
            if (institution != null && institution.length() > 120) {
                problems.add(Problem.error(INVESTMENTS, r.row(), INSTITUTION, "At most 120 characters"));
            }
            String reference = reference(r, INVESTMENTS, method, references, withoutReference, problems);
            if (problems.size() != before || funded == null || amount == null) {
                continue;
            }
            byRef.put(ref, new PlannedInvestment(r.row(), r.text(REF).trim(), name.trim(), type, institution, funded,
                    amount, method, reference, expected, r.date(MATURITY), description, List.of(), null));
        }

        Map<String, List<Movement>> movements = new HashMap<>();
        for (Row r : workbook.rows(MOVEMENTS)) {
            int before = problems.size();
            String ref = key(r.text(REF));
            PlannedInvestment investment = ref == null ? null : byRef.get(ref);
            if (ref == null) {
                problems.add(Problem.error(MOVEMENTS, r.row(), REF, "Say which investment this is for"));
                continue;
            } else if (investment == null) {
                if (!refsSeen.contains(ref)) {
                    problems.add(Problem.error(MOVEMENTS, r.row(), REF, "No investment " + r.text(REF)
                            + " in the Investments sheet"));
                }
                continue;
            }
            LocalDate date = r.date(DATE_OF);
            if (date == null && !r.unreadable(DATE_OF)) {
                problems.add(Problem.error(MOVEMENTS, r.row(), DATE_OF, "The date is required"));
            } else if (date != null && date.isAfter(chama.today())) {
                problems.add(Problem.error(MOVEMENTS, r.row(), DATE_OF, "Cannot be in the future"));
            } else if (date != null && date.isBefore(investment.fundedOn())) {
                problems.add(Problem.error(MOVEMENTS, r.row(), DATE_OF, "Before " + investment.name()
                        + " was invested in (" + investment.fundedOn() + ")"));
            }
            String kind = kind(r.text(KIND));
            if (kind == null) {
                problems.add(Problem.error(MOVEMENTS, r.row(), KIND, (r.text(KIND) == null ? "Say what happened"
                        : "Unknown " + r.text(KIND)) + ": use Deposit, Income, Withdrawal, Valuation or Closure"));
            }
            Long amount = r.amount(AMOUNT);
            boolean zeroAllowed = "VALUATION".equals(kind) || "CLOSURE".equals(kind);
            if ((amount == null && !r.unreadable(AMOUNT)) || (amount != null && (amount < 0 || (amount == 0 && !zeroAllowed)))) {
                problems.add(Problem.error(MOVEMENTS, r.row(), AMOUNT, zeroAllowed ? "Cannot be negative"
                        : "Must be greater than zero"));
            }
            String method = null;
            if (kind != null && !kind.equals("VALUATION")) {
                String text = r.text(METHOD);
                method = text == null ? null : text.trim().equalsIgnoreCase("reinvested") ? "REINVESTED"
                        : HistoryChama.method(text).orElse(null);
                if (method == null) {
                    problems.add(Problem.error(MOVEMENTS, r.row(), METHOD, (text == null ? "The method is required"
                            : "Unknown method " + text) + "; use Cash, Bank or M-Pesa" + (kind.equals("INCOME")
                            ? ", or Reinvested" : "")));
                } else if (method.equals("REINVESTED") && !kind.equals("INCOME")) {
                    problems.add(Problem.error(MOVEMENTS, r.row(), METHOD, "Only income can be reinvested"));
                }
            }
            String note = r.text(NOTE);
            if (note != null && note.length() > 300) {
                problems.add(Problem.error(MOVEMENTS, r.row(), NOTE, "At most 300 characters"));
            }
            String reference = kind == null || kind.equals("VALUATION") || "REINVESTED".equals(method) ? null
                    : reference(r, MOVEMENTS, method, references, withoutReference, problems);
            if (problems.size() != before || date == null || amount == null) {
                continue;
            }
            movements.computeIfAbsent(ref, k -> new ArrayList<>())
                    .add(new Movement(r.row(), date, kind, amount, method, reference, note));
        }

        List<PlannedInvestment> investments = new ArrayList<>();
        for (Map.Entry<String, PlannedInvestment> e : byRef.entrySet()) {
            PlannedInvestment i = e.getValue();
            List<Movement> list = movements.getOrDefault(e.getKey(), List.of()).stream()
                    .sorted(Comparator.comparing(Movement::date).thenComparingInt(m -> ORDER.indexOf(m.kind()))
                            .thenComparingInt(Movement::row))
                    .toList();
            Outcome outcome = replay(i, list, chama, problems);
            investments.add(new PlannedInvestment(i.row(), i.ref(), i.name(), i.type(), i.institution(), i.fundedOn(),
                    i.amountMinor(), i.method(), i.reference(), i.expectedReturnBasisPoints(), i.maturityDate(),
                    i.description(), list, outcome));
        }
        if (withoutReference[0] > 0) {
            problems.add(Problem.warning(null, null, REFERENCE, withoutReference[0] + " bank or M-Pesa transaction"
                    + (withoutReference[0] == 1 ? " has" : "s have") + " no reference. Each gets one made up from the "
                    + "import so it can still be told apart."));
        }
        if (workbook.rows(INVESTMENTS).isEmpty()) {
            problems.add(Problem.error(INVESTMENTS, null, null, "The file has no investments to import"));
        }
        return new Result(investments, workbook.rows(MOVEMENTS).size(), problems);
    }

    /** Follows the book value as the system will, reporting what it would refuse. */
    static Outcome replay(PlannedInvestment i, List<Movement> movements, HistoryChama chama, List<Problem> problems) {
        long book = i.amountMinor();
        long putIn = i.amountMinor();
        long income = 0;
        long out = 0;
        Long value = null;
        boolean closed = false;
        long gain = 0;
        for (Movement m : movements) {
            if (closed) {
                problems.add(Problem.error(MOVEMENTS, m.row(), DATE_OF, i.name() + " was closed before this"));
                continue;
            }
            switch (m.kind()) {
                case "DEPOSIT" -> {
                    book += m.amountMinor();
                    putIn += m.amountMinor();
                    value = value == null ? null : value + m.amountMinor();
                }
                case "INCOME" -> {
                    income += m.amountMinor();
                    if ("REINVESTED".equals(m.method())) {
                        book += m.amountMinor();
                        value = value == null ? null : value + m.amountMinor();
                    }
                }
                case "WITHDRAWAL" -> {
                    if (m.amountMinor() >= book) {
                        problems.add(Problem.error(MOVEMENTS, m.row(), AMOUNT, "A withdrawal must be less than the "
                                + "investment's book value then (" + chama.format(book) + "); if everything was taken "
                                + "out, record a Closure instead"));
                    } else {
                        book -= m.amountMinor();
                        out += m.amountMinor();
                        value = value == null ? null : Math.max(value - m.amountMinor(), 0);
                    }
                }
                case "VALUATION" -> value = m.amountMinor();
                case "CLOSURE" -> {
                    gain = m.amountMinor() - book;
                    out += m.amountMinor();
                    book = 0;
                    closed = true;
                }
                default -> throw new IllegalStateException(m.kind());
            }
        }
        return new Outcome(putIn, income, out, book, value, closed, gain);
    }

    private static String reference(Row r, String sheet, String method, Set<String> used, int[] withoutReference,
                                    List<Problem> problems) {
        String reference = r.text(REFERENCE) == null ? null : r.text(REFERENCE).trim().toUpperCase(Locale.ROOT);
        if (reference == null) {
            if (method != null && !method.equals("CASH")) {
                withoutReference[0]++;
            }
            return null;
        }
        if (reference.length() > 100) {
            problems.add(Problem.error(sheet, r.row(), REFERENCE, "At most 100 characters"));
        } else if (method != null && !used.add(method + ":" + reference)) {
            problems.add(Problem.error(sheet, r.row(), REFERENCE, reference
                    + " is already used (in this file or in the system)"));
        }
        return reference;
    }

    /** e.g. "Money market fund" or MONEY_MARKET_FUND to MONEY_MARKET_FUND. */
    static String type(String text) {
        return text == null ? null : text.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
    }

    /** How a type is offered in the template, e.g. "Money market fund". */
    public static String typeLabel(String type) {
        String words = type.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    static String kind(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return switch (t) {
            case "deposit", "topup", "contribution" -> "DEPOSIT";
            case "income", "interest", "dividend", "dividends", "rent" -> "INCOME";
            case "withdrawal", "withdraw", "partialwithdrawal" -> "WITHDRAWAL";
            case "valuation", "value", "revaluation" -> "VALUATION";
            case "closure", "close", "closed", "sale", "sold", "redemption", "redeemed", "matured" -> "CLOSURE";
            default -> null;
        };
    }

    private static String key(String ref) {
        return ref == null || ref.isBlank() ? null : ref.trim().toUpperCase(Locale.ROOT);
    }

    public static String madeUpReference(String batchId, String sheet, int row) {
        return "IMP-" + batchId.replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT) + "-"
                + (sheet.equals(INVESTMENTS) ? "I" : "A") + row;
    }
}
