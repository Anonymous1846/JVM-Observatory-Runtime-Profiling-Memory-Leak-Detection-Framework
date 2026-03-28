import React, { useState, useEffect, useCallback, useMemo } from 'react'
import { Treemap, ResponsiveContainer, Tooltip } from 'recharts'
import { fetchFlamegraph } from '../api'

/**
 * Allocation hotspot visualization using a Treemap (space-filling chart).
 *
 * For Java devs: Think of this as a visual "top allocators" view.
 * Each rectangle's SIZE represents how many objects that class/method allocated.
 * Larger rectangles = more allocations = potential optimization targets.
 *
 * We use a Treemap instead of a true flamegraph because:
 * 1. recharts has a built-in Treemap component
 * 2. It effectively shows relative allocation volumes
 * 3. It works well in a dashboard layout
 */

// Color palette for top-level packages — each package gets a distinct hue
const PACKAGE_COLORS = [
  '#3b82f6', // blue    — java.*
  '#8b5cf6', // purple  — javax.*
  '#06b6d4', // cyan    — org.*
  '#22c55e', // green   — com.*
  '#f59e0b', // amber   — io.*
  '#ef4444', // red     — net.*
  '#ec4899', // pink    — scala.*
  '#f97316', // orange  — other
]

/**
 * Extract the top-level package from a fully qualified class name.
 * e.g., "java.util.HashMap" -> "java"
 */
function topLevelPackage(className) {
  if (!className) return 'unknown'
  const dot = className.indexOf('.')
  return dot > 0 ? className.substring(0, dot) : className
}

/**
 * Assign a color to a class based on its top-level package.
 */
function packageColor(className) {
  const pkg = topLevelPackage(className)
  let hash = 0
  for (let i = 0; i < pkg.length; i++) {
    hash = pkg.charCodeAt(i) + ((hash << 5) - hash)
  }
  return PACKAGE_COLORS[Math.abs(hash) % PACKAGE_COLORS.length]
}

/**
 * Flatten a tree structure into a flat array for Recharts Treemap.
 *
 * The backend may return nested data like:
 *   { name: "root", children: [{ name: "java.util.HashMap", count: 500 }, ...] }
 *
 * Treemap needs: [{ name, size, fill }, ...]
 */
function flattenTree(node) {
  const result = []

  function walk(n) {
    if (!n) return
    // If this node has a count/size and a name, it's a leaf or has its own value
    if ((n.count || n.size || n.value) && n.name) {
      result.push({
        name: n.name,
        size: n.count || n.size || n.value || 0,
        fill: packageColor(n.name),
      })
    }
    // Recurse into children
    if (Array.isArray(n.children)) {
      n.children.forEach(walk)
    }
  }

  // Handle both array and object inputs
  if (Array.isArray(node)) {
    node.forEach(walk)
  } else {
    walk(node)
  }

  return result
}

function AllocationFlamegraph({ appId }) {
  const [rawData, setRawData] = useState(null)

  const loadData = useCallback(async () => {
    if (!appId) return
    try {
      const res = await fetchFlamegraph(appId)
      const data = res.data
      if (data) {
        setRawData(data)
      }
    } catch (err) {
      console.error('Failed to fetch allocation flamegraph:', err)
    }
  }, [appId])

  useEffect(() => {
    loadData()
    // Poll every 10 seconds (allocation data changes more slowly than GC events)
    const interval = setInterval(loadData, 10000)
    return () => clearInterval(interval)
  }, [loadData])

  // useMemo: only re-flatten when rawData changes (avoids recalculating every render).
  // Similar to a computed/derived property in Java.
  const treemapData = useMemo(() => {
    if (!rawData) return []
    return flattenTree(rawData)
  }, [rawData])

  // ISSUE 12 FIX: placeholder when no allocation data is available
  if (treemapData.length === 0) {
    return (
      <p className="placeholder">
        No allocation data available. Ensure the JVMTI agent is attached and tracking allocations.
      </p>
    )
  }

  // Custom tooltip for the treemap cells
  const CustomTooltip = ({ active, payload }) => {
    if (!active || !payload || payload.length === 0) return null
    const d = payload[0].payload
    return (
      <div style={{
        background: '#1e293b',
        border: '1px solid #334155',
        borderRadius: '6px',
        padding: '0.5rem 0.75rem',
        fontSize: '0.8125rem',
      }}>
        <div style={{ fontFamily: 'monospace' }}>{d.name}</div>
        <div style={{ color: '#3b82f6' }}>
          Allocations: {(d.size || 0).toLocaleString()}
        </div>
      </div>
    )
  }

  // Custom content renderer for treemap cells — shows the class name inside each rect
  const CustomCell = (props) => {
    const { x, y, width, height, name, fill } = props
    // Only show text if the cell is big enough
    const showText = width > 60 && height > 20
    return (
      <g>
        <rect
          x={x}
          y={y}
          width={width}
          height={height}
          fill={fill}
          stroke="#0f172a"
          strokeWidth={2}
          rx={3}
        />
        {showText && (
          <text
            x={x + width / 2}
            y={y + height / 2}
            textAnchor="middle"
            dominantBaseline="middle"
            fill="#fff"
            fontSize={11}
            style={{ pointerEvents: 'none' }}
          >
            {/* Truncate long class names to fit the cell */}
            {name && name.length > width / 7
              ? name.substring(name.lastIndexOf('.') + 1)
              : name}
          </text>
        )}
      </g>
    )
  }

  return (
    <div className="flamegraph-container">
      <ResponsiveContainer width="100%" height={300}>
        <Treemap
          data={treemapData}
          dataKey="size"
          nameKey="name"
          stroke="#0f172a"
          content={<CustomCell />}
        >
          <Tooltip content={<CustomTooltip />} />
        </Treemap>
      </ResponsiveContainer>
    </div>
  )
}

export default AllocationFlamegraph
