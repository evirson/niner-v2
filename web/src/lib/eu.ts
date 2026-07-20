import { useQuery } from '@tanstack/react-query'
import { api } from './api'

export interface Eu {
  id_tenant: number
  conta: { nomeConta: string; slug: string; status: string }
  usuario: { nome: string; email: string; papel: string }
  trial_expira_em: string | null
}

/** "Quem sou eu" do tenant logado — inclui o papel (ADMIN/OPERADOR), usado para telas restritas. */
export function useEu() {
  return useQuery({ queryKey: ['eu'], queryFn: () => api<Eu>('/api/v1/eu'), retry: false })
}
