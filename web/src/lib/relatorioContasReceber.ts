import { api } from './api'
import type { LinhaCarteiraGrafico } from './relatorioVendas'

export interface LinhaContaReceber {
  idEmpresa: number
  nomeEmpresa: string
  nomeEmpresaPagamento: string | null
  idVenda: number
  idCliente: number | null
  nomeCliente: string | null
  nomeCarteira: string
  categoriaCarteira: 'CARTAO_DEBITO' | 'CARTAO_CREDITO' | 'CREDIARIO'
  numeroParcela: number
  totalParcelas: number
  dataVenda: string
  dataVencimento: string
  dataRecebimento: string | null
  valorBruto: number
  taxaAdministrativa: number
  valorTaxaAdministrativa: number
  valorLiquido: number
}

/** Um KPI por carteira (nome + categoria) — soma de valor líquido das parcelas em aberto do
 *  filtro atual, separada em vencida (vencimento no passado) e a vencer (hoje ou no futuro). */
export interface KpiFormaPagamento {
  nomeCarteira: string
  categoriaCarteira: 'CARTAO_DEBITO' | 'CARTAO_CREDITO' | 'CREDIARIO'
  valorVencido: number
  valorAVencer: number
}

export type StatusParcela = 'ABERTO' | 'RECEBIDA'

export type CategoriaParcela = 'CARTAO_DEBITO' | 'CARTAO_CREDITO' | 'CREDIARIO'

/** Allowlist espelhando `COLUNAS_ORDENAVEIS` do backend (`RelatorioContasReceberService`). */
export type ColunaOrdenacaoContaReceber =
  | 'nomeEmpresa'
  | 'idVenda'
  | 'nomeCliente'
  | 'nomeCarteira'
  | 'numeroParcela'
  | 'dataVenda'
  | 'dataVencimento'
  | 'dataRecebimento'
  | 'nomeEmpresaPagamento'
  | 'valorBruto'
  | 'taxaAdministrativa'
  | 'valorTaxaAdministrativa'
  | 'valorLiquido'

export type DirecaoOrdenacao = 'ASC' | 'DESC'

export interface SubtotalEmpresaContaReceber {
  idEmpresa: number
  nomeEmpresa: string
  valorBruto: number
  valorTaxaAdministrativa: number
  valorLiquido: number
}

export interface TotalGeralContaReceber {
  valorBruto: number
  valorTaxaAdministrativa: number
  valorLiquido: number
}

export interface RelatorioContasReceber {
  linhas: LinhaContaReceber[]
  subtotaisPorEmpresa: SubtotalEmpresaContaReceber[]
  totalGeral: TotalGeralContaReceber
  graficoPorFormaPagamento: LinhaCarteiraGrafico[]
  kpisPorFormaPagamento: KpiFormaPagamento[]
}

/** Cada período é opcional, mas pelo menos um precisa vir completo (validado no servidor). */
export interface FiltrosRelatorioContasReceber {
  dataVendaInicial?: string
  dataVendaFinal?: string
  dataVencimentoInicial?: string
  dataVencimentoFinal?: string
  dataRecebimentoInicial?: string
  dataRecebimentoFinal?: string
  idsEmpresa?: number[]
  status?: StatusParcela
  categoria?: CategoriaParcela
  ordenarPor?: ColunaOrdenacaoContaReceber
  direcao?: DirecaoOrdenacao
}

export function gerarRelatorioContasReceber(filtros: FiltrosRelatorioContasReceber): Promise<RelatorioContasReceber> {
  const params = new URLSearchParams()
  if (filtros.dataVendaInicial) params.set('dataVendaInicial', filtros.dataVendaInicial)
  if (filtros.dataVendaFinal) params.set('dataVendaFinal', filtros.dataVendaFinal)
  if (filtros.dataVencimentoInicial) params.set('dataVencimentoInicial', filtros.dataVencimentoInicial)
  if (filtros.dataVencimentoFinal) params.set('dataVencimentoFinal', filtros.dataVencimentoFinal)
  if (filtros.dataRecebimentoInicial) params.set('dataRecebimentoInicial', filtros.dataRecebimentoInicial)
  if (filtros.dataRecebimentoFinal) params.set('dataRecebimentoFinal', filtros.dataRecebimentoFinal)
  for (const id of filtros.idsEmpresa ?? []) params.append('idsEmpresa', String(id))
  if (filtros.status) params.set('status', filtros.status)
  if (filtros.categoria) params.set('categoria', filtros.categoria)
  if (filtros.ordenarPor) params.set('ordenarPor', filtros.ordenarPor)
  if (filtros.direcao) params.set('direcao', filtros.direcao)
  return api<RelatorioContasReceber>(`/api/v1/relatorios/contas-receber?${params.toString()}`)
}
