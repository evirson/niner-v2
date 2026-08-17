import { api } from './api'

/** Painel de Contingência (§9.7, bloco B7) — estado atual, fila pendente, entrar/sair manual.
 *  `duracaoMinutos` vem pronto do servidor, nunca recalculado a partir de `desde` no navegador. */
export interface ContingenciaEstado {
  ativa: boolean
  desde: string | null
  justificativa: string | null
  serieContingencia: number
  pendentes: number
  duracaoMinutos: number
}

export function buscarContingencia(idEmpresa: number): Promise<ContingenciaEstado> {
  return api<ContingenciaEstado>(`/api/v1/fiscal/contingencia/${idEmpresa}`)
}

export function entrarEmContingencia(idEmpresa: number, justificativa: string): Promise<ContingenciaEstado> {
  return api<ContingenciaEstado>(`/api/v1/fiscal/contingencia/${idEmpresa}/entrar`, {
    method: 'POST',
    body: JSON.stringify({ justificativa }),
  })
}

export function sairDaContingencia(idEmpresa: number, justificativa: string): Promise<ContingenciaEstado> {
  return api<ContingenciaEstado>(`/api/v1/fiscal/contingencia/${idEmpresa}/sair`, {
    method: 'POST',
    body: JSON.stringify({ justificativa }),
  })
}
