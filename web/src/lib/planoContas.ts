import { api } from './api'
import { mascararCodigoPlanoContas } from './masks'
import { maiusculas } from './texto'

/** Valores dos ENUMs do banco (V013) — sem acento desde 2026-07-31. */
export type TipoMovimentoConta = 'CREDITO' | 'DEBITO' | 'NEUTRO'
export const TIPOS_MOVIMENTO: TipoMovimentoConta[] = ['CREDITO', 'DEBITO', 'NEUTRO']
export const ROTULO_TIPO_MOVIMENTO: Record<TipoMovimentoConta, string> = {
  CREDITO: 'Crédito',
  DEBITO: 'Débito',
  NEUTRO: 'Neutro',
}

export type NaturezaConta = 'SINTETICA' | 'ANALITICA'
export const NATUREZAS: NaturezaConta[] = ['SINTETICA', 'ANALITICA']
export const ROTULO_NATUREZA: Record<NaturezaConta, string> = {
  SINTETICA: 'Sintética (agrupadora)',
  ANALITICA: 'Analítica (recebe lançamento)',
}

export type GrupoDreConta =
  | 'RECEITA_BRUTA'
  | 'DEDUCOES'
  | 'CUSTO_VARIAVEL'
  | 'DESPESA_FIXA'
  | 'DEPRECIACAO'
  | 'RESULTADO_FINANCEIRO'
  | 'NAO_OPERACIONAL'
  | 'TRIBUTO_LUCRO'
  | 'NAO_APLICA'
export const GRUPOS_DRE: Exclude<GrupoDreConta, 'NAO_APLICA'>[] = [
  'RECEITA_BRUTA',
  'DEDUCOES',
  'CUSTO_VARIAVEL',
  'DESPESA_FIXA',
  'DEPRECIACAO',
  'RESULTADO_FINANCEIRO',
  'NAO_OPERACIONAL',
  'TRIBUTO_LUCRO',
]
export const ROTULO_GRUPO_DRE: Record<GrupoDreConta, string> = {
  RECEITA_BRUTA: 'Receita bruta',
  DEDUCOES: 'Deduções da receita bruta',
  CUSTO_VARIAVEL: 'Custo variável',
  DESPESA_FIXA: 'Despesa fixa',
  DEPRECIACAO: 'Depreciação e amortização',
  RESULTADO_FINANCEIRO: 'Resultado financeiro',
  NAO_OPERACIONAL: 'Não operacional',
  TRIBUTO_LUCRO: 'Tributo sobre o lucro',
  NAO_APLICA: 'Não se aplica',
}

export type GrupoDfcConta = 'OPERACIONAL' | 'INVESTIMENTO' | 'FINANCIAMENTO' | 'NAO_APLICA'
export const GRUPOS_DFC: Exclude<GrupoDfcConta, 'NAO_APLICA'>[] = ['OPERACIONAL', 'INVESTIMENTO', 'FINANCIAMENTO']
export const ROTULO_GRUPO_DFC: Record<GrupoDfcConta, string> = {
  OPERACIONAL: 'Operacional',
  INVESTIMENTO: 'Investimento',
  FINANCIAMENTO: 'Financiamento',
  NAO_APLICA: 'Não se aplica',
}

export interface PlanoContas {
  /** Código contábil (ex.: "3.03.001") — a própria PK de negócio, imutável após criar. */
  idPlanoContas: string
  descricao: string
  descricaoCurta: string | null
  tipoMovimento: TipoMovimentoConta
  natureza: NaturezaConta
  /** 1=grupo 2=família 3=conta — derivado da máscara, nunca editável. */
  nivel: number
  idPlanoContasPai: string | null
  incluiDre: boolean
  grupoDre: GrupoDreConta
  incluiFluxoCaixa: boolean
  grupoDfc: GrupoDfcConta
  /** +1/-1/0 — derivado do tipo de movimento, nunca editável. */
  sinal: number
  /** Derivado da natureza (natureza=ANALITICA), nunca editável. */
  aceitaLancamento: boolean
  padraoSistema: boolean
  ativo: boolean
  observacao: string | null
  criadoEm: string
  atualizadoEm: string
}

/** Estado do formulário — strings/booleans para casar com inputs controlados. */
export interface PlanoContasFormState {
  codigo: string
  descricao: string
  descricaoCurta: string
  tipoMovimento: TipoMovimentoConta | ''
  natureza: NaturezaConta | ''
  incluiDre: boolean
  grupoDre: GrupoDreConta | ''
  incluiFluxoCaixa: boolean
  grupoDfc: GrupoDfcConta | ''
  observacao: string
}

export const PLANO_CONTAS_VAZIO: PlanoContasFormState = {
  codigo: '',
  descricao: '',
  descricaoCurta: '',
  tipoMovimento: '',
  natureza: '',
  incluiDre: false,
  grupoDre: '',
  incluiFluxoCaixa: false,
  grupoDfc: '',
  observacao: '',
}

export function paraFormulario(p: PlanoContas): PlanoContasFormState {
  return {
    codigo: p.idPlanoContas,
    descricao: p.descricao,
    descricaoCurta: p.descricaoCurta ?? '',
    tipoMovimento: p.tipoMovimento,
    natureza: p.natureza,
    incluiDre: p.incluiDre,
    grupoDre: p.incluiDre ? p.grupoDre : '',
    incluiFluxoCaixa: p.incluiFluxoCaixa,
    grupoDfc: p.incluiFluxoCaixa ? p.grupoDfc : '',
    observacao: p.observacao ?? '',
  }
}

/** Monta o corpo da requisição — código/descrições em MAIÚSCULAS (convenção do projeto). */
export function paraRequisicao(f: PlanoContasFormState) {
  return {
    codigo: mascararCodigoPlanoContas(f.codigo),
    descricao: maiusculas(f.descricao.trim()),
    descricaoCurta: f.descricaoCurta.trim() ? maiusculas(f.descricaoCurta.trim()) : null,
    tipoMovimento: f.tipoMovimento,
    natureza: f.natureza,
    incluiDre: f.incluiDre,
    grupoDre: f.incluiDre ? f.grupoDre || null : null,
    incluiFluxoCaixa: f.incluiFluxoCaixa,
    grupoDfc: f.incluiFluxoCaixa ? f.grupoDfc || null : null,
    observacao: f.observacao.trim() ? maiusculas(f.observacao.trim()) : null,
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
  acao: 'excluido' | 'inativado'
  motivo: string | null
}

export type StatusPlanoContas = 'ATIVOS' | 'INATIVOS' | 'TODOS'

export type ColunaOrdenacaoPlanoContas =
  | 'codigo'
  | 'descricao'
  | 'tipoMovimento'
  | 'natureza'
  | 'nivel'
  | 'incluiDre'
  | 'incluiFluxoCaixa'
  | 'status'
export type DirecaoOrdenacao = 'ASC' | 'DESC'

export interface FiltrosPlanosContas {
  busca?: string
  status?: StatusPlanoContas
  pagina?: number
  tamanho?: number
  ordenarPor?: ColunaOrdenacaoPlanoContas
  direcao?: DirecaoOrdenacao
}

export function listarPlanosContas(filtros: FiltrosPlanosContas): Promise<PaginaPlanosContas> {
  const params = new URLSearchParams()
  if (filtros.busca) params.set('busca', filtros.busca)
  if (filtros.status) params.set('status', filtros.status)
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
