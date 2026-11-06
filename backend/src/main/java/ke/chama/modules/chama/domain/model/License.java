package ke.chama.modules.chama.domain.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import ke.chama.shared.domain.AggregateRoot;
import ke.chama.shared.domain.exception.DomainValidationException;
import ke.chama.shared.tenancy.LicenseFeature;
import org.hibernate.annotations.TenantId;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A chama's licence to use the platform: plan, validity period, member limit and features.
 * Each chama has exactly one, issued when it is onboarded (a trial) and later renewed, upgraded
 * or suspended by a platform administrator. Every change is recorded in the chama's audit log.
 */
@Entity
@Table(name = "licenses", schema = "chama")
public class License extends AggregateRoot {

    static final int MAX_NOTES_LENGTH = 500;

    /** No 0/O or 1/I, so a key read over the phone is not misheard. */
    private static final char[] KEY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    @Id
    private UUID id;

    /** The chama (tenant) this licence belongs to. */
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID chamaId;

    /** Human-readable reference for support and billing, e.g. {@code CHM-7K2P-9QXM-4RTD-H8WN}. */
    @Column(name = "license_key", nullable = false, updatable = false, length = 40)
    private String licenseKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LicensePlan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LicenseStatus status;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    /** Active plus suspended members may not exceed this. */
    @Column(name = "max_members", nullable = false)
    private int maxMembers;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "license_features", schema = "chama", joinColumns = @JoinColumn(name = "license_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "feature", nullable = false, length = 40)
    private Set<LicenseFeature> features = EnumSet.noneOf(LicenseFeature.class);

    @Column(length = MAX_NOTES_LENGTH)
    private String notes;

    @Column(name = "updated_by")
    private UUID updatedBy;

    /** Required by JPA. */
    protected License() {
    }

    private License(UUID chamaId, LicensePlan plan, Instant validFrom, Instant validUntil) {
        this.id = UUID.randomUUID();
        this.chamaId = Objects.requireNonNull(chamaId, "chamaId");
        this.licenseKey = generateKey();
        this.plan = plan;
        this.status = LicenseStatus.ACTIVE;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.maxMembers = plan.defaultMaxMembers();
        this.features.addAll(plan.defaultFeatures());
    }

    /** The licence every new chama starts with. */
    public static License trial(UUID chamaId, Instant now, Duration length) {
        if (length == null || length.isNegative() || length.isZero()) {
            throw new IllegalArgumentException("Trial length must be positive");
        }
        return new License(chamaId, LicensePlan.TRIAL, now, now.plus(length));
    }

    /**
     * Applies a platform administrator's change. Null arguments leave that part unchanged.
     * Changing the plan resets the member limit and features to the plan's defaults unless they
     * are given explicitly.
     */
    public void update(LicensePlan newPlan, Instant newValidUntil, Integer newMaxMembers,
                       Set<LicenseFeature> newFeatures, LicenseStatus newStatus, String newNotes,
                       UUID by, Instant now) {
        if (newPlan != null && newPlan != plan) {
            plan = newPlan;
            maxMembers = newPlan.defaultMaxMembers();
            features.clear();
            features.addAll(newPlan.defaultFeatures());
        }
        if (newValidUntil != null) {
            if (!newValidUntil.isAfter(validFrom)) {
                throw new DomainValidationException("LICENSE_INVALID_PERIOD", "validUntil must be after the licence start");
            }
            validUntil = newValidUntil;
        }
        if (newMaxMembers != null) {
            if (newMaxMembers < 1) {
                throw new DomainValidationException("LICENSE_INVALID_MEMBER_LIMIT", "maxMembers must be at least 1");
            }
            maxMembers = newMaxMembers;
        }
        if (newFeatures != null) {
            features.clear();
            features.addAll(newFeatures);
        }
        if (newStatus != null) {
            status = newStatus;
        }
        if (newNotes != null) {
            if (newNotes.length() > MAX_NOTES_LENGTH) {
                throw new DomainValidationException("LICENSE_INVALID_NOTES",
                        "notes must be at most " + MAX_NOTES_LENGTH + " characters");
            }
            notes = newNotes.isBlank() ? null : newNotes.trim();
        }
        updatedBy = by;
    }

    public LicenseState stateAt(Instant now) {
        if (status == LicenseStatus.SUSPENDED) {
            return LicenseState.SUSPENDED;
        }
        return now.isBefore(validUntil) ? LicenseState.ACTIVE : LicenseState.EXPIRED;
    }

    /** For the audit log's before/after details. */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("plan", plan.name());
        map.put("status", status.name());
        map.put("validUntil", validUntil.toString());
        map.put("maxMembers", maxMembers);
        map.put("features", features.stream().map(Enum::name).sorted().toList());
        return map;
    }

    private static String generateKey() {
        StringBuilder key = new StringBuilder("CHM");
        for (int group = 0; group < 4; group++) {
            key.append('-');
            for (int i = 0; i < 4; i++) {
                key.append(KEY_ALPHABET[RANDOM.nextInt(KEY_ALPHABET.length)]);
            }
        }
        return key.toString();
    }

    public UUID getId() {
        return id;
    }

    public UUID getChamaId() {
        return chamaId;
    }

    public String getLicenseKey() {
        return licenseKey;
    }

    public LicensePlan getPlan() {
        return plan;
    }

    public LicenseStatus getStatus() {
        return status;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    public int getMaxMembers() {
        return maxMembers;
    }

    public Set<LicenseFeature> getFeatures() {
        return Collections.unmodifiableSet(features);
    }

    public String getNotes() {
        return notes;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }
}
