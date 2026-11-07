package ke.chama.modules.chama.api;

import java.util.UUID;

/** @param code the chama's unique code, which members use to log in */
public record ChamaSummary(UUID id, String code, String name, String currency, boolean active) {
}
