# Thin image carrying mongorestore + mongosh. The official mongod images stopped
# shipping the database tools, so install them from MongoDB's own apt repository
# instead of guessing what another image happens to contain.
FROM debian:12-slim

ARG MONGODB_MAJOR=8.0

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates curl gnupg; \
    curl -fsSL "https://pgp.mongodb.com/server-${MONGODB_MAJOR}.asc" \
      | gpg --dearmor -o /usr/share/keyrings/mongodb-server.gpg; \
    echo "deb [signed-by=/usr/share/keyrings/mongodb-server.gpg] https://repo.mongodb.org/apt/debian bookworm/mongodb-org/${MONGODB_MAJOR} main" \
      > /etc/apt/sources.list.d/mongodb-org.list; \
    apt-get update; \
    apt-get install -y --no-install-recommends mongodb-database-tools mongodb-mongosh; \
    apt-get purge -y --auto-remove gnupg; \
    rm -rf /var/lib/apt/lists/*

COPY seed.sh /usr/local/bin/seed.sh
COPY search-indexes.js /opt/seed/search-indexes.js

RUN chmod +x /usr/local/bin/seed.sh

ENTRYPOINT ["/usr/local/bin/seed.sh"]
