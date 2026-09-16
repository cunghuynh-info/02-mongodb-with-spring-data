package vn.infodation.mongodb.mflix.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import vn.infodation.mongodb.mflix.dto.MovieSearchCriteria;

/**
 * Phase 2.2 - builds the {@link Criteria} for a {@link MovieSearchCriteria}.
 * <p>
 * Kept separate from the repository so the generated query document can be asserted in a plain
 * unit test, with no database involved.
 */
public final class MovieCriteriaBuilder {

    private MovieCriteriaBuilder() {
    }

    public static Criteria build(MovieSearchCriteria filter) {
        List<Criteria> parts = new ArrayList<>();

        if (StringUtils.hasText(filter.title())) {
            // Pattern.quote so a user typing "(500) Days" does not become a broken regex.
            // Unanchored, so this cannot use an index - Phase 8 replaces it with Atlas Search.
            parts.add(Criteria.where("title").regex(Pattern.quote(filter.title()), "i"));
        }

        if (!CollectionUtils.isEmpty(filter.genres())) {
            parts.add(Criteria.where("genres").in(filter.genres()));
        }

        if (filter.yearFrom() != null || filter.yearTo() != null) {
            Criteria year = Criteria.where("year");
            if (filter.yearFrom() != null) {
                year = year.gte(filter.yearFrom());
            }
            if (filter.yearTo() != null) {
                year = year.lte(filter.yearTo());
            }
            parts.add(year);
        }

        if (filter.minRating() != null) {
            parts.add(Criteria.where("imdb.rating").gte(filter.minRating()));
        }

        if (StringUtils.hasText(filter.castMember())) {
            // A predicate on an array field matches if *any* element matches, so a single
            // condition needs no $elemMatch. It would not work here anyway: Spring's Criteria
            // has no keyless form, and `new Criteria().regex(..)` quietly renders as `{}`.
            // See SalesQueryService for $elemMatch where it is actually required - two
            // conditions that must hold for the same array element.
            parts.add(Criteria.where("cast").regex(Pattern.quote(filter.castMember()), "i"));
        }

        if (parts.isEmpty()) {
            return new Criteria();
        }
        // andOperator rather than chaining .and(): chaining throws when the same key appears
        // twice, and an explicit $and keeps every predicate independent.
        return new Criteria().andOperator(parts.toArray(Criteria[]::new));
    }
}
