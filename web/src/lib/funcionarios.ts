import { api } from './api'
import {
  desmascararPercentual,
  formatarPercentual,
  mascararCpfCnpj,
  mascararTelefone,
  somenteDigitos,
} from './masks'
import { maiusculas } from './texto'

export type StatusFuncionario = 'ATIVOS' | 'INATIVOS' | 'TODOS'

export interface Funcionario {
  idFuncionario: number
  nome: string
  cpf: string | null
  telefone: string | null
  cargo: string | null
  percComissao: number
  ativo: boolean
  criadoEm: string
  atualizadoEm: string
}

/** Estado do formulário — tudo string para casar com inputs controlados; convertido na hora de enviar. */
export interface FuncionarioFormState {
  nome: string
  cpf: string
  telefone: string
  cargo: string
  percComissao: string
  ativo: boolean
}

export const FUNCIONARIO_VAZIO: FuncionarioFormState = {
  nome: '',
  cpf: '',
  telefone: '',
  cargo: '',
  percComissao: '',
  ativo: true,
}

export function paraFormulario(f: Funcionario): FuncionarioFormState {
  return {
    nome: f.nome,
    // Funcionário é sempre pessoa física — mesma máscara/dígito verificador do CPF de cliente.
    cpf: f.cpf ? mascararCpfCnpj(f.cpf, true) : '',
    telefone: f.telefone ? mascararTelefone(f.telefone) : '',
    cargo: f.cargo ?? '',
    percComissao: f.percComissao ? formatarPercentual(f.percComissao) : '',
    ativo: f.ativo,
  }
}

/**
 * Monta o corpo da requisição a partir do formulário: máscaras viram dígitos, vazio vira
 * null. Nome/cargo normalizados para MAIÚSCULAS aqui como último passo antes do envio —
 * mesma convenção de `cadastros.cliente`.
 */
export function paraRequisicao(f: FuncionarioFormState) {
  const semMascara = (v: string) => (v ? somenteDigitos(v) : null)
  const maiusculoOuNulo = (v: string) => (v.trim() ? maiusculas(v.trim()) : null)
  return {
    nome: maiusculas(f.nome.trim()),
    cpf: semMascara(f.cpf),
    telefone: semMascara(f.telefone),
    cargo: maiusculoOuNulo(f.cargo),
    percComissao: desmascararPercentual(f.percComissao),
    ativo: f.ativo,
  }
}

export interface PaginaFuncionarios {
  itens: Funcionario[]
  pagina: number
  tamanhoPagina: number
  totalItens: number
  totalPaginas: number
}

export interface ExclusaoFuncionario {
  acao: 'excluido' | 'inativado'
  motivo: string | null
}

export type ColunaOrdenacaoFuncionario = 'nome' | 'cpf' | 'telefone' | 'cargo' | 'percComissao' | 'status'
export type DirecaoOrdenacao = 'ASC' | 'DESC'

export interface FiltrosFuncionarios {
  nome?: string
  status?: StatusFuncionario
  pagina?: number
  tamanho?: number
  ordenarPor?: ColunaOrdenacaoFuncionario
  direcao?: DirecaoOrdenacao
}

export function listarFuncionarios(filtros: FiltrosFuncionarios): Promise<PaginaFuncionarios> {
  const params = new URLSearchParams()
  if (filtros.nome) params.set('nome', filtros.nome)
  if (filtros.status) params.set('status', filtros.status)
  if (filtros.pagina) params.set('pagina', String(filtros.pagina))
  if (filtros.tamanho) params.set('limite', String(filtros.tamanho))
  if (filtros.ordenarPor) params.set('ordenarPor', filtros.ordenarPor)
  if (filtros.direcao) params.set('direcao', filtros.direcao)
  const query = params.toString()
  return api<PaginaFuncionarios>(`/api/v1/funcionarios${query ? `?${query}` : ''}`)
}

export function buscarFuncionario(id: number): Promise<Funcionario> {
  return api<Funcionario>(`/api/v1/funcionarios/${id}`)
}

export function criarFuncionario(payload: ReturnType<typeof paraRequisicao>): Promise<Funcionario> {
  return api<Funcionario>('/api/v1/funcionarios', { method: 'POST', body: JSON.stringify(payload) })
}

export function atualizarFuncionario(id: number, payload: ReturnType<typeof paraRequisicao>): Promise<Funcionario> {
  return api<Funcionario>(`/api/v1/funcionarios/${id}`, { method: 'PUT', body: JSON.stringify(payload) })
}

export function excluirFuncionario(id: number): Promise<ExclusaoFuncionario> {
  return api<ExclusaoFuncionario>(`/api/v1/funcionarios/${id}`, { method: 'DELETE' })
}
