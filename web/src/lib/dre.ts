import { api } from './api'

/** Relatório de DRE (docs/telas/relatorio-dre.md) — ADMIN-only na API. */
export type RegimeDre = 'COMPETENCIA' | 'CAIXA'
export type ComparacaoDre = 'NENHUM' | 'PERIODO_ANTERIOR' | 'ANO_ANTERIOR'
export type TipoLinhaDre = 'GRUPO' | 'CONTA' | 'SUBTOTAL'

export interface LinhaDre {
  chave: string
  rotulo: string
  nivel: number
  tipo: TipoLinhaDre
  /** Já vem com o sinal do efeito no resultado: dedução, custo e despesa são negativos. */
  valor: number
  /** % sobre a receita líquida; `null` quando não há base (receita líquida zero). */
  percentualAv: number | null
  valorComparado: number | null
  variacaoAbsoluta: number | null
  variacaoPercentual: number | null
}

export interface PeriodoDre {
  dataInicial: string
  dataFinal: string
}

export interface RespostaDre {
  regime: RegimeDre
  periodo: PeriodoDre
  periodoComparado: PeriodoDre | null
  linhas: LinhaDre[]
  receitaLiquida: number
  resultadoLiquido: number
}

export interface FiltrosDre {
  dataInicial: string
  dataFinal: string
  regime: RegimeDre
  idsEmpresa?: number[]
  comparar: ComparacaoDre
}

export function gerarDre(filtros: FiltrosDre): Promise<RespostaDre> {
  const params = new URLSearchParams({
    dataInicial: filtros.dataInicial,
    dataFinal: filtros.dataFinal,
    regime: filtros.regime,
    comparar: filtros.comparar,
  })
  if (filtros.idsEmpresa && filtros.idsEmpresa.length > 0) {
    params.set('idsEmpresa', filtros.idsEmpresa.join(','))
  }
  return api<RespostaDre>(`/api/v1/relatorios/dre?${params.toString()}`)
}
