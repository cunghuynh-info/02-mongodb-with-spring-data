package vn.infodation.mongodb.common;

import org.bson.Document;

/**
 * The four numbers worth looking at in an {@code executionStats} explain, pulled out of the
 * several hundred lines MongoDB actually returns.
 * <p>
 * {@code docsExamined} far above {@code returned} is the signal that an index is missing -
 * which is exactly the before/after measurement Phase 3 is built around.
 */
public record ExplainSummary(
        String winningStage,
        String indexName,
        int returned,
        int keysExamined,
        int docsExamined,
        int executionTimeMillis) {

    public static ExplainSummary of(Document explain) {
        Document stats = explain.get("executionStats", new Document());
        Document winning = nested(explain, "queryPlanner", "winningPlan");
        // Since 6.0 the plan can sit one level deeper under "queryPlan".
        Document plan = winning.get("queryPlan", Document.class);
        Document effective = plan != null ? plan : winning;

        return new ExplainSummary(
                effective.getString("stage"),
                findIndexName(effective),
                stats.getInteger("nReturned", -1),
                stats.getInteger("totalKeysExamined", -1),
                stats.getInteger("totalDocsExamined", -1),
                stats.getInteger("executionTimeMillis", -1));
    }

    /** Walks down inputStage until it finds the IXSCAN, if there is one. */
    private static String findIndexName(Document stage) {
        Document current = stage;
        while (current != null) {
            if (current.containsKey("indexName")) {
                return current.getString("indexName");
            }
            current = current.get("inputStage", Document.class);
        }
        return null;
    }

    private static Document nested(Document root, String... path) {
        Document current = root;
        for (String key : path) {
            if (current == null) {
                return new Document();
            }
            current = current.get(key, Document.class);
        }
        return current == null ? new Document() : current;
    }
}
