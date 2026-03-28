#!/usr/bin/env python3
"""
JVM Observatory — Analysis Engine

Consumes GC and allocation events from Kafka, runs anomaly detection
and leak ranking, and publishes alerts back to Kafka.

Usage:
    cd analysis-service
    python -m venv venv
    venv\\Scripts\\activate   (Windows)
    pip install -r requirements.txt
    python analysis_engine.py

Environment variables:
    KAFKA_SERVERS  — Kafka bootstrap servers (default: localhost:9092)
    CONSUMER_GROUP — Kafka consumer group ID (default: jvm-observatory-analyzer)
"""

import os
import sys
import json
import logging
import signal
from kafka import KafkaConsumer, KafkaProducer
from heap_anomaly_detector import HeapAnomalyDetector
from leak_ranker import LeakRanker

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
)
log = logging.getLogger("analysis-engine")


def main():
    kafka_servers = os.environ.get("KAFKA_SERVERS", "localhost:9092")
    group_id = os.environ.get("CONSUMER_GROUP", "jvm-observatory-analyzer")

    log.info("Starting JVM Observatory Analysis Engine")
    log.info("  Kafka: %s", kafka_servers)
    log.info("  Group: %s", group_id)

    # Initialize ML models
    detector = HeapAnomalyDetector(window=60)
    ranker = LeakRanker()

    # Kafka consumer: subscribes to GC + allocation events
    consumer = KafkaConsumer(
        "jvm.allocations", "jvm.gc",
        bootstrap_servers=kafka_servers,
        group_id=group_id,
        auto_offset_reset="latest",   # ISSUE 9 FIX: don't replay history on restart
        value_deserializer=lambda m: json.loads(m.decode("utf-8")),
        max_poll_records=500,
        consumer_timeout_ms=1000       # return from poll after 1s if no messages
    )

    # Kafka producer: publishes alerts
    producer = KafkaProducer(
        bootstrap_servers=kafka_servers,
        value_serializer=lambda v: json.dumps(v).encode("utf-8"),
        key_serializer=lambda k: k.encode("utf-8") if k else None
    )

    # Graceful shutdown on Ctrl+C
    running = True

    def shutdown(sig, frame):
        nonlocal running
        log.info("Shutting down...")
        running = False

    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)

    log.info("Listening for events on jvm.allocations and jvm.gc...")

    events_processed = 0
    alerts_sent = 0

    while running:
        try:
            # Poll returns a dict of {TopicPartition: [ConsumerRecord, ...]}
            # consumer_timeout_ms=1000 means this returns after 1s even if empty
            for message in consumer:
                if not running:
                    break

                event = message.value
                topic = message.topic
                app_id = message.key.decode("utf-8") if message.key else "unknown"
                events_processed += 1

                alert = None

                if topic == "jvm.gc":
                    alert = detector.analyze(event)
                elif topic == "jvm.allocations":
                    alert = ranker.update(event)

                if alert:
                    producer.send("jvm.alerts", key=app_id, value=alert)
                    producer.flush()
                    alerts_sent += 1
                    log.info("ALERT [%s] %s — %s (severity=%s)",
                             app_id, alert["alertType"], alert["message"], alert["severity"])

                # Periodic stats
                if events_processed % 1000 == 0:
                    log.info("Processed %d events, sent %d alerts", events_processed, alerts_sent)

        except Exception as e:
            if running:
                log.error("Error in main loop: %s", e, exc_info=True)

    # Cleanup
    consumer.close()
    producer.close()
    log.info("Analysis engine stopped. Processed %d events, sent %d alerts.", events_processed, alerts_sent)


if __name__ == "__main__":
    main()
