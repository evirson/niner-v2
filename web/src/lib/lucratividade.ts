import { api } from './api'

/** Relatório de Lucratividade (docs/telas/relatorio-lucratividade.md) — ADMIN-only na API. */

/** Uma linha de despesa do período (item 5), agrupada por conta analítica. */
export interface LinhaDespesaLucratividade {
  idPlanoContas: string
  descricao: string
  valor: number
  /** `false` = conta paga (data de PAGAMENTO). `true` = calculada do movimento —
   *  comissão e taxa de cartão, que não têm lançamento em Contas a Pagar e por isso contam pela
   *  data da VENDA. ⚠️ A tabela mistura duas bases de data, e a tela precisa dizer qual é qual. */
  derivada: boolean
  /** `null` quando a venda líquida é zero — o front imprime `—`, nunca `0%`. */
  percentualSobreVenda: number | null
  /** `null` quando o lucro bruto é zero **ou negativo**: "40% de um prejuízo" não significa nada. */
  percentualSobreLucroBruto: number | null
}

export interface PeriodoLucratividade {
  dataInicial: string
  dataFinal: string
}

export interface RespostaLucratividade {
  periodo: PeriodoLucratividade
  /** Vendas do período **antes** das devoluções. Só existe para o 2º percentual do item 6. */
  vendaBruta: number
  devolucoes: number
  /** O **item 1** do relatório impresso: "valor total da venda" = venda − devoluções. */
  vendaLiquida: number
  custoMercadoriaVendida: number
  lucroBruto: number
  percentualLucroBruto: number | null
  despesas: LinhaDespesaLucratividade[]
  totalDespesas: number
  lucroLiquido: number
  percentualSobreVendaBruta: number | null
  percentualSobreVendaLiquida: number | null
}

export interface FiltrosLucratividade {
  dataInicial: string
  dataFinal: string
  idsEmpresa?: number[]
}

export function gerarLucratividade(filtros: FiltrosLucratividade): Promise<RespostaLucratividade> {
  const params = new URLSearchParams({
    dataInicial: filtros.dataInicial,
    dataFinal: filtros.dataFinal,
  })
  if (filtros.idsEmpresa && filtros.idsEmpresa.length > 0) {
    params.set('idsEmpresa', filtros.idsEmpresa.join(','))
  }
  return api<RespostaLucratividade>(`/api/v1/relatorios/lucratividade?${params.toString()}`)
}
