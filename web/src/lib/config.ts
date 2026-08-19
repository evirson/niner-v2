// Endereços lidos em RUNTIME (public/config.js), com fallback de dev — nunca embutidos no
// bundle (spec §3.1), para trocar o endereço em manutenção/failover sem rebuild.
declare global {
  interface Window {
    NINER_API_BASE?: string
    NINER_SITE_BASE?: string
  }
}

export const API_BASE: string = window.NINER_API_BASE ?? 'http://localhost:8080'

/** Site público — usado no link "criar conta" do login. Antes estava `http://localhost:5175`
 *  escrito no componente, o que em produção virava um link morto (achado no deploy, 2026-08-19). */
export const SITE_BASE: string = window.NINER_SITE_BASE ?? 'http://localhost:5175'
