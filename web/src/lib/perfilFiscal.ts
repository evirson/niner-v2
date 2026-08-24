import { api } from './api'

/** Perfis Fiscais (docs/telas/fiscal-perfil.md) — cabeçalho reutilizável + regras por contexto
 *  (CRT × UF × destinatário × operação), consumidas pelo motor tributário na emissão. */

export type TipoDestinatarioFiscal = 'CONSUMIDOR_FINAL' | 'CONTRIBUINTE' | 'NAO_CONTRIBUINTE'

/** 11 valores no banco (`tipo_operacao_fiscal`); só os 2 primeiros têm caso de uso no v1 — os
 *  outros nove existem porque `ALTER TYPE` depois é caro, mas não aparecem no `<select>`. */
export type TipoOperacaoFiscal =
  | 'VENDA_CONSUMIDOR'
  | 'DEVOLUCAO_VENDA'
  | 'VENDA_CONTRIBUINTE'
  | 'VENDA_NAO_PRESENCIAL'
  | 'TRANSFERENCIA'
  | 'DEVOLUCAO_FORNECEDOR'
  | 'REMESSA_CONSERTO'
  | 'RETORNO_CONSERTO'
  | 'BAIXA_PERDA'
  | 'BONIFICACAO'
  | 'COMPLEMENTAR'

export const TIPO_DESTINATARIO_OPCOES: Array<{ valor: TipoDestinatarioFiscal; rotulo: string }> = [
  { valor: 'CONSUMIDOR_FINAL', rotulo: 'Consumidor final' },
  { valor: 'CONTRIBUINTE', rotulo: 'Contribuinte' },
  { valor: 'NAO_CONTRIBUINTE', rotulo: 'Não contribuinte' },
]

/** Só os dois que o v1 emite (DF35) — os outros nove do enum ficam escondidos de propósito. */
export const TIPO_OPERACAO_OPCOES: Array<{ valor: TipoOperacaoFiscal; rotulo: string }> = [
  { valor: 'VENDA_CONSUMIDOR', rotulo: 'Venda' },
  { valor: 'DEVOLUCAO_VENDA', rotulo: 'Devolução' },
]

/** DF37 — só os CRT que o produto atende. CST de ICMS só é válido no CRT 2. */
export const CRT_OPCOES_PERFIL = [
  { valor: 1, rotulo: '1 — Simples Nacional' },
  { valor: 2, rotulo: '2 — Simples Nacional (excesso de sublimite)' },
  { valor: 4, rotulo: '4 — MEI' },
] as const

export const CSOSN_OPCOES = ['102', '103', '202', '300', '400', '500', '900']
export const CST_ICMS_OPCOES = ['00', '10', '20', '40', '41', '50', '51', '60', '70', '90']
export const CST_PIS_COFINS_OPCOES = ['99', '04', '06', '07', '08', '09']

export interface PerfilFiscalRegra {
  idRegra: number
  crt: number
  ufDestino: string
  tipoDestinatario: TipoDestinatarioFiscal
  tipoOperacao: TipoOperacaoFiscal
  cfop: string
  /** CFOP quando o cliente e de outra UF (6xxx). Nulo = a regra so cobre operacao interna. */
  cfopInterestadual: string | null
  cstIcms: string | null
  csosn: string | null
  aliquotaIcms: number
  percReducaoBc: number
  mvaSt: number
  aliquotaFcp: number
  cstPis: string | null
  aliquotaPis: number
  cstCofins: string | null
  aliquotaCofins: number
  cstIbscbs: string | null
  cclasstrib: string | null
  codigoBeneficio: string | null
}

export interface PerfilFiscalRegraRequest {
  crt: number
  ufDestino: string
  tipoDestinatario: TipoDestinatarioFiscal
  tipoOperacao: TipoOperacaoFiscal
  cfop: string
  /** CFOP quando o cliente e de outra UF (6xxx). Nulo = a regra so cobre operacao interna. */
  cfopInterestadual: string | null
  cstIcms: string | null
  csosn: string | null
  aliquotaIcms: number
  percReducaoBc: number
  mvaSt: number
  aliquotaFcp: number
  cstPis: string | null
  aliquotaPis: number
  cstCofins: string | null
  aliquotaCofins: number
  cstIbscbs: string | null
  cclasstrib: string | null
  codigoBeneficio: string | null
}

export interface PerfilFiscal {
  idPerfilFiscal: number
  nome: string
  descricao: string | null
  ativo: boolean
  regras: PerfilFiscalRegra[]
  criadoEm: string
  atualizadoEm: string
}

export interface PerfilFiscalLista {
  idPerfilFiscal: number
  nome: string
  descricao: string | null
  ativo: boolean
  quantidadeRegras: number
  /** Derivado das regras do perfil (CRT 1 ou 2 ⇒ Simples Nacional; CRT 4 ⇒ MEI) — não é campo
   *  do cadastro. Um perfil com regra pros três CRT atende os dois regimes ao mesmo tempo. */
  atendeSimples: boolean
  atendeMei: boolean
  criadoEm: string
  atualizadoEm: string
}

export interface PaginaPerfisFiscais {
  itens: PerfilFiscalLista[]
  pagina: number
  tamanhoPagina: number
  totalItens: number
  totalPaginas: number
}

export interface PerfilFiscalOpcao {
  idPerfilFiscal: number
  nome: string
}

export interface ExclusaoPerfilFiscal {
  acao: 'excluido' | 'inativado'
  motivo: string | null
}

export type ColunaOrdenacaoPerfilFiscal = 'nome' | 'ativo' | 'criadoEm'
export type DirecaoOrdenacao = 'ASC' | 'DESC'

export interface FiltrosPerfilFiscal {
  busca?: string
  pagina?: number
  tamanho?: number
  ordenarPor?: ColunaOrdenacaoPerfilFiscal
  direcao?: DirecaoOrdenacao
}

export function listarPerfisFiscais(filtros: FiltrosPerfilFiscal): Promise<PaginaPerfisFiscais> {
  const params = new URLSearchParams()
  if (filtros.busca) params.set('busca', filtros.busca)
  if (filtros.pagina) params.set('pagina', String(filtros.pagina))
  if (filtros.tamanho) params.set('limite', String(filtros.tamanho))
  if (filtros.ordenarPor) params.set('ordenarPor', filtros.ordenarPor)
  if (filtros.direcao) params.set('direcao', filtros.direcao)
  const query = params.toString()
  return api<PaginaPerfisFiscais>(`/api/v1/fiscal/perfis${query ? `?${query}` : ''}`)
}

/** Sem checagem de papel — alimenta o `<select>` de Produto (operado por OPERADOR). */
export function listarOpcoesPerfilFiscal(): Promise<PerfilFiscalOpcao[]> {
  return api<PerfilFiscalOpcao[]>('/api/v1/fiscal/perfis/opcoes')
}

export function buscarPerfilFiscal(id: number): Promise<PerfilFiscal> {
  return api<PerfilFiscal>(`/api/v1/fiscal/perfis/${id}`)
}

export interface PerfilFiscalRequest {
  nome: string
  descricao: string | null
  ativo: boolean
  regras: PerfilFiscalRegraRequest[]
}

export function criarPerfilFiscal(payload: PerfilFiscalRequest): Promise<PerfilFiscal> {
  return api<PerfilFiscal>('/api/v1/fiscal/perfis', { method: 'POST', body: JSON.stringify(payload) })
}

export function atualizarPerfilFiscal(id: number, payload: PerfilFiscalRequest): Promise<PerfilFiscal> {
  return api<PerfilFiscal>(`/api/v1/fiscal/perfis/${id}`, { method: 'PUT', body: JSON.stringify(payload) })
}

export function excluirPerfilFiscal(id: number): Promise<ExclusaoPerfilFiscal> {
  return api<ExclusaoPerfilFiscal>(`/api/v1/fiscal/perfis/${id}`, { method: 'DELETE' })
}
