package vn.infodation.mongodb.supplies.service;

import java.util.List;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Phase 2.2 - {@code $elemMatch} where it changes the answer.
 * <p>
 * {@code sales.items} is an array of documents. Two separate predicates match a sale where
 * <em>some</em> item is named "laptop" and <em>some</em> (possibly different) item has
 * quantity &gt;= 5. {@code $elemMatch} demands that one item satisfies both.
 * <p>
 * Spring's {@code Criteria} can only express this over named subfields; there is no keyless
 * form for an array of scalars - see {@code MovieCriteriaBuilder}.
 */
@Service
public class SalesQueryService {

    private static final String SALES = "sales";

    private final MongoTemplate suppliesTemplate;

    public SalesQueryService(@Qualifier("suppliesTemplate") MongoTemplate suppliesTemplate) {
        this.suppliesTemplate = suppliesTemplate;
    }

    /** One item must be both the named product and at least {@code minQuantity}. */
    public List<Document> salesWithItem(String itemName, int minQuantity) {
        Query query = Query.query(Criteria.where("items")
                .elemMatch(Criteria.where("name").is(itemName).and("quantity").gte(minQuantity)));
        return suppliesTemplate.find(query, Document.class, SALES);
    }

    /** The looser form, kept for contrast: the two conditions may land on different items. */
    public List<Document> salesWithItemLoose(String itemName, int minQuantity) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("items.name").is(itemName),
                Criteria.where("items.quantity").gte(minQuantity)));
        return suppliesTemplate.find(query, Document.class, SALES);
    }
}
