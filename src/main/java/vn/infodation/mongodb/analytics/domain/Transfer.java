package vn.infodation.mongodb.analytics.domain;

import java.math.BigDecimal;
import java.time.Instant;

import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The audit record Phase 6 writes alongside the two balance updates. Its own collection rather
 * than {@code sample_analytics.transactions}, which has a different (bucketed) schema.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "transfers")
public class Transfer {

    @Id
    private ObjectId id;

    @Field("from_account_id")
    private Integer fromAccountId;

    @Field("to_account_id")
    private Integer toAccountId;

    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal amount;

    private Instant at;

    /** Set by the caller; the retry wrapper reuses it so a replayed commit is not double-counted. */
    @Field("idempotency_key")
    private String idempotencyKey;
}
