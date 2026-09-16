package vn.infodation.mongodb.support;

import org.testcontainers.mongodb.MongoDBAtlasLocalContainer;

/**
 * Phase 0.4 - one container for the whole suite.
 * <p>
 * Started once and never stopped; Ryuk removes it when the JVM exits. Starting it per class
 * would add ~20s each time, and nothing in the suite needs a pristine server - the fixtures
 * reset the collections they touch.
 * <p>
 * This is the atlas-local image rather than plain {@code mongo} because the later phases need a
 * replica set (transactions, change streams) and mongot ({@code $search}), and switching the
 * harness halfway through would be busywork.
 */
public final class MongoLabContainer {

    public static final MongoDBAtlasLocalContainer INSTANCE =
            new MongoDBAtlasLocalContainer("mongodb/mongodb-atlas-local:8.0");

    static {
        INSTANCE.start();
    }

    private MongoLabContainer() {
    }
}
