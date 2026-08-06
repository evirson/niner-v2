import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { clearToken, getToken, setToken } from './api'

interface AuthCtx {
  token: string | null
  login: (t: string) => void
  logout: () => void
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

  return <Ctx.Provider value={{ token, login, logout }}>{children}</Ctx.Provider>
}

export function useAuth(): AuthCtx {
  const c = useContext(Ctx)
  if (!c) throw new Error('useAuth deve ser usado dentro de <AuthProvider>')
  return c
}
