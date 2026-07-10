// Base-URL da API lida em runtime (public/config.js), com fallback de dev.
declare global {
  interface Window {
    NINER_API_BASE?: string
  }
}

export const API_BASE: string = window.NINER_API_BASE ?? 'http://localhost:8080'
