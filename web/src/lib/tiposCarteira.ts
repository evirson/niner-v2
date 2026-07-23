import { api } from './api'
import { desmascararPercentual, formatarPercentual } from './masks'
import { maiusculas } from './texto'

/** Moeda vinculada a este tipo de carteira (`moeda_detalhe`) — sem ordenação. */
export interface MoedaSelecionada {
  idMoeda: number
  nomeMoeda: string
}

/** Tipo de carteira (prazo/parcelas/taxa do crediário, cartão etc.) — gerencia embutido o
 * N:N com moeda: o fluxo é "criar um tipo de carteira e escolher em quais moedas ele vale".
 * `taxaAdministradora` é opcional (2026-07-23) — nem todo tipo de carteira cobra taxa. */
export interface TipoCarteira {
  idCarteira: number
  nomeCarteira: string
  prazoPagamento: number
  pcMinima: number
  pcMaxima: number
  taxaAdministradora: number | null
  moedas: MoedaSelecionada[]
  criadoEm: string
  atualizadoEm: string
}

/** Estado do formulário — strings para os campos numéricos casarem com inputs controlados. */
export interface TipoCarteiraFormState {
  nomeCarteira: string
  prazoPagamento: string
  pcMinima: string
  pcMaxima: string
  taxaAdministradora: string
  moedas: number[]
}

export const TIPO_CARTEIRA_VAZIO: TipoCarteiraFormState = {
  nomeCarteira: '',
  prazoPagamento: '',
  pcMinima: '',
  pcMaxima: '',
  taxaAdministradora: '',
  moedas: [],
}

export function paraFormulario(tc: TipoCarteira): TipoCarteiraFormState {
  return {
    nomeCarteira: tc.nomeCarteira,
    prazoPagamento: String(tc.prazoPagamento),
    pcMinima: String(tc.pcMinima),
    pcMaxima: String(tc.pcMaxima),
    taxaAdministradora: tc.taxaAdministradora == null ? '' : formatarPercentual(tc.taxaAdministradora),
    moedas: tc.moedas.map((m) => m.idMoeda),
  }
}

export function paraRequisicao(f: TipoCarteiraFormState) {
  return {
    nomeCarteira: maiusculas(f.nomeCarteira.trim()),
    prazoPagamento: Number(f.prazoPagamento || 0),
    pcMinima: Number(f.pcMinima || 0),
    pcMaxima: Number(f.pcMaxima || 0),
    // Em branco vira `null` (opcional) — não é o mesmo que taxa 0.
    taxaAdministradora: f.taxaAdministradora.trim() ? desmascararPercentual(f.taxaAdministradora) : null,
    moedas: f.moedas,
  }
}

export interface PaginaTiposCarteira {
  itens: TipoCarteira[]
  pagina: number
  tamanhoPagina: number
  totalItens: number
  totalPaginas: number
}

export interface ExclusaoTipoCarteira {
  acao: 'excluido'
  motivo: string | null
}

export type ColunaOrdenacaoTipoCarteira = 'nomeCarteira' | 'prazoPagamento' | 'taxaAdministradora'
export type DirecaoOrdenacao = 'ASC' | 'DESC'

export interface FiltrosTiposCarteira {
  busca?: string
  pagina?: number
  tamanho?: number
  ordenarPor?: ColunaOrdenacaoTipoCarteira
  direcao?: DirecaoOrdenacao
}

export function listarTiposCarteira(filtros: FiltrosTiposCarteira): Promise<PaginaTiposCarteira> {
  const params = new URLSearchParams()
  if (filtros.busca) params.set('busca', filtros.busca)
  if (filtros.pagina) params.set('pagina', String(filtros.pagina))
  if (filtros.tamanho) params.set('limite', String(filtros.tamanho))
  if (filtros.ordenarPor) params.set('ordenarPor', filtros.ordenarPor)
  if (filtros.direcao) params.set('direcao', filtros.direcao)
  const query = params.toString()
  return api<PaginaTiposCarteira>(`/api/v1/tipos-carteira${query ? `?${query}` : ''}`)
}

export function buscarTipoCarteira(id: number): Promise<TipoCarteira> {
  return api<TipoCarteira>(`/api/v1/tipos-carteira/${id}`)
}

export function criarTipoCarteira(payload: ReturnType<typeof paraRequisicao>): Promise<TipoCarteira> {
  return api<TipoCarteira>('/api/v1/tipos-carteira', { method: 'POST', body: JSON.stringify(payload) })
}

export function atualizarTipoCarteira(
  id: number,
  payload: ReturnType<typeof paraRequisicao>,
): Promise<TipoCarteira> {
  return api<TipoCarteira>(`/api/v1/tipos-carteira/${id}`, { method: 'PUT', body: JSON.stringify(payload) })
}

export function excluirTipoCarteira(id: number): Promise<ExclusaoTipoCarteira> {
  return api<ExclusaoTipoCarteira>(`/api/v1/tipos-carteira/${id}`, { method: 'DELETE' })
}
