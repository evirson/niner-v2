import { api } from './api'
import { maiusculas } from './texto'

export type StatusUsuario = 'ATIVOS' | 'INATIVOS' | 'TODOS'

export interface EmpresaAcesso {
  idEmpresa: number
  nomeEmpresa: string
}

export interface Usuario {
  idUsuario: number
  nome: string
  email: string
  ativo: boolean
  administrador: boolean
  empresas: EmpresaAcesso[]
  criadoEm: string
  atualizadoEm: string
}

/**
 * Estado do formulário — `senha` fica sempre vazia ao carregar um usuário existente (nunca
 * volta do servidor); em branco na edição significa "manter a senha atual". Sem campo
 * `administrador` de propósito (2026-07-28) — só existe um ADMIN por tenant, criado no
 * signup; esta tela nunca cria nem edita esse papel (ver `Usuario.administrador`, só leitura).
 */
export interface UsuarioFormState {
  nome: string
  email: string
  senha: string
  ativo: boolean
  idsEmpresa: number[]
}

export const USUARIO_VAZIO: UsuarioFormState = {
  nome: '',
  email: '',
  senha: '',
  ativo: true,
  idsEmpresa: [],
}

export function paraFormulario(u: Usuario): UsuarioFormState {
  return {
    nome: u.nome,
    email: u.email,
    senha: '',
    ativo: u.ativo,
    idsEmpresa: u.empresas.map((e) => e.idEmpresa),
  }
}

export function paraRequisicao(f: UsuarioFormState) {
  return {
    nome: maiusculas(f.nome.trim()),
    email: f.email.trim().toLowerCase(),
    senha: f.senha.trim() ? f.senha : null,
    ativo: f.ativo,
    idsEmpresa: f.idsEmpresa,
  }
}

export interface PaginaUsuarios {
  itens: Usuario[]
  pagina: number
  tamanhoPagina: number
  totalItens: number
  totalPaginas: number
}

export interface ExclusaoUsuario {
  acao: 'excluido' | 'inativado'
  motivo: string | null
}

export type ColunaOrdenacaoUsuario = 'nome' | 'email' | 'papel' | 'status'
export type DirecaoOrdenacao = 'ASC' | 'DESC'

export interface FiltrosUsuarios {
  nome?: string
  status?: StatusUsuario
  pagina?: number
  tamanho?: number
  ordenarPor?: ColunaOrdenacaoUsuario
  direcao?: DirecaoOrdenacao
}

export function listarUsuarios(filtros: FiltrosUsuarios): Promise<PaginaUsuarios> {
  const params = new URLSearchParams()
  if (filtros.nome) params.set('nome', filtros.nome)
  if (filtros.status) params.set('status', filtros.status)
  if (filtros.pagina) params.set('pagina', String(filtros.pagina))
  if (filtros.tamanho) params.set('limite', String(filtros.tamanho))
  if (filtros.ordenarPor) params.set('ordenarPor', filtros.ordenarPor)
  if (filtros.direcao) params.set('direcao', filtros.direcao)
  const query = params.toString()
  return api<PaginaUsuarios>(`/api/v1/usuarios${query ? `?${query}` : ''}`)
}

export function buscarUsuario(id: number): Promise<Usuario> {
  return api<Usuario>(`/api/v1/usuarios/${id}`)
}

export function criarUsuario(payload: ReturnType<typeof paraRequisicao>): Promise<Usuario> {
  return api<Usuario>('/api/v1/usuarios', { method: 'POST', body: JSON.stringify(payload) })
}

export function atualizarUsuario(id: number, payload: ReturnType<typeof paraRequisicao>): Promise<Usuario> {
  return api<Usuario>(`/api/v1/usuarios/${id}`, { method: 'PUT', body: JSON.stringify(payload) })
}

export function excluirUsuario(id: number): Promise<ExclusaoUsuario> {
  return api<ExclusaoUsuario>(`/api/v1/usuarios/${id}`, { method: 'DELETE' })
}
