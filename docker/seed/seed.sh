#!/usr/bin/env bash
# Restores the Atlas sample dataset into the lab deployment and creates the
# Atlas Search indexes used by the search exercises. Safe to run repeatedly.
set -euo pipefail

MONGO_HOST="${MONGO_HOST:-mongodb}"
MONGO_PORT="${MONGO_PORT:-27017}"
MONGO_USERNAME="${MONGO_USERNAME:-root}"
MONGO_PASSWORD="${MONGO_PASSWORD:-example}"
SAMPLE_DATABASES="${SAMPLE_DATABASES:-sample_mflix,sample_airbnb,sample_analytics,sample_training,sample_supplies}"
SAMPLE_ARCHIVE_URL="${SAMPLE_ARCHIVE_URL:-https://atlas-education.s3.amazonaws.com/sampledata.archive}"
SEED_FORCE="${SEED_FORCE:-false}"

CACHE_DIR="/seed-cache"
ARCHIVE="${CACHE_DIR}/sampledata.archive"
URI="mongodb://${MONGO_USERNAME}:${MONGO_PASSWORD}@${MONGO_HOST}:${MONGO_PORT}/?authSource=admin&directConnection=true"

log() { printf '[seed] %s\n' "$*"; }

mongosh_eval() {
  mongosh "$URI" --quiet --eval "$1"
}

log "waiting for mongod at ${MONGO_HOST}:${MONGO_PORT} ..."
for attempt in $(seq 1 60); do
  if mongosh_eval 'db.adminCommand({ping:1}).ok' >/dev/null 2>&1; then
    log "mongod is up"
    break
  fi
  if [ "$attempt" -eq 60 ]; then
    log "ERROR: mongod did not become reachable in time"
    exit 1
  fi
  sleep 2
done

# A marker document, rather than a collection count, so a partially restored
# database is not mistaken for a finished restore.
seed_state="$(mongosh_eval 'print(db.getSiblingDB("lab_meta").seed_state.countDocuments({_id:"sampledata"}) > 0 ? "SEEDED" : "EMPTY")' || echo UNKNOWN)"

if [[ "$seed_state" == *SEEDED* ]] && [ "$SEED_FORCE" != "true" ]; then
  log "sample data already present - skipping restore (SEED_FORCE=true restores again)"
else
  mkdir -p "$CACHE_DIR"

  remote_size="$(curl -fsSLI "$SAMPLE_ARCHIVE_URL" | awk 'tolower($1) == "content-length:" { print $2 }' | tr -d '\r' | tail -1)"
  local_size=0
  [ -f "$ARCHIVE" ] && local_size="$(stat -c %s "$ARCHIVE")"

  if [ -n "$remote_size" ] && [ "$local_size" = "$remote_size" ]; then
    log "archive already cached (${local_size} bytes)"
  else
    log "downloading sample archive (~380 MB, resumable, cached in the sample-archive volume)"
    curl --fail --location --retry 3 --continue-at - --no-progress-meter --output "$ARCHIVE" "$SAMPLE_ARCHIVE_URL"
    log "download finished ($(stat -c %s "$ARCHIVE") bytes)"
  fi

  ns_args=()
  IFS=',' read -ra dbs <<< "$SAMPLE_DATABASES"
  for name in "${dbs[@]}"; do
    name="$(echo "$name" | tr -d '[:space:]')"
    [ -n "$name" ] && ns_args+=(--nsInclude "${name}.*")
  done

  log "restoring: ${SAMPLE_DATABASES}"
  mongorestore \
    --uri "$URI" \
    --archive="$ARCHIVE" \
    --drop \
    --numParallelCollections=4 \
    --quiet \
    "${ns_args[@]}"

  mongosh_eval "db.getSiblingDB('lab_meta').seed_state.replaceOne(
      { _id: 'sampledata' },
      { _id: 'sampledata', databases: '${SAMPLE_DATABASES}', restoredAt: new Date() },
      { upsert: true })" >/dev/null
  log "restore complete"
fi

log "ensuring Atlas Search indexes"
mongosh "$URI" --quiet --file /opt/seed/search-indexes.js

log "done. connect with:"
log "  mongodb://${MONGO_USERNAME}:<password>@localhost:${MONGO_PORT}/?authSource=admin&directConnection=true"
