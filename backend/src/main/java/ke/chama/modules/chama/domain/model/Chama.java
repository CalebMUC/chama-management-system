package ke.chama.modules.chama.domain.model;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import jakarta.persistence.*;
import ke.chama.modules.chama.domain.event.ChamaCreated;
import ke.chama.modules.chama.domain.event.ChamaSettingsChanged;
import ke.chama.shared.domain.AggregateRoot;
import ke.chama.shared.domain.exception.DomainValidationException;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/**
 * A savings and investment group, and a tenant of the platform: its {@code id} is the
 * {@code tenant_id} of all its data. Who belongs to it and in what role lives in the member
 * module; what it may use lives in its {@link License}.
 */
@Entity
@Table(name = "chamas", schema = "chama")
public class Chama extends AggregateRoot {

    static final int MAX_NAME_LENGTH = 120;
    static final int MAX_DESCRIPTION_LENGTH = 1000;

    /** Lower-case letters, digits and single hyphens, e.g. {@code mokica} or {@code jamii-sacco}. */
    static final Pattern CODE_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    static final int MIN_CODE_LENGTH = 3;
    static final int MAX_CODE_LENGTH = 30;

    /** Words that would be confusing as a chama code or are kept for platform use. */
    static final Set<String> RESERVED_CODES = Set.of("platform", "admin", "administrator", "api", "app", "www",
            "system", "support", "help", "root", "chama", "login", "register");

    @Id
    private UUID id;

    /** Unique, never changes. Members send it to log in ({@code X-Chama-Code}). */
    @Column(nullable = false, updatable = false, length = MAX_CODE_LENGTH)
    private String code;

    @Column(nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    @Column(length = MAX_DESCRIPTION_LENGTH)
    private String description;

    /** ISO 4217 code. Fixed at creation: every amount in the chama's records is in this currency. */
    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChamaStatus status;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Embedded
    private ChamaSettings settings;

    /** Required by JPA. */
    protected Chama() {
    }

    private Chama(UUID id, String code, String name, String description, Currency currency, ChamaSettings settings,
                  UUID createdBy) {
        this.id = Objects.requireNonNull(id, "id");
        this.code = normalizeCode(code);
        rename(name, description);
        this.currency = currency.getCurrencyCode();
        this.settings = settings;
        this.status = ChamaStatus.ACTIVE;
        this.createdBy = createdBy;
    }

    /**
     * @param id the new tenant id, chosen by the caller before the transaction so the database
     *           session can be scoped to it
     */
    public static Chama found(UUID id, String code, String name, String description, Currency currency,
                              ChamaSettings settings, UUID founderUserId, String founderEmail, Instant now) {
        Chama chama = new Chama(id, code, name, description, currency, settings, founderUserId);
        chama.registerEvent(new ChamaCreated(UUID.randomUUID(), now, chama.id, chama.name, founderUserId, founderEmail));
        return chama;
    }

    /** Trims and lower-cases a code, and rejects malformed or reserved ones. */
    public static String normalizeCode(String code) {
        String normalized = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() < MIN_CODE_LENGTH || normalized.length() > MAX_CODE_LENGTH
                || !CODE_PATTERN.matcher(normalized).matches()) {
            throw new DomainValidationException("CHAMA_INVALID_CODE", "The chama code must be " + MIN_CODE_LENGTH
                    + " to " + MAX_CODE_LENGTH + " letters, digits or hyphens, e.g. 'mokica' or 'jamii-sacco'");
        }
        if (RESERVED_CODES.contains(normalized)) {
            throw new DomainValidationException("CHAMA_INVALID_CODE", "'" + normalized + "' cannot be used as a chama code");
        }
        return normalized;
    }

    public void rename(String newName, String newDescription) {
        if (newName == null || newName.isBlank() || newName.trim().length() > MAX_NAME_LENGTH) {
            throw new DomainValidationException("CHAMA_INVALID_NAME",
                    "Name is required and must be at most " + MAX_NAME_LENGTH + " characters");
        }
        if (newDescription != null && newDescription.length() > MAX_DESCRIPTION_LENGTH) {
            throw new DomainValidationException("CHAMA_INVALID_DESCRIPTION",
                    "Description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        this.name = newName.trim();
        this.description = newDescription == null || newDescription.isBlank() ? null : newDescription.trim();
    }

    public void changeSettings(ChamaSettings newSettings, UUID changedBy, Instant now) {
        this.settings = newSettings;
        registerEvent(new ChamaSettingsChanged(UUID.randomUUID(), now, id, changedBy));
    }

    public boolean isActive() {
        return status == ChamaStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Currency getCurrency() {
        return Currency.getInstance(currency);
    }

    public ChamaStatus getStatus() {
        return status;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public ChamaSettings getSettings() {
        return settings;
    }
}
