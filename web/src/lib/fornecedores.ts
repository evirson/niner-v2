import { api } from './api'
import { mascararCep, mascararCpfCnpj, mascararTelefone, somenteAlfanumerico, somenteDigitos } from './masks'
import { maiusculas } from './texto'

export type StatusFornecedor = 'ATIVOS' | 'INATIVOS' | 'TODOS'

export interface Fornecedor {
  idFornecedor: number
  razaoSocial: string
  /** Código contábil do plano de contas (obrigatório — FK NOT NULL, V016). */
  idPlanoContas: string
  descricaoPlanoContas: string
  nomeFantasia: string | null
  cnpj: string | null
  inscricaoEstadual: string | null
  email: string | null
  telefone: string | null
  cep: string | null
  endereco: string | null
  numero: string | null
  bairro: string | null
  cidade: string | null
  estado: string | null
  ativo: boolean
  criadoEm: string
  atualizadoEm: string
}

/** Estado do formulário — strings para casar com inputs controlados. */
export interface FornecedorFormState {
  razaoSocial: string
  idPlanoContas: string
  nomeFantasia: string
  cnpj: string
  inscricaoEstadual: string
  email: string
  telefone: string
  cep: string
  endereco: string
  numero: string
  bairro: string
  cidade: string
  estado: string
  ativo: boolean
}

export const FORNECEDOR_VAZIO: FornecedorFormState = {
  razaoSocial: '',
  idPlanoContas: '',
  nomeFantasia: '',
  cnpj: '',
  inscricaoEstadual: '',
  email: '',
  telefone: '',
  cep: '',
  endereco: '',
  numero: '',
  bairro: '',
  cidade: '',
  estado: '',
  ativo: true,
}

export function paraFormulario(f: Fornecedor): FornecedorFormState {
  return {
    razaoSocial: f.razaoSocial,
    idPlanoContas: f.idPlanoContas,
    nomeFantasia: f.nomeFantasia ?? '',
    // Fornecedor é sempre pessoa jurídica — máscara de CNPJ (alfanumérico, CLAUDE.md).
    cnpj: f.cnpj ? mascararCpfCnpj(f.cnpj, false) : '',
    inscricaoEstadual: f.inscricaoEstadual ?? '',
    email: f.email ?? '',
    telefone: f.telefone ? mascararTelefone(f.telefone) : '',
    cep: f.cep ? mascararCep(f.cep) : '',
    endereco: f.endereco ?? '',
    numero: f.numero ?? '',
    bairro: f.bairro ?? '',
    cidade: f.cidade ?? '',
    estado: f.estado ?? '',
    ativo: f.ativo,
  }
}

/**
 * Monta o corpo da requisição: máscaras removidas, vazio vira null, texto livre em
 * MAIÚSCULAS (convenção do projeto; exceção: e-mail). CNPJ usa `somenteAlfanumerico` — nunca
 * "só dígitos", que descartaria as letras do CNPJ alfanumérico (CLAUDE.md).
 */
export function paraRequisicao(f: FornecedorFormState) {
  const semMascara = (v: string) => (v ? somenteDigitos(v) : null)
  const semEspacos = (v: string) => (v.trim() ? v.trim() : null)
  const maiusculoOuNulo = (v: string) => (v.trim() ? maiusculas(v.trim()) : null)
  return {
    razaoSocial: maiusculas(f.razaoSocial.trim()),
    idPlanoContas: maiusculas(f.idPlanoContas.trim()),
    nomeFantasia: maiusculoOuNulo(f.nomeFantasia),
    cnpj: f.cnpj ? somenteAlfanumerico(f.cnpj) : null,
    inscricaoEstadual: maiusculoOuNulo(f.inscricaoEstadual),
    email: semEspacos(f.email),
    telefone: semMascara(f.telefone),
    cep: semMascara(f.cep),
    endereco: maiusculoOuNulo(f.endereco),
    numero: maiusculoOuNulo(f.numero),
    bairro: maiusculoOuNulo(f.bairro),
    cidade: maiusculoOuNulo(f.cidade),
    estado: semEspacos(f.estado),
    ativo: f.ativo,
  }
}

export interface PaginaFornecedores {
  itens: Fornecedor[]
  pagina: number
  tamanhoPagina: number
  totalItens: number
  totalPaginas: number
}

export interface ExclusaoFornecedor {
  acao: 'excluido' | 'inativado'
  motivo: string | null
}

export type ColunaOrdenacaoFornecedor = 'razaoSocial' | 'cnpj' | 'planoContas' | 'telefone' | 'cidade' | 'status'
export type DirecaoOrdenacao = 'ASC' | 'DESC'

export interface FiltrosFornecedores {
  razaoSocial?: string
  cnpj?: string
  idPlanoContas?: string
  status?: StatusFornecedor
  pagina?: number
  tamanho?: number
  ordenarPor?: ColunaOrdenacaoFornecedor
  direcao?: DirecaoOrdenacao
}

export function listarFornecedores(filtros: FiltrosFornecedores): Promise<PaginaFornecedores> {
  const params = new URLSearchParams()
  if (filtros.razaoSocial) params.set('razaoSocial', filtros.razaoSocial)
  if (filtros.cnpj) params.set('cnpj', filtros.cnpj)
  if (filtros.idPlanoContas) params.set('idPlanoContas', filtros.idPlanoContas)
  if (filtros.status) params.set('status', filtros.status)
  if (filtros.pagina) params.set('pagina', String(filtros.pagina))
  if (filtros.tamanho) params.set('limite', String(filtros.tamanho))
  if (filtros.ordenarPor) params.set('ordenarPor', filtros.ordenarPor)
  if (filtros.direcao) params.set('direcao', filtros.direcao)
  const query = params.toString()
  return api<PaginaFornecedores>(`/api/v1/fornecedores${query ? `?${query}` : ''}`)
}

export function buscarFornecedor(id: number): Promise<Fornecedor> {
  return api<Fornecedor>(`/api/v1/fornecedores/${id}`)
}

/**
 * Verifica se já existe fornecedor com esse CNPJ (validado na saída do campo — mesmo padrão
 * do CPF/CNPJ do cliente). `ignorarIdFornecedor` evita falso positivo ao editar.
 */
export async function cnpjJaExiste(cnpjLimpo: string, ignorarIdFornecedor?: number): Promise<boolean> {
  if (!cnpjLimpo) return false
  const pagina = await listarFornecedores({ cnpj: cnpjLimpo, status: 'TODOS' })
  return pagina.itens.some((f) => f.idFornecedor !== ignorarIdFornecedor)
}

export function criarFornecedor(payload: ReturnType<typeof paraRequisicao>): Promise<Fornecedor> {
  return api<Fornecedor>('/api/v1/fornecedores', { method: 'POST', body: JSON.stringify(payload) })
}

export function atualizarFornecedor(id: number, payload: ReturnType<typeof paraRequisicao>): Promise<Fornecedor> {
  return api<Fornecedor>(`/api/v1/fornecedores/${id}`, { method: 'PUT', body: JSON.stringify(payload) })
}

export function excluirFornecedor(id: number): Promise<ExclusaoFornecedor> {
  return api<ExclusaoFornecedor>(`/api/v1/fornecedores/${id}`, { method: 'DELETE' })
}
