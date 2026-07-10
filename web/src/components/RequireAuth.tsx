import { Navigate, Outlet } from 'react-router-dom'
import { useAuth } from '../lib/auth'

/** Protege as rotas do ERP: sem token → vai para o login. */
export default function RequireAuth() {
  const { token } = useAuth()
  return token ? <Outlet /> : <Navigate to="/login" replace />
}
