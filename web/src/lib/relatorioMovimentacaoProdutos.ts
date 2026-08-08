import { api } from './api'

export type ModeloRelatorioMovimentacao = 'ANALITICO' | 'KARDEX' | 'SINTETICO'

export type TipoMovimentoProduto =
  | 'COMPRA'
  | 'TRANSFERENCIA'
  | 'DEVOLUCAO'
  | 'AJUSTE'
  | 'VENDA'
  | 'RESERVA'
  | 'LIBERACAO_RESERVA'
  | 'CANCELAMENTO'

export interface LinhaAnaliticaMovimentacao {
  idEmpresa: number
  nomeEmpresa: string
  dataMovimento: string
  tipoMovimento: TipoMovimentoProduto
  movimentoFisico: boolean
  idVariacao: number
  sku: string
  descricaoProduto: string
  marca: string | null
  variacaoCor: string | null
  variacaoTamanho: string | null
  entrada: number
  saida: number
  custoUnitario: number
  valorMovimentado: number
  documento: string
  nomeFuncionario: string | null
}

export interface CabecalhoKardex {
  idVariacao: number
  sku: string
  descricaoProduto: string
  marca: string | null
  variacaoCor: string | null
  variacaoTamanho: string | null
  idEmpresa: number
  nomeEmpresa: string
  saldoInicial: number
  saldoFinal: number
}

export interface LinhaKardex {
  dataMovimento: string
  tipoMovimento: TipoMovimentoProduto
  movimentoFisico: boolean
  documento: string
  nomeFuncionario: string | null
  entrada: number
  saida: number
  saldoApos: number
}

export interface LinhaSinteticaMovimentacao {
  tipoMovimento: TipoMovimentoProduto
  movimentoFisico: boolean
  qtdEntrada: number
  qtdSaida: number
  valorEntrada: number
  valorSaida: number
}

export interface PontoGraficoMovimentacao {
  rotulo: string
  valor: number
}

export interface GraficosMovimentacao {
  porTipo: PontoGraficoMovimentacao[]
  porDia: PontoGraficoMovimentacao[]
  topAjustesNegativos: PontoGraficoMovimentacao[]
}

export interface KpisMovimentacao {
  qtdEntradaFisica: number
  qtdSaidaFisica: number
  valorEntradaFisica: number
  valorSaidaFisica: number
  saldoLiquidoFisico: number
}

export interface VariacaoEncontrada {
  idVariacao: number
  sku: string
  descricaoProduto: string
  marca: string | null
  variacaoCor: string | null
  variacaoTamanho: string | null
}

/** Só os campos do `modelo` pedido vêm preenchidos — mesmo padrão dos demais relatórios. */
export interface RelatorioMovimentacaoProdutos {
  modelo: ModeloRelatorioMovimentacao
  kpis: KpisMovimentacao | null
  graficos: GraficosMovimentacao | null
  linhasAnalitico: LinhaAnaliticaMovimentacao[] | null
  cabecalhoKardex: CabecalhoKardex | null
  linhasKardex: LinhaKardex[] | null
  linhasSintetico: LinhaSinteticaMovimentacao[] | null
}

export interface FiltrosRelatorioMovimentacaoProdutos {
  modelo: ModeloRelatorioMovimentacao
  dataInicial: string
  dataFinal: string
  idsEmpresa?: number[]
  tipos?: TipoMovimentoProduto[]
  marcas?: string[]
  idsCategoria?: number[]
  idVariacaoKardex?: number
  idEmpresaKardex?: number
}

export function gerarRelatorioMovimentacaoProdutos(
  filtros: FiltrosRelatorioMovimentacaoProdutos,
): Promise<RelatorioMovimentacaoProdutos> {
  const params = new URLSearchParams()
  params.set('modelo', filtros.modelo)
  params.set('dataInicial', filtros.dataInicial)
  params.set('dataFinal', filtros.dataFinal)
  for (const id of filtros.idsEmpresa ?? []) params.append('idsEmpresa', String(id))
  for (const tipo of filtros.tipos ?? []) params.append('tipos', tipo)
  for (const marca of filtros.marcas ?? []) params.append('marcas', marca)
  for (const id of filtros.idsCategoria ?? []) params.append('idsCategoria', String(id))
  if (filtros.idVariacaoKardex) params.set('idVariacaoKardex', String(filtros.idVariacaoKardex))
  if (filtros.idEmpresaKardex) params.set('idEmpresaKardex', String(filtros.idEmpresaKardex))
  return api<RelatorioMovimentacaoProdutos>(`/api/v1/relatorios/movimentacao-produtos?${params.toString()}`)
}

export function buscarVariacoesMovimentacao(busca: string): Promise<VariacaoEncontrada[]> {
  const params = new URLSearchParams()
  if (busca) params.set('busca', busca)
  return api<VariacaoEncontrada[]>(`/api/v1/relatorios/movimentacao-produtos/variacoes?${params.toString()}`)
}
