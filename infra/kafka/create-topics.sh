#!/usr/bin/env bash
set -euo pipefail

# Explicit dev topology, kept aligned with KafkaTopics.developmentTopics() by tests.
bootstrap="${1:-${KAFKA_BOOTSTRAP_SERVERS:-kafka:19092}}"
for topic in pymk.member-events.v1 pymk.connection-events.v1 pymk.member-events.dlt.v1 pymk.connection-events.dlt.v1; do
  retention=604800000
  extra=()
  if [[ "$topic" == *.dlt.v1 ]]; then
    retention=2592000000
    extra=(--config max.message.bytes=2097152)
  fi
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$bootstrap" \
    --create --if-not-exists --topic "$topic" --partitions 3 --replication-factor 1 \
    --config cleanup.policy=delete --config "retention.ms=$retention" \
    --config retention.bytes=-1 --config segment.ms=3600000 --config min.insync.replicas=1 \
    --config message.timestamp.type=LogAppendTime "${extra[@]}"
  description=$(/opt/kafka/bin/kafka-topics.sh --bootstrap-server "$bootstrap" --describe --topic "$topic")
  [[ "$description" =~ PartitionCount:[[:space:]]+3 ]] &&
    [[ "$description" =~ ReplicationFactor:[[:space:]]+1 ]] || {
      echo "Unexpected partition/replication layout for $topic; review manually" >&2; exit 1;
    }
  for config in cleanup.policy=delete "retention.ms=$retention" retention.bytes=-1 segment.ms=3600000 min.insync.replicas=1 message.timestamp.type=LogAppendTime; do
    [[ "$description" =~ (^|[[:space:],])${config}([[:space:],]|$) ]] || {
      echo "Unexpected topic configuration for $topic: expected $config; review manually" >&2; exit 1;
    }
  done
  if [[ "$topic" == *.dlt.v1 ]]; then
    [[ "$description" =~ (^|[[:space:],])max.message.bytes=2097152([[:space:],]|$) ]] || {
      echo "Unexpected DLT maximum record size for $topic; review manually" >&2; exit 1;
    }
  fi
  echo "Verified $topic"
done
