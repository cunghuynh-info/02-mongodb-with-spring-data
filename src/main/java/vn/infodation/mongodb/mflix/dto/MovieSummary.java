package vn.infodation.mongodb.mflix.dto;

import java.util.List;

/**
 * Phase 2.3 - a closed interface projection. Spring Data derives the field mask from the
 * getters, so the server sends back {@code title}, {@code year}, {@code genres} and
 * {@code imdb.rating} only - not the 4 KB {@code fullplot} that nobody asked for.
 */
public interface MovieSummary {

    String getId();

    String getTitle();

    Integer getYear();

    List<String> getGenres();

    ImdbSummary getImdb();

    interface ImdbSummary {
        Double getRating();
    }
}
