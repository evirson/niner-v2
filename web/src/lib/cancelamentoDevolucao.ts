import { api } from './api'

export interface DevolucaoParaCancelamento {
  idDevolucao: number
  idEmpresa: number
  nomeEmpresa: string
  dataDevolucao: string
  valorVale: number
  idVendaCredito: number | null
  valeUsado: boolean
  cancelada: boolean
}

export interface PaginaDevolucoesCancelamento {
  itens: DevolucaoParaCancelamento[]
  pagina: number
  tamanhoPagina: number
  totalItens: number
  totalPaginas: number
}

export interface ItemDevolucaoDetalhe {
  descricaoProduto: string
  variacaoCor: string | null
  variacaoTamanho: string | null
  qtd: number
  precoVenda: number
  valorItem: number
}

export interface DevolucaoDetalheCancelamento {
  idDevolucao: number
  idEmpresa: number
  nomeEmpresa: string
  dataDevolucao: string
  valorVale: number
  idVendaCredito: number | null
  valeUsado: boolean
  cancelada: boolean
  dataCancelamento: string | null
  nomeUsuarioCancelamento: string | null
  motivoCancelamento: string | null
  itens: ItemDevolucaoDetalhe[]
}

export type ColunaOrdenacaoCancelamentoDevolucao = 'dataDevolucao' | 'idDevolucao' | 'nomeEmpresa' | 'valorVale'
export type DirecaoOrdenacao = 'ASC' | 'DESC'

export interface FiltrosCancelamentoDevolucao {
  idDevolucao?: number
  dataInicial?: string
  dataFinal?: string
  pagina?: number
  tamanho?: number
  ordenarPor?: ColunaOrdenacaoCancelamentoDevolucao
  direcao?: DirecaoOrdenacao
}

export function listarDevolucoesParaCancelamento(
  filtros: FiltrosCancelamentoDevolucao,
): Promise<PaginaDevolucoesCancelamento> {
  const params = new URLSearchParams()
  if (filtros.idDevolucao) params.set('idDevolucao', String(filtros.idDevolucao))
  if (filtros.dataInicial) params.set('dataInicial', filtros.dataInicial)
  if (filtros.dataFinal) params.set('dataFinal', filtros.dataFinal)
  if (filtros.pagina) params.set('pagina', String(filtros.pagina))
  if (filtros.tamanho) params.set('limite', String(filtros.tamanho))
  if (filtros.ordenarPor) params.set('ordenarPor', filtros.ordenarPor)
  if (filtros.direcao) params.set('direcao', filtros.direcao)
  const query = params.toString()
  return api<PaginaDevolucoesCancelamento>(`/api/v1/vendas/cancelamento-devolucao${query ? `?${query}` : ''}`)
}

export function buscarDetalheParaCancelamentoDevolucao(idDevolucao: number): Promise<DevolucaoDetalheCancelamento> {
  return api<DevolucaoDetalheCancelamento>(`/api/v1/vendas/cancelamento-devolucao/${idDevolucao}`)
}

export interface CancelamentoDevolucaoEfetivado {
  idDevolucao: number
  dataCancelamento: string
}

export function cancelarDevolucao(idDevolucao: number, motivo: string): Promise<CancelamentoDevolucaoEfetivado> {
  return api<CancelamentoDevolucaoEfetivado>(`/api/v1/vendas/cancelamento-devolucao/${idDevolucao}`, {
    method: 'POST',
    body: JSON.stringify({ motivo }),
  })
}
