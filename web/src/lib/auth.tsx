import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { clearToken, getToken, setToken } from './api'

interface AuthCtx {
  token: string | null
  /** Empresa ativa da sessão (claim `eid`, docs/telas/login-empresa.md) — `null` até logar,
   *  ou se o token não tiver o claim (fluxos ainda sem empresa escolhida). */
  idEmpresa: number | null
  login: (t: string) => void
  logout: () => void
}

/** Decodifica só o payload do JWT (sem verificar assinatura — isso é papel do backend) para ler
 *  claims não sensíveis no front, como `eid`. `null` em qualquer formato inesperado. */
function decodificarPayload(token: string): Record<string, unknown> | null {
  try {
    const base64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    return JSON.parse(atob(base64))
  } catch {
    return null
  }
}

function idEmpresaDoToken(token: string | null): number | null {
  if (!token) return null
  const eid = decodificarPayload(token)?.eid
  return typeof eid === 'number' ? eid : null
}

const Ctx = createContext<AuthCtx | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setTok] = useState<string | null>(() => getToken())

  const login = (t: string) => {
    setToken(t)
    setTok(t)
  }
  const logout = () => {
    clearToken()
    setTok(null)
  }

  // Qualquer chamada à API que receber 401 dispara este evento (`clearToken`, `lib/api.ts`) —
  // sem isto, o estado em memória continuava com o token antigo e `RequireAuth` nunca mandava
  // de volta pro /login sozinho depois de uma sessão expirar (2026-08-06, bug real).
  useEffect(() => {
    const aoExpirar = () => setTok(null)
    window.addEventListener('niner:sessao-expirada', aoExpirar)
    return () => window.removeEventListener('niner:sessao-expirada', aoExpirar)
  }, [])

  return <Ctx.Provider value={{ token, idEmpresa: idEmpresaDoToken(token), login, logout }}>{children}</Ctx.Provider>
}

export function useAuth(): AuthCtx {
  const c = useContext(Ctx)
  if (!c) throw new Error('useAuth deve ser usado dentro de <AuthProvider>')
  return c
}
