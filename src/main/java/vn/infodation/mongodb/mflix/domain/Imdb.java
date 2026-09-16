package vn.infodation.mongodb.mflix.domain;

import lombok.Data;

/**
 * Embedded value object. {@code rating} and {@code votes} are empty strings in a few sample
 * documents - see {@code SampleDataConversions} for how those are read.
 */
@Data
public class Imdb {
    private Double rating;
    private Long votes;
    private Integer id;
}
