import React, { useState, useEffect, useCallback } from 'react'
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid,
  Tooltip, ReferenceLine, ResponsiveContainer
} from 'recharts'
import { fetchHeapTrend } from '../api'
import { format, parseISO } from 'date-fns'

/**
 * Real-time heap usage chart that polls the backend every 5 seconds.
 *
 * For Java devs: this is a "functional component" — just a function that
 * returns JSX (HTML-like syntax). Hooks (useState, useEffect, useCallback)
 * give it state and lifecycle behavior without needing a class.
 */
function HeapTrendChart({ appId }) {
  // State: array of chart data points and the max heap size
  const [data, setData] = useState([])
  const [maxHeap, setMaxHeap] = useState(0)

  // ISSUE 11 FIX: useCallback stabilizes the fetch function reference.
  // Without it, a new function object is created every render, causing
  // useEffect to re-run its cleanup/setup cycle unnecessarily.
  const loadData = useCallback(async () => {
    if (!appId) return
    try {
      const res = await fetchHeapTrend(appId)
      const samples = res.data || []
      // ISSUE 12 FIX: handle empty response gracefully — only update state
      // if we actually got data, otherwise leave previous data intact
      if (samples.length > 0) {
        const formatted = samples.map(s => ({
          time: format(parseISO(s.timestamp), 'HH:mm:ss'),
          heapMB: +(s.usedBytes / 1024 / 1024).toFixed(1),
          maxMB: +(s.maxBytes / 1024 / 1024).toFixed(1),
        }))
        setData(formatted)
        setMaxHeap(formatted[0]?.maxMB || 0)
      }
    } catch (err) {
      console.error('Failed to fetch heap trend:', err)
    }
  }, [appId]) // Re-create this function only when appId changes

  // useEffect = lifecycle hook. Runs when loadData changes (i.e., when appId changes).
  // The returned function is the cleanup — like a finally block that clears the interval.
  useEffect(() => {
    loadData()
    const interval = setInterval(loadData, 5000) // poll every 5s
    return () => clearInterval(interval) // cleanup on unmount or appId change
  }, [loadData])

  // ISSUE 12 FIX: show a helpful placeholder when there's no data yet
  if (data.length === 0) {
    return <p className="placeholder">No heap data available. Is the target app running?</p>
  }

  // Draw a red dashed line at 85% of max heap to warn about OOM risk
  const oomThreshold = maxHeap * 0.85

  return (
    <ResponsiveContainer width="100%" height={300}>
      <LineChart data={data}>
        <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
        <XAxis dataKey="time" stroke="#94a3b8" fontSize={12} />
        <YAxis
          stroke="#94a3b8"
          fontSize={12}
          label={{ value: 'MB', position: 'insideLeft' }}
        />
        <Tooltip
          contentStyle={{ background: '#1e293b', border: '1px solid #334155' }}
        />
        <ReferenceLine
          y={oomThreshold}
          stroke="#ef4444"
          strokeDasharray="5 5"
          label={{ value: 'OOM Risk 85%', fill: '#ef4444', fontSize: 11 }}
        />
        <Line
          type="monotone"
          dataKey="heapMB"
          stroke="#3b82f6"
          strokeWidth={2}
          dot={false}
          name="Heap Used (MB)"
        />
      </LineChart>
    </ResponsiveContainer>
  )
}

export default HeapTrendChart
