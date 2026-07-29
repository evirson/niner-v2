import { api } from './api'
import { desmascararPercentual, formatarPercentual } from './masks'
import { maiusculas } from './texto'

/** Categoria fixa do tipo de carteira (2026-07-23) — usada pelo histórico do cliente pra
 * isolar parcelas de crediário das demais formas de pagamento. */
export type CategoriaCarteira = 'AVISTA' | 'CARTAO_DEBITO' | 'CARTAO_CREDITO' | 'CREDIARIO'

export const ROTULO_CATEGORIA_CARTEIRA: Record<CategoriaCarteira, string> = {
  AVISTA: 'À Vista',
  CARTAO_DEBITO: 'Cartão Débito',
  CARTAO_CREDITO: 'Cartão Crédito',
  CREDIARIO: 'Crediário',
}

/**
 * Tipo de carteira (forma de pagamento completa: categoria/prazo/parcelas/taxa/desconto/
 * acréscimo). Absorveu o cadastro de `moeda` em 2026-07-28 — `percDesconto`/`percAcrescimo`
 * vieram de lá, nunca preenchidos com valor positivo ao mesmo tempo. A mesma bandeira
 * (`nomeCarteira`) pode existir uma vez por categoria (ex.: "HIPER" em débito e "HIPER" em
 * crédito, prazo/taxa independentes) — únicos por `(nomeCarteira, categoriaCarteira)`, não só
 * por nome. `taxaAdministradora`/`percDesconto`/`percAcrescimo` são opcionais.
 */
export interface TipoCarteira {
  idCarteira: number
  nomeCarteira: string
  categoriaCarteira: CategoriaCarteira
  prazoPagamento: number
  pcMinima: number
  pcMaxima: number
  taxaAdministradora: number | null
  percDesconto: number | null
  percAcrescimo: number | null
  /** Recebimento de Crediário (2026-07-29, RN007) — só carteiras marcadas aqui aparecem como opção de pagamento naquela tela. */
  permiteReceberCrediario: boolean
  criadoEm: string
  atualizadoEm: string
}

/** Estado do formulário — strings para os campos numéricos casarem com inputs controlados. */
export interface TipoCarteiraFormState {
  nomeCarteira: string
  categoriaCarteira: CategoriaCarteira | ''
  prazoPagamento: string
  pcMinima: string
  pcMaxima: string
  taxaAdministradora: string
  percDesconto: string
  percAcrescimo: string
  permiteReceberCrediario: boolean
}

export const TIPO_CARTEIRA_VAZIO: TipoCarteiraFormState = {
  nomeCarteira: '',
  categoriaCarteira: '',
  prazoPagamento: '',
  pcMinima: '',
  pcMaxima: '',
  taxaAdministradora: '',
  percDesconto: '',
  percAcrescimo: '',
  permiteReceberCrediario: false,
}

export function paraFormulario(tc: TipoCarteira): TipoCarteiraFormState {
  return {
    nomeCarteira: tc.nomeCarteira,
    categoriaCarteira: tc.categoriaCarteira,
    prazoPagamento: String(tc.prazoPagamento),
    pcMinima: String(tc.pcMinima),
    pcMaxima: String(tc.pcMaxima),
    taxaAdministradora: tc.taxaAdministradora == null ? '' : formatarPercentual(tc.taxaAdministradora),
    percDesconto: tc.percDesconto == null ? '' : formatarPercentual(tc.percDesconto),
    percAcrescimo: tc.percAcrescimo == null ? '' : formatarPercentual(tc.percAcrescimo),
    permiteReceberCrediario: tc.permiteReceberCrediario,
  }
}

/** Campo em branco vira `null` (não `0`) — o servidor distingue "não informado" de "zero". */
function desmascararPercentualOuNulo(valor: string): number | null {
  return valor.trim() ? desmascararPercentual(valor) : null
}

export function paraRequisicao(f: TipoCarteiraFormState) {
  return {
    nomeCarteira: maiusculas(f.nomeCarteira.trim()),
    categoriaCarteira: f.categoriaCarteira || null,
    prazoPagamento: Number(f.prazoPagamento || 0),
    pcMinima: Number(f.pcMinima || 0),
    pcMaxima: Number(f.pcMaxima || 0),
    // Em branco vira `null` (opcional) — não é o mesmo que taxa 0.
    taxaAdministradora: f.taxaAdministradora.trim() ? desmascararPercentual(f.taxaAdministradora) : null,
    percDesconto: desmascararPercentualOuNulo(f.percDesconto),
    percAcrescimo: desmascararPercentualOuNulo(f.percAcrescimo),
    permiteReceberCrediario: f.permiteReceberCrediario,
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

export type ColunaOrdenacaoTipoCarteira =
  | 'nomeCarteira'
  | 'prazoPagamento'
  | 'taxaAdministradora'
  | 'categoriaCarteira'
  | 'percDesconto'
  | 'percAcrescimo'
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
