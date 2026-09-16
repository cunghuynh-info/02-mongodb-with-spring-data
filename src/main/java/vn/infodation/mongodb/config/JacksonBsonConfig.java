package vn.infodation.mongodb.config;

import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * Aggregation endpoints return raw {@link Document}s in places, and without this an
 * {@link ObjectId} serialises as {@code {"timestamp":...,"date":...}} instead of its hex string.
 * <p>
 * Spring Boot 4 ships Jackson 3, so these are {@code tools.jackson} types - {@code ValueSerializer}
 * rather than Jackson 2's {@code JsonSerializer}.
 */
@Configuration
public class JacksonBsonConfig {

    @Bean
    public SimpleModule bsonModule() {
        SimpleModule module = new SimpleModule("bson");
        module.addSerializer(ObjectId.class, new ValueSerializer<ObjectId>() {
            @Override
            public void serialize(ObjectId value, JsonGenerator gen, SerializationContext ctxt) {
                gen.writeString(value.toHexString());
            }
        });
        module.addSerializer(Decimal128.class, new ValueSerializer<Decimal128>() {
            @Override
            public void serialize(Decimal128 value, JsonGenerator gen, SerializationContext ctxt) {
                gen.writeNumber(value.bigDecimalValue());
            }
        });
        return module;
    }
}
