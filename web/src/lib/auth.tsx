import { useQueryClient } from '@tanstack/react-query'
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
  const queryClient = useQueryClient()

  /**
   * ⛔ **Trocar de sessão APAGA o cache** (auditoria 2026-08-29).
   *
   * O `QueryClient` nasce uma vez em `main.tsx` e **sobrevive ao logout**, porque entrar e sair é
   * navegação de SPA, sem recarregar a página. Sem esta limpeza, o próximo usuário na mesma aba
   * era servido com o cache do anterior enquanto o refetch não voltava: um lojista que troca de
   * conta via a lista de **clientes do outro tenant** — nome, CPF, limite de crédito — pintada na
   * tela. E na troca de turno do balcão, `['minhas-permissoes']` tem `staleTime` de 1 minuto,
   * então o menu do operador era montado por até um minuto com a grade da ADMINISTRADORA,
   * oferecendo telas que o servidor ia recusar.
   *
   * ⚠️ Vale também para o logoff automático — 401 e horário de acesso —, que entram pelo mesmo
   * caminho, e não só pelo botão Sair.
   */
  const limparSessao = () => {
    setTok(null)
    queryClient.clear()
  }

  const login = (t: string) => {
    setToken(t)
    // O cache do dono anterior da aba não pode sobreviver à entrada de outro.
    queryClient.clear()
    setTok(t)
  }
  const logout = () => {
    clearToken()
    limparSessao()
  }

  // Qualquer chamada à API que receber 401 dispara este evento (`clearToken`, `lib/api.ts`) —
  // sem isto, o estado em memória continuava com o token antigo e `RequireAuth` nunca mandava
  // de volta pro /login sozinho depois de uma sessão expirar (2026-08-06, bug real).
  useEffect(() => {
    const aoExpirar = () => limparSessao()
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
