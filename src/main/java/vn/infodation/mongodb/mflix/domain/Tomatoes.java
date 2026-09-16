package vn.infodation.mongodb.mflix.domain;

import java.time.Instant;

import lombok.Data;

/** Embedded value object, itself containing two more embedded documents. */
@Data
public class Tomatoes {

    private Rating viewer;
    private Rating critic;
    private Integer fresh;
    private Integer rotten;
    private Instant lastUpdated;

    @Data
    public static class Rating {
        private Double rating;
        private Integer numReviews;
        private Integer meter;
    }
}
