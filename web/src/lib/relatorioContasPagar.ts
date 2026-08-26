import { api } from './api'

/**
 * Relatório de Contas a Pagar / Pagas (`docs/telas/relatorio-contas-pagar.md`).
 *
 * ⭐ Duas decisões que o servidor toma e a tela apenas mostra:
 * 1. **"Paga" é ter data de pagamento**, não a marca "Documento Pago" — mesmo critério do Fluxo
 *    de Caixa. Quando as duas discordam, a linha vem com `divergente = true`.
 * 2. **Compra de mercadoria aparece aqui**, ao contrário da DRE e da Lucratividade: este
 *    relatório é sobre dinheiro que sai, não sobre lucro.
 */
export interface LinhaContaPagar {
  idContaPagar: number
  idEmpresa: number
  nomeEmpresa: string
  idFornecedor: number
  nomeFornecedor: string
  idPlanoContas: string
  descricaoPlanoContas: string
  notaFiscal: number | null
  numeroDuplicata: string | null
  dataLancamento: string
  dataVencimento: string
  dataPagamento: string | null
  valorPagar: number
  valorPago: number
  /** `valorPagar − valorPago`. Pagamento parcial existe. */
  valorEmAberto: number
  documentoPago: boolean
  situacao: 'PAGA' | 'VENCIDA' | 'A_VENCER'
  /** ⚠️ "Documento Pago" e "Data de Pagamento" discordam nesta linha. */
  divergente: boolean
}

export interface SubtotalEmpresaContaPagar {
  idEmpresa: number
  nomeEmpresa: string
  valorPagar: number
  valorPago: number
  valorEmAberto: number
}

export interface TotalGeralContaPagar {
  valorPagar: number
  valorPago: number
  valorEmAberto: number
}

/**
 * ⚠️ `vencido + aVencer = emAberto`, e **nunca** `totalPeriodo`. Somar os três daria o dobro do
 * que a loja deve — por isso a tela nunca os agrega num número só.
 */
export interface KpisContaPagar {
  totalPeriodo: number
  emAberto: number
  vencido: number
  aVencer: number
  pagoNoPeriodo: number
}

/** ⚠️ Soma o valor da conta, não o em aberto — a pergunta é "em que comprometi dinheiro". */
export interface FatiaGrafico {
  rotulo: string
  valor: number
}

export interface RelatorioContasPagar {
  linhas: LinhaContaPagar[]
  subtotaisPorEmpresa: SubtotalEmpresaContaPagar[]
  totalGeral: TotalGeralContaPagar
  kpis: KpisContaPagar
  graficoPorPlanoContas: FatiaGrafico[]
  graficoPorFornecedor: FatiaGrafico[]
}

export type SituacaoConta = 'ABERTA' | 'PAGA'

export type DirecaoOrdenacao = 'ASC' | 'DESC'

/** Allowlist espelhando `COLUNAS_ORDENAVEIS` do backend (`RelatorioContasPagarService`). */
export type ColunaOrdenacaoContaPagar =
  | 'nomeEmpresa'
  | 'nomeFornecedor'
  | 'idPlanoContas'
  | 'notaFiscal'
  | 'numeroDuplicata'
  | 'dataLancamento'
  | 'dataVencimento'
  | 'dataPagamento'
  | 'valorPagar'
  | 'valorPago'
  | 'valorEmAberto'

/** Cada período é opcional, mas pelo menos um precisa vir completo (validado no servidor). */
export interface FiltrosRelatorioContasPagar {
  dataLancamentoInicial?: string
  dataLancamentoFinal?: string
  dataVencimentoInicial?: string
  dataVencimentoFinal?: string
  dataPagamentoInicial?: string
  dataPagamentoFinal?: string
  idsEmpresa?: number[]
  idFornecedor?: number
  /** ⭐ Casa por PREFIXO no servidor: escolher a conta sintética traz a subárvore inteira. */
  idPlanoContas?: string
  situacao?: SituacaoConta
  ordenarPor?: ColunaOrdenacaoContaPagar
  direcao?: DirecaoOrdenacao
}

export function gerarRelatorioContasPagar(
  filtros: FiltrosRelatorioContasPagar,
): Promise<RelatorioContasPagar> {
  const params = new URLSearchParams()
  if (filtros.dataLancamentoInicial) params.set('dataLancamentoInicial', filtros.dataLancamentoInicial)
  if (filtros.dataLancamentoFinal) params.set('dataLancamentoFinal', filtros.dataLancamentoFinal)
  if (filtros.dataVencimentoInicial) params.set('dataVencimentoInicial', filtros.dataVencimentoInicial)
  if (filtros.dataVencimentoFinal) params.set('dataVencimentoFinal', filtros.dataVencimentoFinal)
  if (filtros.dataPagamentoInicial) params.set('dataPagamentoInicial', filtros.dataPagamentoInicial)
  if (filtros.dataPagamentoFinal) params.set('dataPagamentoFinal', filtros.dataPagamentoFinal)
  for (const id of filtros.idsEmpresa ?? []) params.append('idsEmpresa', String(id))
  if (filtros.idFornecedor) params.set('idFornecedor', String(filtros.idFornecedor))
  if (filtros.idPlanoContas) params.set('idPlanoContas', filtros.idPlanoContas)
  if (filtros.situacao) params.set('situacao', filtros.situacao)
  if (filtros.ordenarPor) params.set('ordenarPor', filtros.ordenarPor)
  if (filtros.direcao) params.set('direcao', filtros.direcao)
  return api<RelatorioContasPagar>(`/api/v1/relatorios/contas-pagar?${params.toString()}`)
}
