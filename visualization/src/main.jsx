import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'

// createRoot is React 18's way of mounting (replaces ReactDOM.render from React 17).
// StrictMode enables extra development warnings — it does NOT affect production builds.
ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
)
