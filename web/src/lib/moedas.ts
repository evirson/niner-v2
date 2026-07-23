import { api } from './api'
import { desmascararPercentual, formatarPercentual } from './masks'
import { maiusculas } from './texto'

/** Moeda (forma de recebimento — dinheiro, PIX, cartão, crediário...). Já nasce com 7 linhas
 * por tenant no signup; o vínculo com tipo de carteira é gerido pela tela de Tipo de Carteira.
 * `percDesconto`/`percAcrescimo` são opcionais (2026-07-23) — nunca os dois preenchidos com
 * valor positivo ao mesmo tempo (um é desconto, o outro é acréscimo, nunca ambos). */
export interface Moeda {
  idMoeda: number
  nomeMoeda: string
  percDesconto: number | null
  percAcrescimo: number | null
  criadoEm: string
  atualizadoEm: string
}

/** Estado do formulário — strings para casar com inputs controlados (percentuais mascarados);
 * em branco = não informado (fica `null` na API), não é o mesmo que "0,00". */
export interface MoedaFormState {
  nomeMoeda: string
  percDesconto: string
  percAcrescimo: string
}

export const MOEDA_VAZIA: MoedaFormState = {
  nomeMoeda: '',
  percDesconto: '',
  percAcrescimo: '',
}

export function paraFormulario(m: Moeda): MoedaFormState {
  return {
    nomeMoeda: m.nomeMoeda,
    percDesconto: m.percDesconto == null ? '' : formatarPercentual(m.percDesconto),
    percAcrescimo: m.percAcrescimo == null ? '' : formatarPercentual(m.percAcrescimo),
  }
}

/** Campo em branco vira `null` (não `0`) — o servidor distingue "não informado" de "zero". */
function desmascararPercentualOuNulo(valor: string): number | null {
  return valor.trim() ? desmascararPercentual(valor) : null
}

export function paraRequisicao(f: MoedaFormState) {
  return {
    nomeMoeda: maiusculas(f.nomeMoeda.trim()),
    percDesconto: desmascararPercentualOuNulo(f.percDesconto),
    percAcrescimo: desmascararPercentualOuNulo(f.percAcrescimo),
  }
}

export interface PaginaMoedas {
  itens: Moeda[]
  pagina: number
  tamanhoPagina: number
  totalItens: number
  totalPaginas: number
}

export interface ExclusaoMoeda {
  acao: 'excluido'
  motivo: string | null
}

export type ColunaOrdenacaoMoeda = 'nomeMoeda' | 'percDesconto' | 'percAcrescimo'
export type DirecaoOrdenacao = 'ASC' | 'DESC'

export interface FiltrosMoedas {
  busca?: string
  pagina?: number
  tamanho?: number
  ordenarPor?: ColunaOrdenacaoMoeda
  direcao?: DirecaoOrdenacao
}

export function listarMoedas(filtros: FiltrosMoedas): Promise<PaginaMoedas> {
  const params = new URLSearchParams()
  if (filtros.busca) params.set('busca', filtros.busca)
  if (filtros.pagina) params.set('pagina', String(filtros.pagina))
  if (filtros.tamanho) params.set('limite', String(filtros.tamanho))
  if (filtros.ordenarPor) params.set('ordenarPor', filtros.ordenarPor)
  if (filtros.direcao) params.set('direcao', filtros.direcao)
  const query = params.toString()
  return api<PaginaMoedas>(`/api/v1/moedas${query ? `?${query}` : ''}`)
}

export function buscarMoeda(id: number): Promise<Moeda> {
  return api<Moeda>(`/api/v1/moedas/${id}`)
}

export function criarMoeda(payload: ReturnType<typeof paraRequisicao>): Promise<Moeda> {
  return api<Moeda>('/api/v1/moedas', { method: 'POST', body: JSON.stringify(payload) })
}

export function atualizarMoeda(id: number, payload: ReturnType<typeof paraRequisicao>): Promise<Moeda> {
  return api<Moeda>(`/api/v1/moedas/${id}`, { method: 'PUT', body: JSON.stringify(payload) })
}

export function excluirMoeda(id: number): Promise<ExclusaoMoeda> {
  return api<ExclusaoMoeda>(`/api/v1/moedas/${id}`, { method: 'DELETE' })
}
