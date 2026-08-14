import { Navigate, Outlet } from 'react-router-dom'
import { useAuth } from '../lib/auth'
import { RotinaCriticaProvider } from '../lib/rotinaCritica'
import HorarioAcessoGuard from './HorarioAcessoGuard'

/** Protege as rotas do ERP: sem token → vai para o login. Também liga o guard de horário de
 *  acesso (docs/telas/usuario.md, 2026-08-11) — escopo só das rotas autenticadas. */
export default function RequireAuth() {
  const { token } = useAuth()
  if (!token) return <Navigate to="/login" replace />
  return (
    <RotinaCriticaProvider>
      <HorarioAcessoGuard />
      <Outlet />
    </RotinaCriticaProvider>
  )
}
