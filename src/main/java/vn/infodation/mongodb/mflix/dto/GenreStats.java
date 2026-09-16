package vn.infodation.mongodb.mflix.dto;

/** Phase 2.5 output. */
public record GenreStats(String genre, long movieCount, Double averageRating, Integer latestYear) {
}
