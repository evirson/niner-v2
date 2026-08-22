import type { QueryClient } from '@tanstack/react-query'
import { api } from './api'
import { desmascararPercentual, formatarPercentual, mascararCpfCnpj, somenteAlfanumerico } from './masks'
import { maiusculas } from './texto'

/** Código `tPag` (NFC-e, `docs/MODULOFISCAL.md` §6.4) — não depende da categoria (ex.: AVISTA
 * cobre tanto Dinheiro=01 quanto PIX=17), por isso é uma lista fixa, não derivada. */
export const OPCOES_CODIGO_TPAG: Array<{ codigo: string; rotulo: string }> = [
  { codigo: '01', rotulo: '01 — Dinheiro' },
  { codigo: '03', rotulo: '03 — Cartão de Crédito' },
  { codigo: '04', rotulo: '04 — Cartão de Débito' },
  { codigo: '05', rotulo: '05 — Crédito Loja (Crediário)' },
  { codigo: '17', rotulo: '17 — PIX' },
  { codigo: '90', rotulo: '90 — Sem Pagamento' },
  { codigo: '99', rotulo: '99 — Outros' },
]

/** Código `tBand` (NFC-e, §6.4) — só se aplica quando a categoria é Cartão Débito/Crédito
 * (mesma condição que `ConformidadeFiscalService` usa pra cobrar o campo). */
export const OPCOES_CODIGO_BANDEIRA: Array<{ codigo: string; rotulo: string }> = [
  { codigo: '01', rotulo: '01 — Visa' },
  { codigo: '02', rotulo: '02 — Mastercard' },
  { codigo: '03', rotulo: '03 — American Express' },
  { codigo: '06', rotulo: '06 — Elo' },
  { codigo: '07', rotulo: '07 — Hipercard' },
  { codigo: '99', rotulo: '99 — Outros' },
]

/** Categoria fixa do tipo de carteira (2026-07-23) — usada pelo histórico do cliente pra
 * isolar parcelas de crediário das demais formas de pagamento. VALE_MERCADORIA (2026-08-03):
 * resgate do vale-mercadoria gerado pela Devolução de Produtos — paga na hora igual À Vista, mas
 * exige o número do vale em vez de aceitar qualquer valor digitado (ver FormaPagamentoModal). */
export type CategoriaCarteira = 'AVISTA' | 'CARTAO_DEBITO' | 'CARTAO_CREDITO' | 'CREDIARIO' | 'VALE_MERCADORIA'

export const ROTULO_CATEGORIA_CARTEIRA: Record<CategoriaCarteira, string> = {
  AVISTA: 'À Vista',
  CARTAO_DEBITO: 'Cartão Débito',
  CARTAO_CREDITO: 'Cartão Crédito',
  CREDIARIO: 'Crediário',
  VALE_MERCADORIA: 'Vale-Mercadoria',
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
  /** Fiscal (2026-08-18, `docs/MODULOFISCAL.md` §6.4) — grupo `pag/detPag` da NFC-e. Todos
   * opcionais: a Conformidade Fiscal cobra sem bloquear o cadastro. */
  codigoTpag: string | null
  codigoBandeira: string | null
  cnpjCredenciadora: string | null
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
  codigoTpag: string
  codigoBandeira: string
  cnpjCredenciadora: string
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
  codigoTpag: '',
  codigoBandeira: '',
  cnpjCredenciadora: '',
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
    codigoTpag: tc.codigoTpag ?? '',
    codigoBandeira: tc.codigoBandeira ?? '',
    cnpjCredenciadora: tc.cnpjCredenciadora ? mascararCpfCnpj(tc.cnpjCredenciadora, false) : '',
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
    codigoTpag: f.codigoTpag || null,
    codigoBandeira: f.codigoBandeira || null,
    cnpjCredenciadora: f.cnpjCredenciadora.trim() ? somenteAlfanumerico(f.cnpjCredenciadora) : null,
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

/**
 * Invalida TODAS as chaves de tipo de carteira (auditoria 2026-08-21, item 15).
 *
 * ⚠️ O CRUD invalidava `["tipos-carteira"]`, mas o PDV lê `["tipos-carteira-pdv"]` e a Importação
 * lê `["tipos-carteira-importacao"]` — **chave de array não casa por prefixo de string**, são
 * chaves diferentes. Criar uma forma de pagamento e ir direto ao PDV podia não mostrá-la.
 *
 * Mesma lição de `feedback_react_query_cache_entre_telas`: chave nova derivada do mesmo dado
 * precisa entrar aqui, senão nasce desatualizada em silêncio.
 */
export function invalidarTiposCarteira(queryClient: QueryClient): void {
  queryClient.invalidateQueries({ queryKey: ['tipos-carteira'] })
  queryClient.invalidateQueries({ queryKey: ['tipos-carteira-pdv'] })
  queryClient.invalidateQueries({ queryKey: ['tipos-carteira-importacao'] })
}
