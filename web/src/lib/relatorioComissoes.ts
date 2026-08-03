import { api } from './api'

export interface LinhaComissao {
  idEmpresa: number
  nomeEmpresa: string
  idFuncionario: number
  nomeFuncionario: string
  valorVenda: number
  valorDevolucao: number
  valorLiquido: number
  percComissao: number
  valorComissao: number
}

export interface SubtotalEmpresa {
  idEmpresa: number
  nomeEmpresa: string
  valorVenda: number
  valorDevolucao: number
  valorLiquido: number
  valorComissao: number
}

export interface TotalGeralComissao {
  valorVenda: number
  valorDevolucao: number
  valorLiquido: number
  valorComissao: number
}

export interface RelatorioComissoes {
  linhas: LinhaComissao[]
  subtotaisPorEmpresa: SubtotalEmpresa[]
  totalGeral: TotalGeralComissao
}

/** Allowlist espelhando `COLUNAS_ORDENAVEIS` do backend (`RelatorioComissoesService`). */
export type ColunaOrdenacaoComissao =
  | 'nomeEmpresa'
  | 'nomeFuncionario'
  | 'valorVenda'
  | 'valorDevolucao'
  | 'valorLiquido'
  | 'percComissao'
  | 'valorComissao'

export type DirecaoOrdenacao = 'ASC' | 'DESC'

export interface FiltrosRelatorioComissoes {
  dataInicial: string
  dataFinal: string
  idsEmpresa?: number[]
  ordenarPor?: ColunaOrdenacaoComissao
  direcao?: DirecaoOrdenacao
}

export function gerarRelatorioComissoes(filtros: FiltrosRelatorioComissoes): Promise<RelatorioComissoes> {
  const params = new URLSearchParams()
  params.set('dataInicial', filtros.dataInicial)
  params.set('dataFinal', filtros.dataFinal)
  for (const id of filtros.idsEmpresa ?? []) params.append('idsEmpresa', String(id))
  if (filtros.ordenarPor) params.set('ordenarPor', filtros.ordenarPor)
  if (filtros.direcao) params.set('direcao', filtros.direcao)
  return api<RelatorioComissoes>(`/api/v1/relatorios/comissoes?${params.toString()}`)
}
