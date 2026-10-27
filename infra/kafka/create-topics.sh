#!/usr/bin/env bash
set -euo pipefail

# Explicit dev topology, kept aligned with KafkaTopics.developmentTopics() by tests.
bootstrap="${1:-${KAFKA_BOOTSTRAP_SERVERS:-kafka:19092}}"
for topic in pymk.member-events.v1 pymk.connection-events.v1; do
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$bootstrap" \
    --create --if-not-exists --topic "$topic" --partitions 3 --replication-factor 1 \
    --config cleanup.policy=delete --config retention.ms=604800000 \
    --config retention.bytes=-1 --config segment.ms=3600000 --config min.insync.replicas=1 \
    --config message.timestamp.type=LogAppendTime
  description=$(/opt/kafka/bin/kafka-topics.sh --bootstrap-server "$bootstrap" --describe --topic "$topic")
  [[ "$description" =~ PartitionCount:[[:space:]]+3 ]] &&
    [[ "$description" =~ ReplicationFactor:[[:space:]]+1 ]] || {
      echo "Unexpected partition/replication layout for $topic; review manually" >&2; exit 1;
    }
  for config in cleanup.policy=delete retention.ms=604800000 retention.bytes=-1 segment.ms=3600000 min.insync.replicas=1 message.timestamp.type=LogAppendTime; do
    [[ "$description" =~ (^|[[:space:],])${config}([[:space:],]|$) ]] || {
      echo "Unexpected topic configuration for $topic: expected $config; review manually" >&2; exit 1;
    }
  done
  echo "Verified $topic"
done
