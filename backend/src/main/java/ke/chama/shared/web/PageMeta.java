package ke.chama.shared.web;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

/**
 * Paging details for list responses. Pages are zero-based. Spring Data's {@code Page} is not
 * serialized directly because its JSON layout is not a guaranteed contract.
 */
public record PageMeta(
        @Schema(description = "Zero-based page number", example = "0") int page,
        @Schema(example = "50") int size,
        @Schema(example = "3") long totalElements,
        @Schema(example = "1") int totalPages,
        boolean hasNext,
        boolean hasPrevious) {

    public static PageMeta from(Page<?> page) {
        return new PageMeta(page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages(),
                page.hasNext(), page.hasPrevious());
    }
}
