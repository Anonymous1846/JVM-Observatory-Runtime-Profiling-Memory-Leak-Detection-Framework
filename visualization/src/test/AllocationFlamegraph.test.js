import { describe, it, expect } from 'vitest'

/**
 * Tests for the utility functions in AllocationFlamegraph.
 *
 * topLevelPackage and flattenTree are pure functions — perfect for unit testing
 * without any DOM rendering, like testing a Java utility class.
 */

// Re-implemented from AllocationFlamegraph.jsx (not exported)
function topLevelPackage(className) {
  if (!className) return 'unknown'
  const dot = className.indexOf('.')
  return dot > 0 ? className.substring(0, dot) : className
}

const PACKAGE_COLORS = [
  '#3b82f6', '#8b5cf6', '#06b6d4', '#22c55e',
  '#f59e0b', '#ef4444', '#ec4899', '#f97316',
]

function packageColor(className) {
  const pkg = topLevelPackage(className)
  let hash = 0
  for (let i = 0; i < pkg.length; i++) {
    hash = pkg.charCodeAt(i) + ((hash << 5) - hash)
  }
  return PACKAGE_COLORS[Math.abs(hash) % PACKAGE_COLORS.length]
}

function flattenTree(node) {
  const result = []
  function walk(n) {
    if (!n) return
    if ((n.count || n.size || n.value) && n.name) {
      result.push({
        name: n.name,
        size: n.count || n.size || n.value || 0,
        fill: packageColor(n.name),
      })
    }
    if (Array.isArray(n.children)) {
      n.children.forEach(walk)
    }
  }
  if (Array.isArray(node)) {
    node.forEach(walk)
  } else {
    walk(node)
  }
  return result
}

describe('topLevelPackage', () => {
  it('extracts top-level package from fully qualified name', () => {
    expect(topLevelPackage('java.util.HashMap')).toBe('java')
  })

  it('extracts from deep package', () => {
    expect(topLevelPackage('com.jvmobservatory.agent.NativeEventBridge')).toBe('com')
  })

  it('returns the class itself if no dots', () => {
    expect(topLevelPackage('HashMap')).toBe('HashMap')
  })

  it('returns "unknown" for null/undefined', () => {
    expect(topLevelPackage(null)).toBe('unknown')
    expect(topLevelPackage(undefined)).toBe('unknown')
    expect(topLevelPackage('')).toBe('unknown')
  })
})

describe('flattenTree', () => {
  it('flattens a nested tree structure', () => {
    const tree = {
      name: 'root',
      children: [
        { name: 'java.util.HashMap', count: 500 },
        { name: 'java.lang.String', count: 300 },
      ],
    }
    const result = flattenTree(tree)
    expect(result).toHaveLength(2)
    expect(result[0].name).toBe('java.util.HashMap')
    expect(result[0].size).toBe(500)
    expect(result[1].name).toBe('java.lang.String')
    expect(result[1].size).toBe(300)
  })

  it('handles flat array input', () => {
    const data = [
      { name: 'java.util.ArrayList', count: 100 },
      { name: 'com.example.Foo', size: 200 },
    ]
    const result = flattenTree(data)
    expect(result).toHaveLength(2)
    expect(result[0].size).toBe(100)
    expect(result[1].size).toBe(200)
  })

  it('returns empty array for null input', () => {
    expect(flattenTree(null)).toEqual([])
  })

  it('skips nodes without a name', () => {
    const data = [
      { count: 100 },  // no name
      { name: 'java.util.HashMap', count: 500 },
    ]
    const result = flattenTree(data)
    expect(result).toHaveLength(1)
    expect(result[0].name).toBe('java.util.HashMap')
  })

  it('skips nodes without count/size/value', () => {
    const data = [
      { name: 'java.util.HashMap' },  // no count
      { name: 'java.lang.String', count: 300 },
    ]
    const result = flattenTree(data)
    expect(result).toHaveLength(1)
  })

  it('assigns fill colors based on package', () => {
    const data = [{ name: 'java.util.HashMap', count: 500 }]
    const result = flattenTree(data)
    expect(result[0].fill).toBeDefined()
    expect(PACKAGE_COLORS).toContain(result[0].fill)
  })

  it('handles deeply nested trees', () => {
    const tree = {
      name: 'root',
      children: [
        {
          name: 'group',
          children: [
            { name: 'java.util.HashMap', count: 100 },
            { name: 'java.util.ArrayList', count: 200 },
          ],
        },
      ],
    }
    const result = flattenTree(tree)
    expect(result).toHaveLength(2)
  })
})
