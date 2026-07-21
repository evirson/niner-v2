import { api } from './api'
import { maiusculas } from './texto'

/**
 * Tipos de movimento do plano de contas — mesmos valores (com acentos) do ENUM
 * `tipo_movimento_conta` do banco (V013).
 */
export type TipoMovimentoConta = 'CRÉDITO' | 'DÉBITO' | 'NEUTRO'
export const TIPOS_MOVIMENTO: TipoMovimentoConta[] = ['CRÉDITO', 'DÉBITO', 'NEUTRO']

export interface PlanoContas {
  /** Código contábil (ex.: "3.1.001") — a própria PK de negócio, imutável após criar. */
  idPlanoContas: string
  descricao: string
  tipoMovimento: TipoMovimentoConta
  incluiDre: boolean
  incluiFluxoCaixa: boolean
  criadoEm: string
  atualizadoEm: string
}

/** Estado do formulário — strings para casar com inputs controlados. */
export interface PlanoContasFormState {
  codigo: string
  descricao: string
  tipoMovimento: TipoMovimentoConta | ''
  incluiDre: boolean
  incluiFluxoCaixa: boolean
}

export const PLANO_CONTAS_VAZIO: PlanoContasFormState = {
  codigo: '',
  descricao: '',
  tipoMovimento: '',
  incluiDre: false,
  incluiFluxoCaixa: false,
}

export function paraFormulario(p: PlanoContas): PlanoContasFormState {
  return {
    codigo: p.idPlanoContas,
    descricao: p.descricao,
    tipoMovimento: p.tipoMovimento,
    incluiDre: p.incluiDre,
    incluiFluxoCaixa: p.incluiFluxoCaixa,
  }
}

/** Monta o corpo da requisição — código/descrição em MAIÚSCULAS (convenção do projeto). */
export function paraRequisicao(f: PlanoContasFormState) {
  return {
    codigo: maiusculas(f.codigo.trim()),
    descricao: maiusculas(f.descricao.trim()),
    tipoMovimento: f.tipoMovimento,
    incluiDre: f.incluiDre,
    incluiFluxoCaixa: f.incluiFluxoCaixa,
  }
}

export interface PaginaPlanosContas {
  itens: PlanoContas[]
  pagina: number
  tamanhoPagina: number
  totalItens: number
  totalPaginas: number
}

export interface ExclusaoPlanoContas {
  acao: 'excluido'
  motivo: string | null
}

export type ColunaOrdenacaoPlanoContas = 'codigo' | 'descricao' | 'tipoMovimento' | 'incluiDre' | 'incluiFluxoCaixa'
export type DirecaoOrdenacao = 'ASC' | 'DESC'

export interface FiltrosPlanosContas {
  busca?: string
  pagina?: number
  tamanho?: number
  ordenarPor?: ColunaOrdenacaoPlanoContas
  direcao?: DirecaoOrdenacao
}

export function listarPlanosContas(filtros: FiltrosPlanosContas): Promise<PaginaPlanosContas> {
  const params = new URLSearchParams()
  if (filtros.busca) params.set('busca', filtros.busca)
  if (filtros.pagina) params.set('pagina', String(filtros.pagina))
  if (filtros.tamanho) params.set('limite', String(filtros.tamanho))
  if (filtros.ordenarPor) params.set('ordenarPor', filtros.ordenarPor)
  if (filtros.direcao) params.set('direcao', filtros.direcao)
  const query = params.toString()
  return api<PaginaPlanosContas>(`/api/v1/planos-contas${query ? `?${query}` : ''}`)
}

export function buscarPlanoContas(codigo: string): Promise<PlanoContas> {
  return api<PlanoContas>(`/api/v1/planos-contas/${encodeURIComponent(codigo)}`)
}

export function criarPlanoContas(payload: ReturnType<typeof paraRequisicao>): Promise<PlanoContas> {
  return api<PlanoContas>('/api/v1/planos-contas', { method: 'POST', body: JSON.stringify(payload) })
}

export function atualizarPlanoContas(
  codigo: string,
  payload: ReturnType<typeof paraRequisicao>,
): Promise<PlanoContas> {
  return api<PlanoContas>(`/api/v1/planos-contas/${encodeURIComponent(codigo)}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export function excluirPlanoContas(codigo: string): Promise<ExclusaoPlanoContas> {
  return api<ExclusaoPlanoContas>(`/api/v1/planos-contas/${encodeURIComponent(codigo)}`, { method: 'DELETE' })
}
