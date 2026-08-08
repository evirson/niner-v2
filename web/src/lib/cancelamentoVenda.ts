import { api } from './api'

export interface VendaParaCancelamento {
  idVenda: number
  idEmpresa: number
  nomeEmpresa: string
  dataVenda: string
  idCliente: number | null
  nomeCliente: string | null
  idFuncionario: number | null
  nomeFuncionario: string | null
  valorVenda: number
  cancelada: boolean
  bloqueadaCredario: boolean
}

export interface PaginaVendasCancelamento {
  itens: VendaParaCancelamento[]
  pagina: number
  tamanhoPagina: number
  totalItens: number
  totalPaginas: number
}

export interface ItemVendaDetalhe {
  descricaoProduto: string
  variacaoCor: string | null
  variacaoTamanho: string | null
  qtd: number
  precoVenda: number
  valorItem: number
}

export interface PagamentoVendaDetalhe {
  idCarteira: number
  nomeCarteira: string
  categoriaCarteira: string
  valorPago: number
  quitado: boolean
}

export interface ParcelaRecebidaDetalhe {
  numeroParcela: number
  dataVencimento: string
  dataRecebimento: string
  valorRecebido: number
}

export interface VendaDetalheCancelamento {
  idVenda: number
  idEmpresa: number
  nomeEmpresa: string
  dataVenda: string
  idCliente: number | null
  nomeCliente: string | null
  idFuncionario: number | null
  nomeFuncionario: string | null
  valorVenda: number
  cancelada: boolean
  dataCancelamento: string | null
  nomeUsuarioCancelamento: string | null
  motivoCancelamento: string | null
  itens: ItemVendaDetalhe[]
  pagamentos: PagamentoVendaDetalhe[]
  bloqueadaCredario: boolean
  parcelasRecebidas: ParcelaRecebidaDetalhe[]
}

export type ColunaOrdenacaoCancelamento =
  | 'dataVenda'
  | 'numeroVenda'
  | 'valorVenda'
  | 'nomeCliente'
  | 'nomeEmpresa'
  | 'nomeFuncionario'
export type DirecaoOrdenacao = 'ASC' | 'DESC'

export interface FiltrosCancelamentoVenda {
  numeroVenda?: number
  idEmpresa?: number
  idCliente?: number
  dataInicial?: string
  dataFinal?: string
  idFuncionario?: number
  pagina?: number
  tamanho?: number
  ordenarPor?: ColunaOrdenacaoCancelamento
  direcao?: DirecaoOrdenacao
}

export function listarVendasParaCancelamento(filtros: FiltrosCancelamentoVenda): Promise<PaginaVendasCancelamento> {
  const params = new URLSearchParams()
  if (filtros.numeroVenda) params.set('numeroVenda', String(filtros.numeroVenda))
  if (filtros.idEmpresa) params.set('idEmpresa', String(filtros.idEmpresa))
  if (filtros.idCliente) params.set('idCliente', String(filtros.idCliente))
  if (filtros.dataInicial) params.set('dataInicial', filtros.dataInicial)
  if (filtros.dataFinal) params.set('dataFinal', filtros.dataFinal)
  if (filtros.idFuncionario) params.set('idFuncionario', String(filtros.idFuncionario))
  if (filtros.pagina) params.set('pagina', String(filtros.pagina))
  if (filtros.tamanho) params.set('limite', String(filtros.tamanho))
  if (filtros.ordenarPor) params.set('ordenarPor', filtros.ordenarPor)
  if (filtros.direcao) params.set('direcao', filtros.direcao)
  const query = params.toString()
  return api<PaginaVendasCancelamento>(`/api/v1/vendas/cancelamento${query ? `?${query}` : ''}`)
}

export function buscarDetalheParaCancelamento(idVenda: number): Promise<VendaDetalheCancelamento> {
  return api<VendaDetalheCancelamento>(`/api/v1/vendas/cancelamento/${idVenda}`)
}

export interface CancelamentoEfetivado {
  idVenda: number
  dataCancelamento: string
}

export function cancelarVenda(idVenda: number, motivo: string): Promise<CancelamentoEfetivado> {
  return api<CancelamentoEfetivado>(`/api/v1/vendas/cancelamento/${idVenda}`, {
    method: 'POST',
    body: JSON.stringify({ motivo }),
  })
}
