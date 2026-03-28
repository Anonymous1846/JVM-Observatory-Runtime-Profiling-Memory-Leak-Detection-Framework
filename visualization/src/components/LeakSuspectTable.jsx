import React, { useState, useEffect, useCallback } from 'react'
import { fetchLeakSuspects } from '../api'

/**
 * Table showing classes suspected of memory leaks, ranked by growth ratio.
 *
 * For Java devs: This component polls the backend every 5 seconds and
 * re-renders the table automatically when new data arrives. Think of
 * useState as an Observable<T> that triggers a UI refresh on change.
 */
function LeakSuspectTable({ appId }) {
  const [suspects, setSuspects] = useState([])

  // ISSUE 11 FIX: useCallback prevents unnecessary re-fetches
  const loadData = useCallback(async () => {
    if (!appId) return
    try {
      const res = await fetchLeakSuspects(appId)
      const data = res.data || []
      if (data.length > 0) {
        setSuspects(data)
      }
    } catch (err) {
      console.error('Failed to fetch leak suspects:', err)
    }
  }, [appId])

  useEffect(() => {
    loadData()
    const interval = setInterval(loadData, 5000)
    return () => clearInterval(interval)
  }, [loadData])

  // ISSUE 12 FIX: show placeholder when no suspects are detected
  if (suspects.length === 0) {
    return <p className="placeholder">No leak suspects detected yet. Keep monitoring.</p>
  }

  /**
   * Returns a CSS color based on the leak probability value.
   * Red (> 0.8) = high confidence leak, amber (> 0.6) = moderate, green = low.
   */
  const probColor = (prob) => {
    if (prob > 0.8) return '#ef4444'  // --danger
    if (prob > 0.6) return '#f59e0b'  // --warning
    return '#22c55e'                   // --success
  }

  /**
   * Returns a CSS class name for the severity badge.
   */
  const severityClass = (severity) => {
    switch (severity?.toUpperCase()) {
      case 'HIGH':   return 'badge high'
      case 'MEDIUM': return 'badge medium'
      default:       return 'badge low'
    }
  }

  return (
    <table className="leak-table">
      <thead>
        <tr>
          <th>Class</th>
          <th>Leak Probability</th>
          <th>Growth Ratio</th>
          <th>Severity</th>
          <th>Details</th>
        </tr>
      </thead>
      <tbody>
        {/* .map() is React's way of looping — like a Stream.map() in Java */}
        {suspects.map((s, idx) => (
          <tr key={s.className || idx}>
            <td className="class-name">{s.className}</td>
            <td>
              {/* Progress bar showing leak probability as a visual indicator */}
              <div className="prob-bar-container">
                <div
                  className="prob-bar-fill"
                  style={{
                    width: `${(s.leakProbability || 0) * 100}%`,
                    backgroundColor: probColor(s.leakProbability || 0)
                  }}
                />
              </div>
              <span style={{ fontSize: '0.75rem', color: '#94a3b8' }}>
                {((s.leakProbability || 0) * 100).toFixed(0)}%
              </span>
            </td>
            <td>{(s.growthRatio || 0).toFixed(1)}x</td>
            <td>
              <span className={severityClass(s.severity)}>
                {s.severity}
              </span>
            </td>
            <td style={{ fontSize: '0.8125rem', color: '#94a3b8' }}>
              {s.message}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

export default LeakSuspectTable
