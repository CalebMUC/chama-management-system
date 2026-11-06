package ke.chama.modules.chama.api;

import java.util.UUID;

public record ChamaSummary(UUID id, String name, String currency, boolean active) {
}
