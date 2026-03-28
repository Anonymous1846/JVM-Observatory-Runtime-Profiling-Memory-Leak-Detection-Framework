/**
 * API client for the JVM Observatory Spring Boot backend.
 *
 * All requests go to /api/v1/* which Vite proxies to localhost:8080 in dev mode.
 * In production, the React build is served by Spring Boot so no proxy is needed.
 */
import axios from 'axios'

const BASE = '/api/v1'

export const fetchHeapTrend    = (appId) => axios.get(`${BASE}/heap-trend`, { params: { appId } })
export const fetchLeakSuspects = (appId) => axios.get(`${BASE}/leak-suspects`, { params: { appId } })
export const fetchGCStats      = (appId) => axios.get(`${BASE}/gc-stats`, { params: { appId } })
export const fetchAllocations  = (appId, topN = 20) => axios.get(`${BASE}/allocations`, { params: { appId, topN } })
export const fetchFlamegraph   = (appId) => axios.get(`${BASE}/allocation-flamegraph`, { params: { appId } })
