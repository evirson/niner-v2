import { api } from './api'

/**
 * Movimento do período — ⚠️ cada contador conta pela SUA data (abertas por `data_abertura`,
 * concluídas por `data_conclusao`, faturadas por `data_faturamento`, canceladas por
 * `data_cancelamento`). Somar os quatro não faz sentido: são fatos diferentes que por acaso cabem
 * no mesmo período. Ver docs/telas/relatorio-ordem-servico.md §1.
 */
export interface MovimentoOrdens {
  qtdAbertas: number
  qtdConcluidas: number
  qtdFaturadas: number
  qtdCanceladas: number
  valorFaturado: number
  valorDesconto: number
  ticketMedio: number
  /** Tempo de CALENDÁRIO (abertura → conclusão), não de bancada — inclui a espera do cliente. */
  tempoMedioHoras: number
}

/**
 * Uma linha por (empresa, executor). `idFuncionario === 0` é a linha "(SEM EXECUTOR)": item sem
 * executor atribuído, que aparece em vez de ser filtrado — sem ela a soma das linhas não fecharia
 * com o total geral e nada na tela diria por quê.
 */
export interface LinhaExecutor {
  idEmpresa: number
  nomeEmpresa: string
  idFuncionario: number
  nomeFuncionario: string
  qtdOrdens: number
  valorServicos: number
  valorPecas: number
  valorTotal: number
  tempoMedioHoras: number
}

export interface SubtotalEmpresaOrdens {
  idEmpresa: number
  nomeEmpresa: string
  qtdOrdens: number
  valorServicos: number
  valorPecas: number
  valorTotal: number
}

/** ⚠️ `qtdOrdens` aqui é de OS DISTINTAS — uma OS com dois executores conta 1 aqui e 1 para cada
 *  um deles nas linhas. É por isso que a coluna não soma; a tela avisa no rodapé. */
export interface TotalGeralOrdens {
  qtdOrdens: number
  valorServicos: number
  valorPecas: number
  valorTotal: number
}

export interface RelatorioOrdensServico {
  movimento: MovimentoOrdens
  linhas: LinhaExecutor[]
  subtotaisPorEmpresa: SubtotalEmpresaOrdens[]
  totalGeral: TotalGeralOrdens
}

/** Allowlist espelhando `COLUNAS_ORDENAVEIS` do backend (`RelatorioOrdensServicoService`). */
export type ColunaOrdenacaoOrdens =
  | 'nomeEmpresa'
  | 'nomeFuncionario'
  | 'qtdOrdens'
  | 'valorServicos'
  | 'valorPecas'
  | 'valorTotal'
  | 'tempoMedioHoras'

export type DirecaoOrdenacao = 'ASC' | 'DESC'

export interface FiltrosRelatorioOrdensServico {
  dataInicial: string
  dataFinal: string
  idsEmpresa?: number[]
  ordenarPor?: ColunaOrdenacaoOrdens
  direcao?: DirecaoOrdenacao
}

export function gerarRelatorioOrdensServico(
  filtros: FiltrosRelatorioOrdensServico,
): Promise<RelatorioOrdensServico> {
  const params = new URLSearchParams()
  params.set('dataInicial', filtros.dataInicial)
  params.set('dataFinal', filtros.dataFinal)
  for (const id of filtros.idsEmpresa ?? []) params.append('idsEmpresa', String(id))
  if (filtros.ordenarPor) params.set('ordenarPor', filtros.ordenarPor)
  if (filtros.direcao) params.set('direcao', filtros.direcao)
  return api<RelatorioOrdensServico>(`/api/v1/relatorios/ordens-servico?${params.toString()}`)
}
