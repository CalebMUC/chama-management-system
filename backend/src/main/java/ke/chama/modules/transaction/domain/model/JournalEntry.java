package ke.chama.modules.transaction.domain.model;

import ke.chama.modules.transaction.api.PostingRequest;
import ke.chama.modules.transaction.domain.exception.LedgerExceptions.InvalidEntryException;
import ke.chama.modules.transaction.domain.exception.LedgerExceptions.UnbalancedEntryException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A balanced journal entry: total debits equal total credits, with at least two lines. Immutable
 * once created and never changed after posting; a mistake is undone with {@link #reversal}.
 * The database checks the balance again when the transaction commits.
 *
 * @param number assigned when posted: gap-free and increasing within the chama
 */
public record JournalEntry(UUID id,
                           UUID chamaId,
                           long number,
                           LocalDate entryDate,
                           String description,
                           String reference,
                           String sourceType,
                           String sourceId,
                           UUID reversesEntryId,
                           UUID recordedBy,
                           Instant recordedAt,
                           List<JournalLine> lines) {

    static final int MAX_DESCRIPTION_LENGTH = 500;
    static final int MAX_REFERENCE_LENGTH = 100;
    static final int MAX_MEMO_LENGTH = 200;

    public JournalEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(chamaId, "chamaId");
        lines = List.copyOf(lines);
    }

    /**
     * Validates and builds an entry to be posted.
     *
     * @param today the chama's current date; entries cannot be dated in the future
     */
    public static JournalEntry create(UUID chamaId, LocalDate entryDate, String description, String reference,
                                      String sourceType, String sourceId, UUID reversesEntryId, UUID recordedBy,
                                      List<JournalLine> lines, LocalDate today, Instant now) {
        LocalDate date = entryDate == null ? today : entryDate;
        if (date.isAfter(today)) {
            throw new InvalidEntryException("An entry cannot be dated in the future");
        }
        String text = description == null ? "" : description.trim();
        if (text.isEmpty() || text.length() > MAX_DESCRIPTION_LENGTH) {
            throw new InvalidEntryException("A description is required (at most " + MAX_DESCRIPTION_LENGTH + " characters)");
        }
        String ref = reference == null || reference.isBlank() ? null : reference.trim();
        if (ref != null && ref.length() > MAX_REFERENCE_LENGTH) {
            throw new InvalidEntryException("reference must be at most " + MAX_REFERENCE_LENGTH + " characters");
        }
        if (lines == null || lines.size() < 2) {
            throw new UnbalancedEntryException("An entry needs at least one debit and one credit line");
        }
        long debits = 0;
        long credits = 0;
        for (JournalLine line : lines) {
            if (line.amountMinor() <= 0) {
                throw new InvalidEntryException("Every line amount must be greater than zero");
            }
            if (line.memo() != null && line.memo().length() > MAX_MEMO_LENGTH) {
                throw new InvalidEntryException("A line memo must be at most " + MAX_MEMO_LENGTH + " characters");
            }
            debits = Math.addExact(debits, line.debitMinor());
            credits = Math.addExact(credits, line.creditMinor());
        }
        if (debits != credits) {
            throw new UnbalancedEntryException("Debits (" + debits + ") must equal credits (" + credits + ")");
        }
        return new JournalEntry(UUID.randomUUID(), chamaId, 0, date, text, ref, sourceType, sourceId,
                reversesEntryId, recordedBy, now, lines);
    }

    /** The mirror image of this entry, dated {@code today}, which cancels it out. */
    public JournalEntry reversal(String reason, UUID recordedBy, LocalDate today, Instant now) {
        return reversal(reason, recordedBy, today, today, now);
    }

    /**
     * The mirror image of this entry, dated {@code entryDate}: e.g. the original's own date, when a
     * record imported with a past date is undone, so reports for that period come out unchanged.
     */
    public JournalEntry reversal(String reason, UUID recordedBy, LocalDate entryDate, LocalDate today, Instant now) {
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) {
            throw new InvalidEntryException("A reason is required to reverse an entry");
        }
        String text = "Reversal of entry #" + number + ": " + why;
        if (text.length() > MAX_DESCRIPTION_LENGTH) {
            text = text.substring(0, MAX_DESCRIPTION_LENGTH);
        }
        return create(chamaId, entryDate, text, reference, PostingRequest.SOURCE_REVERSAL, id.toString(), id, recordedBy,
                lines.stream().map(JournalLine::mirrored).toList(), today, now);
    }

    public JournalEntry numbered(long assignedNumber) {
        return new JournalEntry(id, chamaId, assignedNumber, entryDate, description, reference, sourceType, sourceId,
                reversesEntryId, recordedBy, recordedAt, lines);
    }

    public boolean isReversal() {
        return reversesEntryId != null;
    }

    public long totalMinor() {
        return lines.stream().mapToLong(JournalLine::debitMinor).sum();
    }
}
