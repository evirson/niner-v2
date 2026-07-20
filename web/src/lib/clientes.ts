import { api } from './api'
import { mascararCep, mascararCpfCnpj, mascararTelefone, somenteDigitos } from './masks'
import { maiusculas } from './texto'

export type Genero = 'MASCULINO' | 'FEMININO' | 'OUTROS'
export type StatusCliente = 'ATIVOS' | 'INATIVOS' | 'TODOS'

export interface Categoria {
  idCategoriaCliente: number
  nomeCategoria: string
}

export interface Cliente {
  idCliente: number
  fisicaJuridica: boolean
  nome: string
  idCategoriaCliente: number
  nomeCategoria: string
  cpfCnpj: string | null
  rgIe: string | null
  dataNascimento: string | null
  genero: Genero | null
  email: string | null
  telefone: string | null
  whatsapp: string | null
  instagram: string | null
  facebook: string | null
  tiktok: string | null
  cep: string | null
  endereco: string | null
  numero: string | null
  complemento: string | null
  bairro: string | null
  cidade: string | null
  estado: string | null
  limiteCredito: number
  ativo: boolean
  criadoEm: string
  atualizadoEm: string
}

/** Estado do formulário — tudo string para casar com inputs controlados; convertido na hora de enviar. */
export interface ClienteFormState {
  fisicaJuridica: boolean
  nome: string
  idCategoriaCliente: number | null
  cpfCnpj: string
  rgIe: string
  dataNascimento: string
  genero: Genero | ''
  email: string
  telefone: string
  whatsapp: string
  instagram: string
  facebook: string
  tiktok: string
  cep: string
  endereco: string
  numero: string
  complemento: string
  bairro: string
  cidade: string
  estado: string
  limiteCredito: string
  ativo: boolean
}

export const CLIENTE_VAZIO: ClienteFormState = {
  fisicaJuridica: true,
  nome: '',
  idCategoriaCliente: null,
  cpfCnpj: '',
  rgIe: '',
  dataNascimento: '',
  genero: '',
  email: '',
  telefone: '',
  whatsapp: '',
  instagram: '',
  facebook: '',
  tiktok: '',
  cep: '',
  endereco: '',
  numero: '',
  complemento: '',
  bairro: '',
  cidade: '',
  estado: '',
  limiteCredito: '',
  ativo: true,
}

export function paraFormulario(c: Cliente): ClienteFormState {
  return {
    fisicaJuridica: c.fisicaJuridica,
    nome: c.nome,
    idCategoriaCliente: c.idCategoriaCliente,
    cpfCnpj: c.cpfCnpj ? mascararCpfCnpj(c.cpfCnpj, c.fisicaJuridica) : '',
    rgIe: c.rgIe ?? '',
    dataNascimento: c.dataNascimento ?? '',
    genero: c.genero ?? '',
    email: c.email ?? '',
    telefone: c.telefone ? mascararTelefone(c.telefone) : '',
    whatsapp: c.whatsapp ? mascararTelefone(c.whatsapp) : '',
    instagram: c.instagram ?? '',
    facebook: c.facebook ?? '',
    tiktok: c.tiktok ?? '',
    cep: c.cep ? mascararCep(c.cep) : '',
    endereco: c.endereco ?? '',
    numero: c.numero ?? '',
    complemento: c.complemento ?? '',
    bairro: c.bairro ?? '',
    cidade: c.cidade ?? '',
    estado: c.estado ?? '',
    limiteCredito: c.limiteCredito ? String(c.limiteCredito) : '',
    ativo: c.ativo,
  }
}

/**
 * Monta o corpo da requisição a partir do formulário: máscaras viram dígitos, vazio vira
 * null. Campos de texto livre são normalizados para MAIÚSCULAS aqui como último passo
 * antes do envio — mesmo que o `onChange` de cada campo já force maiúsculas ao digitar,
 * isso cobre valores preenchidos por outra via (ex.: autopreenchimento do CEP). Exceção:
 * e-mail, que mantém a caixa original.
 */
export function paraRequisicao(f: ClienteFormState) {
  const semMascara = (v: string) => (v ? somenteDigitos(v) : null)
  const semEspacos = (v: string) => (v.trim() ? v.trim() : null)
  const maiusculoOuNulo = (v: string) => (v.trim() ? maiusculas(v.trim()) : null)
  return {
    fisicaJuridica: f.fisicaJuridica,
    nome: maiusculas(f.nome.trim()),
    idCategoriaCliente: f.idCategoriaCliente,
    cpfCnpj: semMascara(f.cpfCnpj),
    rgIe: maiusculoOuNulo(f.rgIe),
    dataNascimento: f.fisicaJuridica && f.dataNascimento ? f.dataNascimento : null,
    genero: f.fisicaJuridica && f.genero ? f.genero : null,
    email: semEspacos(f.email),
    telefone: semMascara(f.telefone),
    whatsapp: semMascara(f.whatsapp),
    instagram: maiusculoOuNulo(f.instagram),
    facebook: maiusculoOuNulo(f.facebook),
    tiktok: maiusculoOuNulo(f.tiktok),
    cep: semMascara(f.cep),
    endereco: maiusculoOuNulo(f.endereco),
    numero: maiusculoOuNulo(f.numero),
    complemento: maiusculoOuNulo(f.complemento),
    bairro: maiusculoOuNulo(f.bairro),
    cidade: maiusculoOuNulo(f.cidade),
    estado: semEspacos(f.estado),
    limiteCredito: f.limiteCredito ? Number(f.limiteCredito.replace(',', '.')) : 0,
    ativo: f.ativo,
  }
}

export interface PaginaClientes {
  itens: Cliente[]
  proximoCursor: number | null
}

export interface ExclusaoCliente {
  acao: 'excluido' | 'inativado'
  motivo: string | null
}

export interface FiltrosClientes {
  nome?: string
  cpfCnpj?: string
  idCategoriaCliente?: number
  status?: StatusCliente
  cursor?: number
}

export function listarClientes(filtros: FiltrosClientes): Promise<PaginaClientes> {
  const params = new URLSearchParams()
  if (filtros.nome) params.set('nome', filtros.nome)
  if (filtros.cpfCnpj) params.set('cpfCnpj', filtros.cpfCnpj)
  if (filtros.idCategoriaCliente) params.set('idCategoriaCliente', String(filtros.idCategoriaCliente))
  if (filtros.status) params.set('status', filtros.status)
  if (filtros.cursor) params.set('cursor', String(filtros.cursor))
  const query = params.toString()
  return api<PaginaClientes>(`/api/v1/clientes${query ? `?${query}` : ''}`)
}

export function buscarCliente(id: number): Promise<Cliente> {
  return api<Cliente>(`/api/v1/clientes/${id}`)
}

export function criarCliente(payload: ReturnType<typeof paraRequisicao>): Promise<Cliente> {
  return api<Cliente>('/api/v1/clientes', { method: 'POST', body: JSON.stringify(payload) })
}

export function atualizarCliente(id: number, payload: ReturnType<typeof paraRequisicao>): Promise<Cliente> {
  return api<Cliente>(`/api/v1/clientes/${id}`, { method: 'PUT', body: JSON.stringify(payload) })
}

export function excluirCliente(id: number): Promise<ExclusaoCliente> {
  return api<ExclusaoCliente>(`/api/v1/clientes/${id}`, { method: 'DELETE' })
}

export function listarCategorias(): Promise<Categoria[]> {
  return api<Categoria[]>('/api/v1/categorias-cliente')
}

export function criarCategoria(nomeCategoria: string): Promise<Categoria> {
  return api<Categoria>('/api/v1/categorias-cliente', {
    method: 'POST',
    body: JSON.stringify({ nomeCategoria }),
  })
}

export function renomearCategoria(id: number, nomeCategoria: string): Promise<Categoria> {
  return api<Categoria>(`/api/v1/categorias-cliente/${id}`, {
    method: 'PUT',
    body: JSON.stringify({ nomeCategoria }),
  })
}
