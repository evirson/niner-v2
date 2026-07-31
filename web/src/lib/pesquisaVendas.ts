import { api } from './api'

export interface VendaPesquisa {
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
}

export interface PaginaVendasPesquisa {
  itens: VendaPesquisa[]
  pagina: number
  tamanhoPagina: number
  totalItens: number
  totalPaginas: number
  totalItensAtivos: number
  somaValorAtivas: number
}

export interface ItemVendaPesquisa {
  codigo: string
  descricaoProduto: string
  variacaoLinha: string | null
  variacaoColuna: string | null
  qtd: number
  valorUnitario: number
  valorDesconto: number
  valorItem: number
}

export interface MovimentoCaixaPesquisa {
  dataHora: string
  tipoOperacao: string
  nomeCarteira: string
  origem: string
  creditoDebito: 'C' | 'D'
  valor: number
}

export type SituacaoParcela = 'ABERTA' | 'PAGA' | 'VENCIDA'

export interface ParcelaPesquisa {
  numeroParcela: number
  totalParcelas: number
  dataVencimento: string
  valor: number
  situacao: SituacaoParcela
  dataPagamento: string | null
  valorPago: number
  valorJuros: number
}

export interface VendaDetalhePesquisa {
  idVenda: number
  idEmpresa: number
  nomeEmpresa: string
  dataVenda: string
  idCliente: number | null
  nomeCliente: string | null
  cpfCnpj: string | null
  fisicaJuridica: boolean | null
  idFuncionario: number | null
  nomeFuncionario: string | null
  condicaoPagamento: string
  desconto: number
  valorTotal: number
  recebido: number
  aReceber: number
  cancelada: boolean
  dataCancelamento: string | null
  nomeUsuarioCancelamento: string | null
  motivoCancelamento: string | null
  itens: ItemVendaPesquisa[]
  movimentosCaixa: MovimentoCaixaPesquisa[]
  temParcelasCredario: boolean
  parcelas: ParcelaPesquisa[]
}

export type ColunaOrdenacaoPesquisaVenda =
  | 'dataVenda'
  | 'numeroVenda'
  | 'valorVenda'
  | 'nomeCliente'
  | 'nomeEmpresa'
  | 'nomeFuncionario'
export type DirecaoOrdenacao = 'ASC' | 'DESC'
export type SituacaoVendaFiltro = 'ATIVAS' | 'CANCELADAS'

export interface FiltrosPesquisaVenda {
  numeroVenda?: number
  idEmpresa?: number
  situacao?: SituacaoVendaFiltro
  idCliente?: number
  idFuncionario?: number
  dataInicial?: string
  dataFinal?: string
  pagina?: number
  tamanho?: number
  ordenarPor?: ColunaOrdenacaoPesquisaVenda
  direcao?: DirecaoOrdenacao
}

export function pesquisarVendas(filtros: FiltrosPesquisaVenda): Promise<PaginaVendasPesquisa> {
  const params = new URLSearchParams()
  if (filtros.numeroVenda) params.set('numeroVenda', String(filtros.numeroVenda))
  if (filtros.idEmpresa) params.set('idEmpresa', String(filtros.idEmpresa))
  if (filtros.situacao) params.set('situacao', filtros.situacao)
  if (filtros.idCliente) params.set('idCliente', String(filtros.idCliente))
  if (filtros.idFuncionario) params.set('idFuncionario', String(filtros.idFuncionario))
  if (filtros.dataInicial) params.set('dataInicial', filtros.dataInicial)
  if (filtros.dataFinal) params.set('dataFinal', filtros.dataFinal)
  if (filtros.pagina) params.set('pagina', String(filtros.pagina))
  if (filtros.tamanho) params.set('limite', String(filtros.tamanho))
  if (filtros.ordenarPor) params.set('ordenarPor', filtros.ordenarPor)
  if (filtros.direcao) params.set('direcao', filtros.direcao)
  const query = params.toString()
  return api<PaginaVendasPesquisa>(`/api/v1/vendas/pesquisa${query ? `?${query}` : ''}`)
}

export function buscarDetalhePesquisaVenda(idVenda: number): Promise<VendaDetalhePesquisa> {
  return api<VendaDetalhePesquisa>(`/api/v1/vendas/pesquisa/${idVenda}`)
}
