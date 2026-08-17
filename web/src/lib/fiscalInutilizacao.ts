import { api } from './api'

/** Inutilização de faixa de numeração (§10.4, bloco B8) — buracos detectados sozinhos, faixa +
 *  justificativa, histórico de tentativas (P3: mesmo a recusada fica registrada). */
export interface FaixaBuraco {
  numeroInicial: number
  numeroFinal: number
}

export interface InutilizacaoItem {
  modelo: number
  serie: number
  ano: number
  numeroInicial: number
  numeroFinal: number
  justificativa: string
  autorizado: boolean
  protocolo: string | null
  motivoSefaz: string | null
  criadoEm: string
}

export interface InutilizacaoResultado {
  protocolo: string
  ano: number
}

export function detectarBuracos(idEmpresa: number, modelo: number, serie: number): Promise<FaixaBuraco[]> {
  const params = new URLSearchParams({
    idEmpresa: String(idEmpresa),
    modelo: String(modelo),
    serie: String(serie),
  })
  return api<FaixaBuraco[]>(`/api/v1/fiscal/inutilizacoes/buracos?${params.toString()}`)
}

export function listarInutilizacoes(idEmpresa: number): Promise<InutilizacaoItem[]> {
  return api<InutilizacaoItem[]>(`/api/v1/fiscal/inutilizacoes?idEmpresa=${idEmpresa}`)
}

export function inutilizarFaixa(
  idEmpresa: number,
  modelo: number,
  serie: number,
  numeroInicial: number,
  numeroFinal: number,
  justificativa: string,
): Promise<InutilizacaoResultado> {
  return api<InutilizacaoResultado>('/api/v1/fiscal/inutilizacoes', {
    method: 'POST',
    body: JSON.stringify({ idEmpresa, modelo, serie, numeroInicial, numeroFinal, justificativa }),
  })
}
