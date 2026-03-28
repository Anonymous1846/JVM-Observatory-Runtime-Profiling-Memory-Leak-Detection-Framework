"""
JVM Observatory — Heap Anomaly Detector

Two-stage anomaly detection for GC events:
  Stage 1: IsolationForest flags unusual GC behavior
  Stage 2: Linear trend predicts time-to-OOM
"""

import numpy as np
from collections import defaultdict, deque
from datetime import datetime
from sklearn.ensemble import IsolationForest


class HeapAnomalyDetector:
    """
    Two-stage anomaly detection:

    Stage 1: IsolationForest (unsupervised anomaly detection)
      - Trained on recent GC events (heap usage, pause duration, reclaimed bytes)
      - Flags GC events that look "unusual" compared to recent history
      - contamination=0.05 means we expect ~5% of events to be anomalous

    WHY IsolationForest over other algorithms:
      - Works without labels (unsupervised — we don't know what "normal" looks like upfront)
      - Trains in milliseconds (fast enough to retrain every 10 events)
      - Handles multivariate data (we feed 3 features per event)
      - Good at detecting point anomalies (single unusual GC events)

    Stage 2: Linear trend (OOM prediction)
      - Fits a line to heap_used_after values over time
      - Extrapolates to estimate when heap hits 95% of max
      - Gives a "steps to OOM" estimate for severity ranking

    WHY linear regression for OOM:
      - Memory leaks cause LINEAR heap growth (each iteration adds N bytes)
      - A simple slope gives us the growth rate
      - Extrapolation to 95% threshold gives time-to-OOM
    """

    def __init__(self, window: int = 60):
        self.window = window  # max samples per app_id
        self.samples: dict[str, deque] = defaultdict(lambda: deque(maxlen=window))
        self.models: dict[str, IsolationForest] = {}  # app_id -> trained IsolationForest
        self._event_count: dict[str, int] = defaultdict(int)  # for retraining cadence

    def analyze(self, gc_event: dict) -> dict | None:
        """
        Process a GC event. Returns an alert dict if anomalous, None otherwise.

        ISSUE 8 FIX: Requires at least 30 samples before analysis.
        IsolationForest with fewer samples gives random results because
        the tree splits have too little data to establish a meaningful
        "normal" region.
        """
        app_id = gc_event.get("appId", "unknown")

        heap_used_after = gc_event.get("heapUsedAfter", 0)
        duration_ms = gc_event.get("durationMs", 0)
        heap_used_before = gc_event.get("heapUsedBefore", 0)
        reclaimed = heap_used_before - heap_used_after

        sample = [heap_used_after, duration_ms, reclaimed]
        self.samples[app_id].append(sample)
        self._event_count[app_id] += 1

        # Guard: need at least 30 samples for meaningful analysis
        if len(self.samples[app_id]) < 30:
            return None

        # Retrain every 10 events (balance freshness vs CPU cost)
        if self._event_count[app_id] % 10 == 0:
            X = np.array(list(self.samples[app_id]))
            self.models[app_id] = IsolationForest(
                contamination=0.05,  # expect 5% anomalies
                random_state=42,     # reproducible results
                n_estimators=100     # 100 trees (default, good balance)
            )
            self.models[app_id].fit(X)

        model = self.models.get(app_id)
        if model is None:
            return None

        # Score current sample: -1 = anomaly, 1 = normal
        score = model.predict(np.array([sample]))[0]

        if score == -1:
            # Anomaly detected — predict OOM via linear trend
            heap_trend = [s[0] for s in self.samples[app_id]]  # heap_used_after values
            time_steps = np.arange(len(heap_trend))

            slope, intercept = np.polyfit(time_steps, heap_trend, 1)

            severity = "MEDIUM"
            steps_to_oom = float('inf')

            if slope > 0:
                # Estimate: at this growth rate, how many steps until 95% of max heap?
                # We approximate max_heap from the highest observed value x 1.5
                # (since we don't have max_heap in the GC event directly)
                estimated_max = max(heap_trend) * 1.5
                target = 0.95 * estimated_max
                steps_to_oom = (target - intercept) / slope

                if steps_to_oom < 60:
                    severity = "HIGH"  # OOM predicted within 60 GC cycles

            return {
                "eventType": "ALERT",
                "appId": app_id,
                "timestamp": datetime.utcnow().isoformat() + "Z",
                "severity": severity,
                "alertType": "GC_ANOMALY",
                "className": "",
                "leakProbability": 0.0,
                "growthRatio": round(slope, 2),
                "message": (
                    f"GC anomaly detected. Heap growth rate: {slope:.0f} bytes/cycle. "
                    f"Estimated {steps_to_oom:.0f} cycles to OOM."
                    if steps_to_oom != float('inf')
                    else f"GC anomaly detected. Heap growth rate: {slope:.0f} bytes/cycle."
                )
            }

        return None
