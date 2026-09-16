package vn.infodation.mongodb.config;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.lang.NonNull;

/**
 * The Atlas sample data is real-world messy: a handful of {@code sample_mflix.movies} documents
 * store {@code year} as a string ({@code "2005è"}) and {@code imdb.rating} / {@code imdb.votes}
 * as an empty string rather than a number. Without these readers, mapping a full page of movies
 * blows up on a few stray documents.
 * <p>
 * These are reading converters only - nothing written by this application takes the lossy path.
 * Registering them narrows real bugs into silent nulls, which is an acceptable trade for a
 * practice dataset but would deserve a stricter treatment in production.
 */
@Configuration
public class SampleDataConversions {

    @Bean
    public MongoCustomConversions mongoCustomConversions() {
        return new MongoCustomConversions(List.of(
                StringToInteger.INSTANCE,
                StringToLong.INSTANCE,
                StringToDouble.INSTANCE));
    }

    /** First signed integer found in the text, or {@code null} when there is none. */
    private static final Pattern FIRST_NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

    private static String firstNumber(String source) {
        Matcher matcher = FIRST_NUMBER.matcher(source.replace(",", ""));
        return matcher.find() ? matcher.group() : null;
    }

    @ReadingConverter
    enum StringToInteger implements Converter<String, Integer> {
        INSTANCE;

        @Override
        public Integer convert(@NonNull String source) {
            String number = firstNumber(source);
            return number == null ? null : Integer.valueOf((int) Double.parseDouble(number));
        }
    }

    @ReadingConverter
    enum StringToLong implements Converter<String, Long> {
        INSTANCE;

        @Override
        public Long convert(@NonNull String source) {
            String number = firstNumber(source);
            return number == null ? null : Long.valueOf((long) Double.parseDouble(number));
        }
    }

    @ReadingConverter
    enum StringToDouble implements Converter<String, Double> {
        INSTANCE;

        @Override
        public Double convert(@NonNull String source) {
            String number = firstNumber(source);
            return number == null ? null : Double.valueOf(number);
        }
    }
}
