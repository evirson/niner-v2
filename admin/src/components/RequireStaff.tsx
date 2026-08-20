import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../lib/auth'

/** Sem sessão de staff, volta para o login guardando para onde a pessoa queria ir. */
export default function RequireStaff() {
  const { sessao } = useAuth()
  const local = useLocation()
  if (!sessao) {
    return <Navigate to="/entrar" replace state={{ de: local.pathname }} />
  }
  return <Outlet />
}
