import { createContext, useContext, useState, type ReactNode } from 'react'

interface RotinaCriticaCtx {
  emAndamento: boolean
  setEmAndamento: (v: boolean) => void
}

const Ctx = createContext<RotinaCriticaCtx | null>(null)

/**
 * Flag compartilhada, escopo das rotas autenticadas: `true` enquanto o usuário está no meio de
 * uma rotina que não pode ser interrompida (ex.: finalizando uma venda no PDV). O logoff
 * automático por fim de horário de acesso (`HorarioAcessoGuard`, docs/telas/usuario.md,
 * 2026-08-11) espera ela virar `false` antes de derrubar a sessão de verdade — nunca corta uma
 * venda no meio.
 */
export function RotinaCriticaProvider({ children }: { children: ReactNode }) {
  const [emAndamento, setEmAndamento] = useState(false)
  return <Ctx.Provider value={{ emAndamento, setEmAndamento }}>{children}</Ctx.Provider>
}

export function useRotinaCritica(): RotinaCriticaCtx {
  const c = useContext(Ctx)
  if (!c) throw new Error('useRotinaCritica deve ser usado dentro de <RotinaCriticaProvider>')
  return c
}
