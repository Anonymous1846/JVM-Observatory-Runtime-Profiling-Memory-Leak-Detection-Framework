import React, { useState, useEffect, useCallback, useMemo } from 'react'
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid,
  Tooltip, ResponsiveContainer, Cell
} from 'recharts'
import { fetchGCStats } from '../api'
import { format, parseISO } from 'date-fns'

/**
 * Bar chart showing GC pause durations over time, colored by GC cause.
 *
 * For Java devs: Each bar represents one GC event. The color indicates
 * the cause (e.g., Allocation Failure, System.gc(), G1 Humongous Allocation).
 * Long bars = long stop-the-world pauses = bad for latency.
 */

// Color palette for different GC causes — deterministic mapping via hash
const CAUSE_COLORS = [
  '#3b82f6', // blue
  '#f59e0b', // amber
  '#22c55e', // green
  '#ef4444', // red
  '#8b5cf6', // purple
  '#06b6d4', // cyan
  '#f97316', // orange
  '#ec4899', // pink
]

/**
 * Simple string hash to consistently assign colors to GC cause strings.
 * Same cause always gets the same color across renders.
 */
function causeToColor(cause) {
  let hash = 0
  for (let i = 0; i < (cause || '').length; i++) {
    hash = cause.charCodeAt(i) + ((hash << 5) - hash)
  }
  return CAUSE_COLORS[Math.abs(hash) % CAUSE_COLORS.length]
}

function GCPauseChart({ appId }) {
  const [data, setData] = useState([])

  const loadData = useCallback(async () => {
    if (!appId) return
    try {
      const res = await fetchGCStats(appId)
      const stats = res.data || {}
      const events = stats.events || stats || []

      // Normalize: backend may return { events: [...] } or just [...]
      const eventList = Array.isArray(events) ? events : []

      if (eventList.length > 0) {
        const formatted = eventList.map((e, idx) => ({
          time: e.timestamp
            ? format(parseISO(e.timestamp), 'HH:mm:ss')
            : `#${idx}`,
          durationMs: e.durationMs || 0,
          gcName: e.gcName || 'Unknown',
          gcCause: e.gcCause || 'Unknown',
        }))
        setData(formatted)
      }
    } catch (err) {
      console.error('Failed to fetch GC stats:', err)
    }
  }, [appId])

  useEffect(() => {
    loadData()
    const interval = setInterval(loadData, 5000)
    return () => clearInterval(interval)
  }, [loadData])

  // ISSUE 12 FIX: placeholder when no GC data is available
  if (data.length === 0) {
    return <p className="placeholder">No GC events recorded yet. Waiting for garbage collections...</p>
  }

  // Custom tooltip showing GC name, cause, and duration
  const CustomTooltip = ({ active, payload }) => {
    if (!active || !payload || payload.length === 0) return null
    const d = payload[0].payload
    return (
      <div style={{
        background: '#1e293b',
        border: '1px solid #334155',
        borderRadius: '6px',
        padding: '0.5rem 0.75rem',
        fontSize: '0.8125rem'
      }}>
        <div><strong>{d.gcName}</strong></div>
        <div style={{ color: '#94a3b8' }}>Cause: {d.gcCause}</div>
        <div style={{ color: '#3b82f6' }}>Duration: {d.durationMs} ms</div>
      </div>
    )
  }

  return (
    <ResponsiveContainer width="100%" height={300}>
      <BarChart data={data}>
        <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
        <XAxis dataKey="time" stroke="#94a3b8" fontSize={12} />
        <YAxis
          stroke="#94a3b8"
          fontSize={12}
          label={{ value: 'ms', position: 'insideLeft' }}
        />
        <Tooltip content={<CustomTooltip />} />
        <Bar dataKey="durationMs" name="GC Pause (ms)" radius={[3, 3, 0, 0]}>
          {/* Color each bar based on its GC cause */}
          {data.map((entry, index) => (
            <Cell key={index} fill={causeToColor(entry.gcCause)} />
          ))}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  )
}

export default GCPauseChart
