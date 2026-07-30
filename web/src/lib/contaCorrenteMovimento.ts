import { api } from './api'
import { dataParaIso, desmascararMoeda, formatarMoeda, isoParaData } from './masks'
import { maiusculas } from './texto'

export type CreditoDebito = 'C' | 'D'

export interface ContaCorrenteMovimento {
  localizador: number
  idContaCorrente: string
  descricaoContaCorrente: string
  idPlanoContas: string
  descricaoPlanoContas: string
  dataMovimento: string
  numeroDocumento: string
  creditoDebito: CreditoDebito
  compensado: boolean
  valor: number
  observacao: string | null
  criadoEm: string
  atualizadoEm: string
}

/** Estado do formulário — strings para casar com inputs controlados. */
export interface ContaCorrenteMovimentoFormState {
  idContaCorrente: string
  idPlanoContas: string
  dataMovimento: string
  numeroDocumento: string
  creditoDebito: CreditoDebito | ''
  compensado: boolean
  valorTexto: string
  observacao: string
}

export const CONTA_CORRENTE_MOVIMENTO_VAZIO: ContaCorrenteMovimentoFormState = {
  idContaCorrente: '',
  idPlanoContas: '',
  dataMovimento: '',
  numeroDocumento: '',
  creditoDebito: '',
  compensado: false,
  valorTexto: '0,00',
  observacao: '',
}

export function paraFormulario(m: ContaCorrenteMovimento): ContaCorrenteMovimentoFormState {
  return {
    idContaCorrente: m.idContaCorrente,
    idPlanoContas: m.idPlanoContas,
    dataMovimento: isoParaData(m.dataMovimento),
    numeroDocumento: m.numeroDocumento,
    creditoDebito: m.creditoDebito,
    compensado: m.compensado,
    valorTexto: formatarMoeda(m.valor),
    observacao: m.observacao ?? '',
  }
}

/** {@code dataMovimento} já validada (dd/mm/aaaa) antes de chamar — meio-dia UTC evita
 *  problema de fuso virar o dia ao converter de volta pra exibição. */
export function paraRequisicao(f: ContaCorrenteMovimentoFormState) {
  return {
    idContaCorrente: f.idContaCorrente,
    idPlanoContas: f.idPlanoContas,
    dataMovimento: `${dataParaIso(f.dataMovimento)}T12:00:00Z`,
    numeroDocumento: f.numeroDocumento.trim(),
    creditoDebito: f.creditoDebito,
    compensado: f.compensado,
    valor: desmascararMoeda(f.valorTexto),
    observacao: f.observacao.trim() ? maiusculas(f.observacao.trim()) : null,
  }
}

export interface PaginaContaCorrenteMovimento {
  itens: ContaCorrenteMovimento[]
  pagina: number
  tamanhoPagina: number
  totalItens: number
  totalPaginas: number
}

export interface ExclusaoContaCorrenteMovimento {
  acao: 'excluido'
}

export type ColunaOrdenacaoMovimento = 'dataMovimento' | 'numeroDocumento' | 'idContaCorrente' | 'creditoDebito' | 'valor' | 'compensado'
export type DirecaoOrdenacao = 'ASC' | 'DESC'
export type FiltroCompensado = 'TODOS' | 'COMPENSADOS' | 'PENDENTES'

export interface FiltrosMovimento {
  idContaCorrente?: string
  idEmpresa?: number
  idPlanoContas?: string
  busca?: string
  /** ISO (aaaa-mm-dd) — converter com {@link dataParaIso} antes de passar aqui. */
  dataInicial?: string
  dataFinal?: string
  compensado?: FiltroCompensado
  pagina?: number
  tamanho?: number
  ordenarPor?: ColunaOrdenacaoMovimento
  direcao?: DirecaoOrdenacao
}

export function listarMovimentos(filtros: FiltrosMovimento): Promise<PaginaContaCorrenteMovimento> {
  const params = new URLSearchParams()
  if (filtros.idContaCorrente) params.set('idContaCorrente', filtros.idContaCorrente)
  if (filtros.idEmpresa) params.set('idEmpresa', String(filtros.idEmpresa))
  if (filtros.idPlanoContas) params.set('idPlanoContas', filtros.idPlanoContas)
  if (filtros.busca) params.set('busca', filtros.busca)
  if (filtros.dataInicial) params.set('dataInicial', filtros.dataInicial)
  if (filtros.dataFinal) params.set('dataFinal', filtros.dataFinal)
  if (filtros.compensado) params.set('compensado', filtros.compensado)
  if (filtros.pagina) params.set('pagina', String(filtros.pagina))
  if (filtros.tamanho) params.set('limite', String(filtros.tamanho))
  if (filtros.ordenarPor) params.set('ordenarPor', filtros.ordenarPor)
  if (filtros.direcao) params.set('direcao', filtros.direcao)
  const query = params.toString()
  return api<PaginaContaCorrenteMovimento>(`/api/v1/contas-corrente-movimento${query ? `?${query}` : ''}`)
}

export function buscarMovimento(localizador: number): Promise<ContaCorrenteMovimento> {
  return api<ContaCorrenteMovimento>(`/api/v1/contas-corrente-movimento/${localizador}`)
}

export function criarMovimento(payload: ReturnType<typeof paraRequisicao>): Promise<ContaCorrenteMovimento> {
  return api<ContaCorrenteMovimento>('/api/v1/contas-corrente-movimento', { method: 'POST', body: JSON.stringify(payload) })
}

export function atualizarMovimento(
  localizador: number,
  payload: ReturnType<typeof paraRequisicao>,
): Promise<ContaCorrenteMovimento> {
  return api<ContaCorrenteMovimento>(`/api/v1/contas-corrente-movimento/${localizador}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export function excluirMovimento(localizador: number): Promise<ExclusaoContaCorrenteMovimento> {
  return api<ExclusaoContaCorrenteMovimento>(`/api/v1/contas-corrente-movimento/${localizador}`, { method: 'DELETE' })
}
