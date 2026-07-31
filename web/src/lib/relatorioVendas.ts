import { api } from './api'
import type { CategoriaCarteira } from './tiposCarteira'

export type TotalizarPor = 'NAO_TOTALIZAR' | 'DATA_VENDA' | 'CLIENTE' | 'VENDEDOR' | 'OPERADOR_CAIXA' | 'EMPRESA'

export interface KpisRelatorioVendas {
  ticketMedioValor: number
  ticketMedioNVendas: number
  percentualMedioDesconto: number
  valorDesconto: number
  percentualDevolucao: number
  valorDevolucao: number
  itensVendidos: number
  mediaItensPorVenda: number
}

export interface ComposicaoFaturamento {
  valorBruto: number
  descontos: number
  acrescimos: number
  devolucoes: number
  vendaLiquida: number
}

export interface PontoGrafico {
  rotulo: string
  valor: number
}

/** A mesma bandeira pode existir em mais de uma categoria (ex.: "HIPER" em Cartão Débito e em
 *  Cartão Crédito, saldos diferentes) — nome e categoria vêm separados; use `rotulosPorCarteira`
 *  pra montar o texto de exibição (rótulo condicional, ver a função). */
export interface LinhaCarteiraGrafico {
  nomeCarteira: string
  categoriaCarteira: CategoriaCarteira
  valor: number
}

/** Palavra curta (sem "Cartão") — diferente de `rotuloCarteira()` (`lib/caixa.ts`, sempre
 *  "NOME — Categoria" por extenso, usado no Fechamento de Caixa), pedido específico do
 *  Relatório de Vendas pra caber numa linha só. */
const PALAVRA_CURTA_CATEGORIA: Partial<Record<CategoriaCarteira, string>> = {
  CARTAO_DEBITO: 'Débito',
  CARTAO_CREDITO: 'Crédito',
}

/** Cartão sempre diz Débito/Crédito, mesmo quando só existe uma bandeira daquele nome no
 *  gráfico ("VISA Crédito") — é informação relevante por si, não só desambiguação de nome
 *  repetido. À Vista/Crediário não têm essa distinção — só o nome ("DINHEIRO", "CREDIARIO").
 *  Usado tanto pelo gráfico quanto pela impressão/PDF (mesma fonte de verdade). */
export function rotulosPorCarteira(dados: LinhaCarteiraGrafico[]): PontoGrafico[] {
  return dados.map((d) => {
    const palavraCurta = PALAVRA_CURTA_CATEGORIA[d.categoriaCarteira]
    return { rotulo: palavraCurta ? `${d.nomeCarteira} ${palavraCurta}` : d.nomeCarteira, valor: d.valor }
  })
}

export interface GraficosRelatorioVendas {
  porDia: PontoGrafico[]
  topMarcas: PontoGrafico[]
  topVendedores: PontoGrafico[]
  topClientes: PontoGrafico[]
  porCarteira: LinhaCarteiraGrafico[]
  porHora: PontoGrafico[]
  porDiaSemana: PontoGrafico[]
}

export interface LinhaAgrupadaTotalizador {
  chave: string
  nome: string
  nVendas: number
  valorVenda: number
}

export interface LinhaAnaliticaTotalizador {
  idVenda: number
  idEmpresa: number
  nomeEmpresa: string
  dataHoraVenda: string
  nomeCliente: string | null
  nomeVendedor: string | null
  nomeOperador: string | null
  qtdProdutos: number
  valorVenda: number
  acrescimos: number
  descontos: number
  valorLiquido: number
}

export interface Totalizador {
  tipo: 'ANALITICO' | 'AGRUPADO'
  linhasAgrupadas: LinhaAgrupadaTotalizador[] | null
  linhasAnaliticas: LinhaAnaliticaTotalizador[] | null
}

export interface RelatorioVendasResponse {
  kpis: KpisRelatorioVendas
  composicaoFaturamento: ComposicaoFaturamento
  graficos: GraficosRelatorioVendas
  totalizador: Totalizador
}

export interface FiltrosRelatorioVendas {
  dataInicial: string
  dataFinal: string
  idsEmpresa?: number[]
  idFuncionario?: number
  totalizarPor: TotalizarPor
}

function montarParams(filtros: FiltrosRelatorioVendas): URLSearchParams {
  const params = new URLSearchParams()
  params.set('dataInicial', filtros.dataInicial)
  params.set('dataFinal', filtros.dataFinal)
  params.set('totalizarPor', filtros.totalizarPor)
  if (filtros.idFuncionario) params.set('idFuncionario', String(filtros.idFuncionario))
  for (const id of filtros.idsEmpresa ?? []) params.append('idsEmpresa', String(id))
  return params
}

export function gerarRelatorioVendas(filtros: FiltrosRelatorioVendas): Promise<RelatorioVendasResponse> {
  return api<RelatorioVendasResponse>(`/api/v1/relatorios/vendas?${montarParams(filtros)}`)
}

export function buscarDetalheTotalizador(
  filtros: FiltrosRelatorioVendas,
  chave: string,
): Promise<{ itens: LinhaAnaliticaTotalizador[] }> {
  const params = montarParams(filtros)
  params.set('chave', chave)
  return api<{ itens: LinhaAnaliticaTotalizador[] }>(`/api/v1/relatorios/vendas/detalhe?${params}`)
}
