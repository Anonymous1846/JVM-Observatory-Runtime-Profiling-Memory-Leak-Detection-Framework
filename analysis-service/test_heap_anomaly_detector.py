"""
Tests for HeapAnomalyDetector.

Think of these like JUnit tests — each test method exercises one behavior.
pytest discovers any function starting with test_ automatically (like @Test).
"""

import pytest
from heap_anomaly_detector import HeapAnomalyDetector


def make_gc_event(heap_used_before, heap_used_after, duration_ms, app_id="test-app"):
    """Helper to create a GC event dict — like a test fixture / builder pattern."""
    return {
        "appId": app_id,
        "heapUsedBefore": heap_used_before,
        "heapUsedAfter": heap_used_after,
        "durationMs": duration_ms,
    }


class TestHeapAnomalyDetector:
    """Groups related tests — like a JUnit test class."""

    def test_returns_none_with_fewer_than_30_samples(self):
        """Guard clause: need 30 samples before analysis starts."""
        detector = HeapAnomalyDetector(window=60)

        # Feed 29 normal events — should all return None
        for i in range(29):
            result = detector.analyze(make_gc_event(
                heap_used_before=100_000_000,
                heap_used_after=50_000_000,
                duration_ms=15
            ))
            assert result is None, f"Expected None for sample {i}, got alert"

    def test_normal_steady_state_no_alert(self):
        """Stable heap usage should not trigger an alert."""
        detector = HeapAnomalyDetector(window=60)

        # Feed 40 identical "normal" events — steady state, no anomaly
        for i in range(40):
            result = detector.analyze(make_gc_event(
                heap_used_before=100_000_000,
                heap_used_after=50_000_000,
                duration_ms=15
            ))

        # After 40 identical events, IsolationForest should see them all as normal
        # (no outliers in uniform data)
        assert result is None

    def test_spike_triggers_alert(self):
        """A sudden heap spike after steady-state should be flagged as anomalous."""
        import random
        random.seed(42)
        detector = HeapAnomalyDetector(window=60)

        # Build up 40 normal baseline events with natural variation.
        # IsolationForest needs variance in features to learn a "normal" region;
        # identical events create degenerate trees that can't distinguish outliers.
        for _ in range(40):
            detector.analyze(make_gc_event(
                heap_used_before=100_000_000 + random.randint(-5_000_000, 5_000_000),
                heap_used_after=50_000_000 + random.randint(-3_000_000, 3_000_000),
                duration_ms=15 + random.randint(-5, 5)
            ))

        # Inject a dramatic spike — 18x heap, 50x pause.
        # The model was last trained at event 40. The first spike (event 41)
        # will be scored against that baseline model.
        spike_result = None
        for _ in range(15):
            r = detector.analyze(make_gc_event(
                heap_used_before=1_000_000_000,
                heap_used_after=900_000_000,
                duration_ms=800
            ))
            if r is not None:
                spike_result = r
                break

        assert spike_result is not None, "Expected an alert for the heap spike"
        assert spike_result["alertType"] == "GC_ANOMALY"
        assert spike_result["severity"] in ("MEDIUM", "HIGH")
        assert spike_result["appId"] == "test-app"
        assert "growthRatio" in spike_result

    def test_alert_contains_required_fields(self):
        """When an alert fires, it must have all fields the downstream pipeline expects."""
        detector = HeapAnomalyDetector(window=60)

        # Linearly growing heap — classic leak pattern
        for i in range(50):
            result = detector.analyze(make_gc_event(
                heap_used_before=100_000_000 + i * 5_000_000,
                heap_used_after=80_000_000 + i * 5_000_000,
                duration_ms=15 + i * 2
            ))
            if result is not None:
                break

        if result is not None:
            required_fields = {"eventType", "appId", "timestamp", "severity",
                               "alertType", "className", "leakProbability",
                               "growthRatio", "message"}
            assert required_fields.issubset(result.keys()), \
                f"Missing fields: {required_fields - result.keys()}"
            assert result["eventType"] == "ALERT"

    def test_separate_app_ids_are_independent(self):
        """Events from different apps should not interfere with each other."""
        detector = HeapAnomalyDetector(window=60)

        # Feed 35 events for app-A
        for _ in range(35):
            detector.analyze(make_gc_event(
                heap_used_before=100_000_000,
                heap_used_after=50_000_000,
                duration_ms=15,
                app_id="app-A"
            ))

        # app-B should still be in the warmup phase (< 30 samples)
        result = detector.analyze(make_gc_event(
            heap_used_before=999_000_000,
            heap_used_after=998_000_000,
            duration_ms=5000,
            app_id="app-B"
        ))
        assert result is None, "app-B should not have enough samples yet"

    def test_window_limits_sample_count(self):
        """The deque should never exceed the configured window size."""
        window = 30
        detector = HeapAnomalyDetector(window=window)

        for _ in range(100):
            detector.analyze(make_gc_event(
                heap_used_before=100_000_000,
                heap_used_after=50_000_000,
                duration_ms=15
            ))

        assert len(detector.samples["test-app"]) <= window
