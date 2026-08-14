import { api } from './api'

/** Fluxo de Caixa (docs/telas/fluxo-caixa.md) — realizado e projeção. */
export type OrigemDinheiro = 'TODAS' | 'CAIXA' | 'CONTA_CORRENTE'
export type AgrupamentoProjecao = 'DIA' | 'SEMANA' | 'MES'

export interface LinhaAtividade {
  chave: string
  rotulo: string
  /** Já vem com sinal: entrada positiva, saída negativa. */
  valor: number
}

export interface Atividade {
  grupo: 'OPERACIONAL' | 'INVESTIMENTO' | 'FINANCIAMENTO'
  rotulo: string
  total: number
  linhas: LinhaAtividade[]
}

export interface FluxoRealizado {
  dataInicial: string
  dataFinal: string
  saldoInicial: number
  atividades: Atividade[]
  totalEntradas: number
  totalSaidas: number
  saldoFinal: number
  saldoRealAtual: number
  /** `null` quando o período termina no passado — aí não faz sentido comparar com o saldo de hoje. */
  diferencaConciliacao: number | null
}

export interface LinhaProjecao {
  data: string
  rotulo: string
  entradas: number
  saidas: number
  saldoPeriodo: number
  saldoAcumulado: number
  emAtraso: boolean
}

export interface FluxoProjecao {
  dataInicial: string
  dataFinal: string
  saldoAtual: number
  linhas: LinhaProjecao[]
  totalEntradasPrevistas: number
  totalSaidasPrevistas: number
  saldoProjetadoFinal: number
  primeiraDataNegativa: string | null
  valorFaltante: number | null
}

export function gerarFluxoRealizado(filtros: {
  dataInicial: string
  dataFinal: string
  idsEmpresa?: number[]
  origem: OrigemDinheiro
}): Promise<FluxoRealizado> {
  const params = new URLSearchParams({
    dataInicial: filtros.dataInicial,
    dataFinal: filtros.dataFinal,
    origem: filtros.origem,
  })
  if (filtros.idsEmpresa?.length) params.set('idsEmpresa', filtros.idsEmpresa.join(','))
  return api<FluxoRealizado>(`/api/v1/relatorios/fluxo-caixa/realizado?${params.toString()}`)
}

export function gerarFluxoProjecao(filtros: {
  dataInicial: string
  dataFinal: string
  idsEmpresa?: number[]
  agrupamento: AgrupamentoProjecao
}): Promise<FluxoProjecao> {
  const params = new URLSearchParams({
    dataInicial: filtros.dataInicial,
    dataFinal: filtros.dataFinal,
    agrupamento: filtros.agrupamento,
  })
  if (filtros.idsEmpresa?.length) params.set('idsEmpresa', filtros.idsEmpresa.join(','))
  return api<FluxoProjecao>(`/api/v1/relatorios/fluxo-caixa/projecao?${params.toString()}`)
}
