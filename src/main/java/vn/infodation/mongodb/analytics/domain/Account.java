package vn.infodation.mongodb.analytics.domain;

import java.math.BigDecimal;
import java.util.List;

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
 * {@code sample_analytics.accounts}. The sample documents carry {@code account_id},
 * {@code limit} and {@code products}; {@code balance} is added by this application - the
 * dataset has no money field, and Phase 6 needs one to move.
 * <p>
 * Phase 0.5 convention: money is {@link BigDecimal} stored as {@code DECIMAL128}. A double
 * balance accumulates rounding error, and {@code $inc} on a double is how you end up with
 * 0.30000000000000004 in an audit report.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "accounts")
public class Account {

    @Id
    private ObjectId id;

    @Field("account_id")
    private Integer accountId;

    @Field("limit")
    private Integer creditLimit;

    private List<String> products;

    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal balance;
}
