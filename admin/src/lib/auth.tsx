import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, clearToken, getToken, setToken } from './api'

/** Papéis do staff (R18). O menu e as ações se adaptam a eles. */
export type PapelStaff = 'SUPER_ADMIN' | 'SUPORTE' | 'FINANCEIRO'

interface Sessao {
  token: string
  nome: string
  email: string
  papel: PapelStaff
}

interface Contexto {
  sessao: Sessao | null
  entrar: (email: string, senha: string) => Promise<void>
  sair: () => void
  ehSuperAdmin: boolean
}

const AuthContext = createContext<Contexto | null>(null)
const CHAVE_PERFIL = 'niner_staff_perfil'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [sessao, setSessao] = useState<Sessao | null>(() => {
    const token = getToken()
    const perfil = localStorage.getItem(CHAVE_PERFIL)
    return token && perfil ? { token, ...JSON.parse(perfil) } : null
  })

  // A sessão pode morrer fora do React (um 401 em qualquer chamada). Sem ouvir isso, a tela
  // continuaria "logada" e falhando a cada clique.
  useEffect(() => {
    const aoExpirar = () => {
      localStorage.removeItem(CHAVE_PERFIL)
      setSessao(null)
    }
    window.addEventListener('niner:sessao-staff-expirada', aoExpirar)
    return () => window.removeEventListener('niner:sessao-staff-expirada', aoExpirar)
  }, [])

  const entrar = useCallback(async (email: string, senha: string) => {
    const r = await api<Sessao>('/api/admin/sessao', {
      method: 'POST',
      body: JSON.stringify({ email, senha }),
    })
    setToken(r.token)
    localStorage.setItem(CHAVE_PERFIL, JSON.stringify({ nome: r.nome, email: r.email, papel: r.papel }))
    setSessao(r)
  }, [])

  const sair = useCallback(() => {
    localStorage.removeItem(CHAVE_PERFIL)
    clearToken()
    setSessao(null)
  }, [])

  const valor = useMemo(
    () => ({ sessao, entrar, sair, ehSuperAdmin: sessao?.papel === 'SUPER_ADMIN' }),
    [sessao, entrar, sair],
  )
  return <AuthContext.Provider value={valor}>{children}</AuthContext.Provider>
}

export function useAuth(): Contexto {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth fora do AuthProvider')
  return ctx
}
