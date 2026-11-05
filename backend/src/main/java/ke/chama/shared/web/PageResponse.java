package ke.chama.shared.web;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Stable JSON shape for paginated lists. Spring Data's {@code Page} is not serialized directly
 * because its JSON layout is not a guaranteed contract.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    public static <S, T> PageResponse<T> from(Page<S> page, Function<? super S, ? extends T> mapper) {
        return from(page.map(mapper));
    }
}
