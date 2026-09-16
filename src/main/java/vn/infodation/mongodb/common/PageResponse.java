package vn.infodation.mongodb.common;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/** Offset-paged payload. Phase 4 adds the keyset counterpart alongside this. */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    public <R> PageResponse<R> map(Function<T, R> mapper) {
        return new PageResponse<>(items.stream().map(mapper).toList(), page, size, totalElements, totalPages);
    }
}
