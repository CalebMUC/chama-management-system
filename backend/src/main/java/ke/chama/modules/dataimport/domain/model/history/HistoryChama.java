package ke.chama.modules.dataimport.domain.model.history;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The chama as it is now, as every history check sees it, with the lookups the sheets need:
 * members by number ("12", "12 - Jane Doe") or email, savings products by code, payment methods.
 *
 * @param usedReferences references already in the system, as METHOD:REFERENCE upper case
 */
public record HistoryChama(LocalDate today,
                           String currency,
                           List<MemberRef> members,
                           List<ProductRef> savingsProducts,
                           Set<String> usedReferences) {

    /**
     * A seated member.
     *
     * @param openProductIds savings products the member has an open account in
     */
    public record MemberRef(UUID userId, int number, String name, String email, LocalDate joinedOn,
                            Set<UUID> openProductIds) {

        /** How the member is offered in drop-downs, e.g. "12 - Jane Doe". */
        public String label() {
            return number + " - " + name;
        }
    }

    public record ProductRef(UUID id, String code, String name, boolean defaultProduct, boolean active) {

        public String label() {
            return code + " - " + name;
        }
    }

    public HistoryChama {
        members = List.copyOf(members);
        savingsProducts = List.copyOf(savingsProducts);
        usedReferences = Set.copyOf(usedReferences);
    }

    /** The same chama with the references already used in the module being imported. */
    public HistoryChama withReferences(Set<String> references) {
        return new HistoryChama(today, currency, members, savingsProducts, references);
    }

    private static final Pattern LEADING_NUMBER = Pattern.compile("^#?\\s*(\\d{1,6})\\b.*");

    /** A member from "12", "#12", "12 - Jane Doe" or their email. */
    public Optional<MemberRef> member(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String t = text.trim();
        if (t.contains("@")) {
            String email = t.toLowerCase(Locale.ROOT);
            return members.stream().filter(m -> m.email() != null && m.email().equalsIgnoreCase(email)).findFirst();
        }
        Matcher m = LEADING_NUMBER.matcher(t);
        if (!m.matches()) {
            return Optional.empty();
        }
        int number = Integer.parseInt(m.group(1));
        return members.stream().filter(r -> r.number() == number).findFirst();
    }

    /** A savings product from its code or "CODE - Name" (case ignored). */
    public Optional<ProductRef> savingsProduct(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String code = text.trim().split("\\s+-\\s+|\\s+", 2)[0].toUpperCase(Locale.ROOT);
        return savingsProducts.stream().filter(p -> p.code().equalsIgnoreCase(code)).findFirst()
                .or(() -> savingsProducts.stream().filter(p -> p.name().equalsIgnoreCase(text.trim())).findFirst());
    }

    public ProductRef defaultProduct() {
        return savingsProducts.stream().filter(ProductRef::defaultProduct).findFirst().orElseThrow();
    }

    public List<String> memberLabels() {
        return members.stream().map(MemberRef::label).toList();
    }

    public Map<UUID, MemberRef> membersById() {
        Map<UUID, MemberRef> byId = new HashMap<>();
        members.forEach(m -> byId.put(m.userId(), m));
        return byId;
    }

    // ---- payment methods ----------------------------------------------------------------------

    /** The choices offered in the template. */
    public static final List<String> METHODS = List.of("Cash", "Bank", "M-Pesa");

    /** CASH, BANK or MPESA from "Cash", "Bank", "M-Pesa", "Mobile money", ... */
    public static Optional<String> method(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return Optional.ofNullable(switch (t) {
            case "cash", "cashonhand" -> "CASH";
            case "bank", "bankaccount", "banktransfer", "cheque", "check", "eft", "rtgs" -> "BANK";
            case "mpesa", "mobilemoney", "mobile", "mpesapaybill", "paybill", "till" -> "MPESA";
            default -> null;
        });
    }

    public static String methodLabel(String method) {
        return switch (method) {
            case "CASH" -> "Cash";
            case "BANK" -> "Bank";
            case "MPESA" -> "M-Pesa";
            default -> method;
        };
    }

    // ---- amounts ------------------------------------------------------------------------------

    /** e.g. "KES 5,000.00" */
    public String format(long minor) {
        int digits = Math.max(Currency.getInstance(currency).getDefaultFractionDigits(), 0);
        return currency + " " + new DecimalFormat(digits == 0 ? "#,##0" : "#,##0." + "0".repeat(digits))
                .format(BigDecimal.valueOf(minor, digits));
    }
}
