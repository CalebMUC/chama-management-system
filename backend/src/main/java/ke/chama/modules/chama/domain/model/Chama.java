package ke.chama.modules.chama.domain.model;

import jakarta.persistence.*;
import ke.chama.modules.chama.domain.event.ChamaCreated;
import ke.chama.modules.chama.domain.event.ChamaSettingsChanged;
import ke.chama.shared.domain.AggregateRoot;
import ke.chama.shared.domain.exception.DomainValidationException;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/** A savings and investment group. Who belongs to it and in what role lives in the member module. */
@Entity
@Table(name = "chamas", schema = "chama")
public class Chama extends AggregateRoot {

    static final int MAX_NAME_LENGTH = 120;
    static final int MAX_DESCRIPTION_LENGTH = 1000;

    @Id
    private UUID id;

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

    private Chama(String name, String description, Currency currency, ChamaSettings settings, UUID createdBy) {
        this.id = UUID.randomUUID();
        rename(name, description);
        this.currency = currency.getCurrencyCode();
        this.settings = settings;
        this.status = ChamaStatus.ACTIVE;
        this.createdBy = createdBy;
    }

    public static Chama found(String name, String description, Currency currency, ChamaSettings settings,
                              UUID founderUserId, String founderEmail, Instant now) {
        Chama chama = new Chama(name, description, currency, settings, founderUserId);
        chama.registerEvent(new ChamaCreated(UUID.randomUUID(), now, chama.id, chama.name, founderUserId, founderEmail));
        return chama;
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
