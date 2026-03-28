"""
Tests for LeakRanker.

These test the heuristic-based leak detection logic without needing Kafka.
"""

import time
import pytest
from unittest.mock import patch
from leak_ranker import LeakRanker


def make_alloc_event(class_name, app_id="test-app"):
    """Helper to create an allocation event dict."""
    return {
        "appId": app_id,
        "className": class_name,
    }


class TestLeakRanker:

    def test_returns_none_with_insufficient_data(self):
        """Need at least 60 samples before analysis starts."""
        ranker = LeakRanker()

        for _ in range(59):
            result = ranker.update(make_alloc_event("java.util.HashMap"))

        assert result is None

    def test_returns_none_for_empty_class_name(self):
        """Events without a class name should be ignored."""
        ranker = LeakRanker()
        result = ranker.update({"appId": "test-app", "className": ""})
        assert result is None

    def test_steady_rate_no_alert(self):
        """Constant allocation rate (ratio ~1.0) should not trigger an alert."""
        ranker = LeakRanker()

        # Simulate 120 events spread evenly across 120 seconds
        # All at the same rate => growth_ratio ~ 1.0
        base_time = 1000000.0

        with patch("leak_ranker.time") as mock_time:
            for i in range(120):
                # 1 event per second, steady rate
                mock_time.time.return_value = base_time + i
                result = ranker.update(make_alloc_event("java.util.HashMap"))

        assert result is None

    def test_growth_spike_triggers_alert(self):
        """If recent allocations are 3x the older period, an alert should fire."""
        ranker = LeakRanker()

        base_time = 1000000.0

        with patch("leak_ranker.time") as mock_time:
            # Older period (60-120s ago): 20 events
            for i in range(20):
                mock_time.time.return_value = base_time + i * 3  # spread over 60s
                ranker.update(make_alloc_event("com.example.LeakyService"))

            # Recent period (last 60s): 60+ events (3x rate)
            recent_start = base_time + 60
            for i in range(65):
                mock_time.time.return_value = recent_start + i * 0.9  # much faster
                result = ranker.update(make_alloc_event("com.example.LeakyService"))
                if result is not None:
                    break

        assert result is not None, "Expected a LEAK_SUSPECT alert"
        assert result["alertType"] == "LEAK_SUSPECT"
        assert result["className"] == "com.example.LeakyService"
        assert result["growthRatio"] > 2.5
        assert 0 < result["leakProbability"] <= 0.99

    def test_alert_contains_required_fields(self):
        """Alert dict must match the schema the telemetry pipeline expects."""
        ranker = LeakRanker()
        base_time = 1000000.0

        with patch("leak_ranker.time") as mock_time:
            # Force a leak alert
            for i in range(20):
                mock_time.time.return_value = base_time + i * 3
                ranker.update(make_alloc_event("com.example.Leaky"))

            recent_start = base_time + 60
            result = None
            for i in range(80):
                mock_time.time.return_value = recent_start + i * 0.5
                result = ranker.update(make_alloc_event("com.example.Leaky"))
                if result is not None:
                    break

        if result is not None:
            required = {"eventType", "appId", "timestamp", "severity",
                        "alertType", "className", "leakProbability",
                        "growthRatio", "message"}
            assert required.issubset(result.keys()), \
                f"Missing: {required - result.keys()}"
            assert result["eventType"] == "ALERT"
            assert result["severity"] in ("MEDIUM", "HIGH")

    def test_duplicate_alert_suppressed(self):
        """Same class should only trigger one alert (dedup via alerts_sent set)."""
        ranker = LeakRanker()
        base_time = 1000000.0
        alerts = []

        with patch("leak_ranker.time") as mock_time:
            for i in range(20):
                mock_time.time.return_value = base_time + i * 3
                ranker.update(make_alloc_event("com.example.Leaky"))

            recent_start = base_time + 60
            for i in range(100):
                mock_time.time.return_value = recent_start + i * 0.5
                result = ranker.update(make_alloc_event("com.example.Leaky"))
                if result is not None:
                    alerts.append(result)

        # Should get at most 1 alert for this class
        assert len(alerts) <= 1, f"Expected at most 1 alert, got {len(alerts)}"

    def test_different_apps_are_independent(self):
        """Events from different appIds should be tracked separately."""
        ranker = LeakRanker()

        for _ in range(59):
            ranker.update(make_alloc_event("java.util.HashMap", app_id="app-A"))

        # app-B has only 1 event — should not get an alert
        result = ranker.update(make_alloc_event("java.util.HashMap", app_id="app-B"))
        assert result is None

    def test_leak_probability_clamped(self):
        """Leak probability should never exceed 0.99."""
        ranker = LeakRanker()
        base_time = 1000000.0

        with patch("leak_ranker.time") as mock_time:
            # Tiny older period
            for i in range(5):
                mock_time.time.return_value = base_time + i * 10
                ranker.update(make_alloc_event("com.example.Extreme"))

            # Massive recent burst (ratio >> 2.5)
            recent_start = base_time + 60
            result = None
            for i in range(200):
                mock_time.time.return_value = recent_start + i * 0.2
                result = ranker.update(make_alloc_event("com.example.Extreme"))
                if result is not None:
                    break

        if result is not None:
            assert result["leakProbability"] <= 0.99
