import React, { useState } from 'react'
import HeapTrendChart from './components/HeapTrendChart'
import LeakSuspectTable from './components/LeakSuspectTable'
import GCPauseChart from './components/GCPauseChart'
import AllocationFlamegraph from './components/AllocationFlamegraph'
import './App.css'

/**
 * Root component for the JVM Observatory dashboard.
 *
 * React key concept for Java devs: "state" is like a mutable field that,
 * when changed via its setter (setAppId), automatically re-renders
 * the component and all children that depend on it.
 */
function App() {
  // useState returns [currentValue, setterFunction] — similar to a JavaFX Property
  const [appId, setAppId] = useState('demo-app')

  return (
    <div className="app">
      <header className="header">
        <h1>JVM Observatory</h1>
        <div className="app-id-input">
          <label htmlFor="appId">Application ID:</label>
          <input
            id="appId"
            type="text"
            value={appId}
            onChange={(e) => setAppId(e.target.value)}
            placeholder="e.g. payment-service-prod-1"
          />
        </div>
      </header>

      {/* Main dashboard — CSS grid arranges these into a 2x2 layout */}
      <main className="dashboard">
        <div className="card">
          <h2>Heap Usage Trend</h2>
          <HeapTrendChart appId={appId} />
        </div>
        <div className="card">
          <h2>GC Pause Duration</h2>
          <GCPauseChart appId={appId} />
        </div>
        <div className="card full-width">
          <h2>Leak Suspects</h2>
          <LeakSuspectTable appId={appId} />
        </div>
        <div className="card full-width">
          <h2>Allocation Hotspots</h2>
          <AllocationFlamegraph appId={appId} />
        </div>
      </main>
    </div>
  )
}

export default App
