import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'

/**
 * Tests for the LeakSuspectTable component rendering.
 *
 * We mock the API module so no real HTTP calls are made —
 * like using Mockito.when() in Spring Boot tests.
 */

// Mock the api module before importing the component
vi.mock('../api', () => ({
  fetchLeakSuspects: vi.fn(() => Promise.resolve({ data: [] })),
}))

import LeakSuspectTable from '../components/LeakSuspectTable'

describe('LeakSuspectTable', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows placeholder when no data', () => {
    render(<LeakSuspectTable appId="demo-app" />)
    expect(screen.getByText(/no leak suspects detected/i)).toBeInTheDocument()
  })
})
