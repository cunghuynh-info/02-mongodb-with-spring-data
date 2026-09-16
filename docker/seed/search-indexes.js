// Atlas Search indexes for the lab. mongot in the atlas-local image builds these
// the same way Atlas does, so the $search pipelines in the exercises run unchanged
// against a real cluster later.
const INDEXES = [
  {
    db: "sample_mflix",
    collection: "movies",
    name: "movies_search",
    definition: {
      mappings: {
        dynamic: false,
        fields: {
          title: [
            { type: "string", analyzer: "lucene.english" },
            { type: "autocomplete", tokenization: "edgeGram", minGrams: 2, maxGrams: 15 }
          ],
          plot: { type: "string", analyzer: "lucene.english" },
          fullplot: { type: "string", analyzer: "lucene.english" },
          // Indexed twice: once for matching, once as a facet type. $searchMeta facets only
          // work on stringFacet/numberFacet, and a plain string field is not one.
          genres: [
            { type: "string", analyzer: "lucene.keyword" },
            { type: "stringFacet" }
          ],
          cast: { type: "string" },
          directors: { type: "string" },
          year: [
            { type: "number" },
            { type: "numberFacet" }
          ],
          "imdb.rating": { type: "number" }
        }
      }
    }
  },
  {
    db: "sample_airbnb",
    collection: "listingsAndReviews",
    name: "listings_search",
    definition: { mappings: { dynamic: true } }
  }
];

function waitUntilQueryable(coll, name, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const info = coll.getSearchIndexes(name)[0];
    if (info && (info.queryable === true || info.status === "READY")) {
      return true;
    }
    sleep(2000);
  }
  return false;
}

for (const spec of INDEXES) {
  const database = db.getSiblingDB(spec.db);
  const coll = database.getCollection(spec.collection);

  if (coll.countDocuments({}, { limit: 1 }) === 0) {
    print(`[search] skip ${spec.db}.${spec.collection} - collection is empty`);
    continue;
  }

  const existing = coll.getSearchIndexes(spec.name);
  if (existing.length > 0) {
    // Update rather than skip, so editing a definition here actually takes effect on a
    // re-run instead of silently leaving the old index in place.
    coll.updateSearchIndex(spec.name, spec.definition);
    print(`[search] updated ${spec.db}.${spec.collection}/${spec.name}`);
  } else {
    coll.createSearchIndex(spec.name, spec.definition);
    print(`[search] created ${spec.db}.${spec.collection}/${spec.name}`);
  }

  if (waitUntilQueryable(coll, spec.name, 180000)) {
    print(`[search] ${spec.name} is queryable`);
  } else {
    print(`[search] WARNING ${spec.name} is not queryable yet - it is still building`);
  }
}
