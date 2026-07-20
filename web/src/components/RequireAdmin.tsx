import { Navigate, Outlet } from 'react-router-dom'
import { useEu } from '../lib/eu'

/** Protege telas restritas a ADMIN (ex.: configuração de tela) — OPERADOR volta pro Painel. */
export default function RequireAdmin() {
  const { data, isLoading } = useEu()

  if (isLoading) return <p className="muted">Carregando…</p>
  if (data?.usuario.papel !== 'ADMIN') return <Navigate to="/" replace />
  return <Outlet />
}
