import { createContext, useContext, useState, type ReactNode } from 'react'
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

  return <Ctx.Provider value={{ token, login, logout }}>{children}</Ctx.Provider>
}

export function useAuth(): AuthCtx {
  const c = useContext(Ctx)
  if (!c) throw new Error('useAuth deve ser usado dentro de <AuthProvider>')
  return c
}
