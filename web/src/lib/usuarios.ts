import { api } from './api'
import { horaParaIso, isoParaHora } from './masks'
import { maiusculas } from './texto'

export type StatusUsuario = 'ATIVOS' | 'INATIVOS' | 'TODOS'

export interface EmpresaAcesso {
  idEmpresa: number
  nomeEmpresa: string
}

/** Um dia da semana (1=segunda..7=domingo, ISO) do horário de acesso permitido — os dois
 *  horários nulos juntos = sem acesso naquele dia (docs/telas/usuario.md, 2026-08-11). */
export interface HorarioAcesso {
  diaSemana: number
  horaInicio: string | null
  horaFim: string | null
}

export interface Usuario {
  idUsuario: number
  nome: string
  email: string
  ativo: boolean
  administrador: boolean
  empresas: EmpresaAcesso[]
  controlaHorarioAcesso: boolean
  horarios: HorarioAcesso[]
  criadoEm: string
  atualizadoEm: string
}

/** Uma linha da tabela de horário no formulário — sempre as 7 posições (Segunda..Domingo) em
 *  memória; `horaInicio`/`horaFim` em branco = sem acesso naquele dia. */
export interface HorarioAcessoFormState {
  diaSemana: number
  horaInicio: string
  horaFim: string
}

function horariosEmBranco(): HorarioAcessoFormState[] {
  return Array.from({ length: 7 }, (_, i) => ({ diaSemana: i + 1, horaInicio: '', horaFim: '' }))
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
  controlaHorarioAcesso: boolean
  horarios: HorarioAcessoFormState[]
}

export const USUARIO_VAZIO: UsuarioFormState = {
  nome: '',
  email: '',
  senha: '',
  ativo: true,
  idsEmpresa: [],
  controlaHorarioAcesso: false,
  horarios: horariosEmBranco(),
}

export function paraFormulario(u: Usuario): UsuarioFormState {
  const porDia = new Map(u.horarios.map((h) => [h.diaSemana, h]))
  return {
    nome: u.nome,
    email: u.email,
    senha: '',
    ativo: u.ativo,
    idsEmpresa: u.empresas.map((e) => e.idEmpresa),
    controlaHorarioAcesso: u.controlaHorarioAcesso,
    horarios: horariosEmBranco().map((vazio) => {
      const h = porDia.get(vazio.diaSemana)
      return h
        ? { diaSemana: h.diaSemana, horaInicio: isoParaHora(h.horaInicio), horaFim: isoParaHora(h.horaFim) }
        : vazio
    }),
  }
}

export function paraRequisicao(f: UsuarioFormState) {
  return {
    nome: maiusculas(f.nome.trim()),
    email: f.email.trim().toLowerCase(),
    senha: f.senha.trim() ? f.senha : null,
    ativo: f.ativo,
    idsEmpresa: f.idsEmpresa,
    controlaHorarioAcesso: f.controlaHorarioAcesso,
    horarios: f.horarios.map((h) => ({
      diaSemana: h.diaSemana,
      horaInicio: horaParaIso(h.horaInicio),
      horaFim: horaParaIso(h.horaFim),
    })),
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
