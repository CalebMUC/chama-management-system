package ke.chama.modules.chama.application.port.in;

import ke.chama.modules.chama.domain.model.Chama;
import ke.chama.modules.chama.domain.model.ChamaSettings;

import java.time.Instant;
import java.util.UUID;

public record ChamaView(UUID id,
                        String name,
                        String description,
                        String currency,
                        String status,
                        UUID createdBy,
                        Instant createdAt,
                        ChamaSettings settings) {

    public static ChamaView from(Chama chama) {
        return new ChamaView(chama.getId(), chama.getName(), chama.getDescription(),
                chama.getCurrency().getCurrencyCode(), chama.getStatus().name(), chama.getCreatedBy(),
                chama.getCreatedAt(), chama.getSettings());
    }
}
