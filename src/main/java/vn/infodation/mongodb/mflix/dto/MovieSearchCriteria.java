package vn.infodation.mongodb.mflix.dto;

import java.util.List;

/**
 * Phase 2.2 - every field is optional; only the ones that are set contribute a predicate.
 * A record keeps the filter immutable and trivially testable without a running database.
 */
public record MovieSearchCriteria(
        String title,
        List<String> genres,
        Integer yearFrom,
        Integer yearTo,
        Double minRating,
        String castMember) {

    public static MovieSearchCriteria empty() {
        return new MovieSearchCriteria(null, null, null, null, null, null);
    }
}
