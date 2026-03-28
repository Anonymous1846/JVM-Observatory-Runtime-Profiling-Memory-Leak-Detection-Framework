import { describe, it, expect } from 'vitest'

/**
 * Tests for the causeToColor utility function from GCPauseChart.
 *
 * We extract and test the logic independently — like testing a static
 * utility method in Java without needing the full Spring context.
 */

// Re-implement the function here since it's not exported from the component.
// In a real refactor, you'd extract it to a utils file and import it.
const CAUSE_COLORS = [
  '#3b82f6', '#f59e0b', '#22c55e', '#ef4444',
  '#8b5cf6', '#06b6d4', '#f97316', '#ec4899',
]

function causeToColor(cause) {
  let hash = 0
  for (let i = 0; i < (cause || '').length; i++) {
    hash = cause.charCodeAt(i) + ((hash << 5) - hash)
  }
  return CAUSE_COLORS[Math.abs(hash) % CAUSE_COLORS.length]
}

describe('causeToColor', () => {
  it('returns a valid color from the palette', () => {
    const color = causeToColor('Allocation Failure')
    expect(CAUSE_COLORS).toContain(color)
  })

  it('returns the same color for the same cause (deterministic)', () => {
    const a = causeToColor('System.gc()')
    const b = causeToColor('System.gc()')
    expect(a).toBe(b)
  })

  it('handles null/undefined cause gracefully', () => {
    const color = causeToColor(null)
    expect(CAUSE_COLORS).toContain(color)
  })

  it('handles empty string', () => {
    const color = causeToColor('')
    expect(CAUSE_COLORS).toContain(color)
  })

  it('different causes can map to different colors', () => {
    // Not guaranteed for all pairs, but these specific strings differ
    const colors = new Set([
      causeToColor('Allocation Failure'),
      causeToColor('G1 Humongous Allocation'),
      causeToColor('System.gc()'),
      causeToColor('Metadata GC Threshold'),
    ])
    // With 4 distinct inputs and 8 colors, we expect at least 2 distinct colors
    expect(colors.size).toBeGreaterThanOrEqual(2)
  })
})
