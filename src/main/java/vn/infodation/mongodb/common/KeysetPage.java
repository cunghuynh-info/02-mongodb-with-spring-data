package vn.infodation.mongodb.common;

import java.util.List;
import java.util.function.Function;

/**
 * Phase 4.6 - what a keyset endpoint can honestly return: the rows, and where to continue.
 * <p>
 * No {@code totalElements} and no {@code totalPages}, because getting them would mean the
 * second round trip that keyset paging exists to avoid. No page number either - there is no
 * such thing as "jump to page 500" here, which is the trade you are making.
 */
public record KeysetPage<T>(List<T> items, String nextCursor, boolean hasNext, int size) {

    public <R> KeysetPage<R> map(Function<T, R> mapper) {
        return new KeysetPage<>(items.stream().map(mapper).toList(), nextCursor, hasNext, size);
    }
}
