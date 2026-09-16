package vn.infodation.mongodb.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 0.5 - the readers that keep the malformed sample documents from breaking a page load. */
class SampleDataConversionsTest {

    @Test
    void stripsTrailingJunkFromAYear() {
        assertThat(SampleDataConversions.StringToInteger.INSTANCE.convert("2005è")).isEqualTo(2005);
    }

    @Test
    void emptyStringBecomesNullRatherThanThrowing() {
        assertThat(SampleDataConversions.StringToDouble.INSTANCE.convert("")).isNull();
        assertThat(SampleDataConversions.StringToLong.INSTANCE.convert("")).isNull();
        assertThat(SampleDataConversions.StringToInteger.INSTANCE.convert("")).isNull();
    }

    @Test
    void readsThousandsSeparatorsAndDecimals() {
        assertThat(SampleDataConversions.StringToLong.INSTANCE.convert("1,234,567")).isEqualTo(1_234_567L);
        assertThat(SampleDataConversions.StringToDouble.INSTANCE.convert("8.7")).isEqualTo(8.7);
    }

    @Test
    void keepsTheSign() {
        assertThat(SampleDataConversions.StringToInteger.INSTANCE.convert("-42")).isEqualTo(-42);
    }
}
