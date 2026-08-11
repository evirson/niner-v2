import { useQuery } from '@tanstack/react-query'
import { api } from './api'

export interface Eu {
  id_tenant: number
  conta: { nomeConta: string; slug: string; status: string }
  usuario: { idUsuario: number; nome: string; email: string; papel: string }
  /** Empresa ativa da sessão (escolhida no login, 2026-07-28) — todo cadastro feito nesta
   *  sessão com coluna `id_empresa` usa esta empresa. */
  empresa: { idEmpresa: number; nome: string }
  trial_expira_em: string | null
  /** Contagem regressiva do aviso de horário de acesso (`HorarioAcessoGuard`, 2026-08-14) —
   *  `null` na imensa maioria das chamadas; só vem preenchido nos minutos finais de tolerância
   *  antes do bloqueio de verdade. */
  segundosRestantesTolerancia: number | null
}

/** "Quem sou eu" do tenant logado — inclui o papel (ADMIN/OPERADOR), usado para telas restritas. */
export function useEu() {
  return useQuery({ queryKey: ['eu'], queryFn: () => api<Eu>('/api/v1/eu'), retry: false })
}
