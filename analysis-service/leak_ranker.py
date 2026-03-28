"""
JVM Observatory — Leak Ranker

Heuristic-based leak detection: ranks classes by allocation growth ratio
to identify memory leak suspects.
"""

import time
from collections import defaultdict, deque
from datetime import datetime


class LeakRanker:
    """
    Heuristic-based leak detection using growth ratio scoring.

    IDEA: If a class's allocation rate in the last 60 seconds is
    significantly higher than the previous 60 seconds, it's probably
    leaking (accumulating objects faster than they're being collected).

    growth_ratio = recent_allocations / older_allocations

    If growth_ratio > 2.5: the class is allocating 2.5x faster than before.
    This is suspicious — normal steady-state workloads have ratio ~ 1.0.

    leak_probability is a simple linear scaling:
      prob = 0.5 + (growth_ratio - 2.5) * 0.1
      clamped to [0, 0.99]

    WHY not ML here: Leak detection is simpler than GC anomaly detection.
    A ratio > 2.5 is a strong signal. ML would add complexity without
    meaningfully improving detection quality for this metric.
    """

    def __init__(self):
        # history[app_id::className] = deque of (timestamp, count) tuples
        self.history: dict[str, deque] = defaultdict(lambda: deque(maxlen=120))
        self.alerts_sent: set[str] = set()  # avoid duplicate alerts per class

    def update(self, event: dict) -> dict | None:
        """
        Process an allocation event. Returns an alert if the class shows leak behavior.
        """
        app_id = event.get("appId", "unknown")
        class_name = event.get("className", "")
        if not class_name:
            return None

        key = f"{app_id}::{class_name}"
        now = time.time()
        self.history[key].append((now, 1))

        # Need at least 60 seconds of data
        if len(self.history[key]) < 60:
            return None

        # Count allocations in recent 60s vs previous 60s
        recent_cutoff = now - 60
        older_cutoff = now - 120

        recent = sum(1 for ts, _ in self.history[key] if ts >= recent_cutoff)
        older = sum(1 for ts, _ in self.history[key] if older_cutoff <= ts < recent_cutoff)

        if older == 0:
            return None  # no basis for comparison

        growth_ratio = recent / older

        if growth_ratio > 2.5 and key not in self.alerts_sent:
            leak_prob = min(0.99, 0.5 + (growth_ratio - 2.5) * 0.1)
            self.alerts_sent.add(key)

            return {
                "eventType": "ALERT",
                "appId": app_id,
                "timestamp": datetime.utcnow().isoformat() + "Z",
                "severity": "HIGH" if leak_prob > 0.8 else "MEDIUM",
                "alertType": "LEAK_SUSPECT",
                "className": class_name,
                "leakProbability": round(leak_prob, 2),
                "growthRatio": round(growth_ratio, 1),
                "message": (
                    f"{class_name} grew {growth_ratio:.1f}x in 60s. "
                    f"Leak probability: {leak_prob:.0%}"
                )
            }

        return None
