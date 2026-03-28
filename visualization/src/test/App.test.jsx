import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import App from '../App'

/**
 * Tests for the root App component.
 *
 * For Java devs: render() is like instantiating the component.
 * screen.getByText() is like Selenium's findElement(By.text(...)).
 */

describe('App', () => {
  it('renders the header title', () => {
    render(<App />)
    expect(screen.getByText('JVM Observatory')).toBeInTheDocument()
  })

  it('renders the Application ID input with default value', () => {
    render(<App />)
    const input = screen.getByLabelText('Application ID:')
    expect(input).toBeInTheDocument()
    expect(input.value).toBe('demo-app')
  })

  it('updates appId when input changes', () => {
    render(<App />)
    const input = screen.getByLabelText('Application ID:')
    fireEvent.change(input, { target: { value: 'payment-service' } })
    expect(input.value).toBe('payment-service')
  })

  it('renders all four dashboard sections', () => {
    render(<App />)
    expect(screen.getByText('Heap Usage Trend')).toBeInTheDocument()
    expect(screen.getByText('GC Pause Duration')).toBeInTheDocument()
    expect(screen.getByText('Leak Suspects')).toBeInTheDocument()
    expect(screen.getByText('Allocation Hotspots')).toBeInTheDocument()
  })
})
