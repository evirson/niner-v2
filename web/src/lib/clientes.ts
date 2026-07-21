import { api } from './api'
import {
  desmascararMoeda,
  formatarMoeda,
  mascararCep,
  mascararCpfCnpj,
  mascararIdWhatsapp,
  mascararTelefone,
  somenteAlfanumerico,
  somenteDigitos,
} from './masks'
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
    whatsapp: c.whatsapp ? mascararIdWhatsapp(c.whatsapp) : '',
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
    limiteCredito: c.limiteCredito ? formatarMoeda(c.limiteCredito) : '',
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
  // CNPJ é alfanumérico (Receita Federal, a partir de julho/2026) — não usar somenteDigitos
  // aqui, senão as letras da raiz/ordem seriam descartadas antes de chegar na API. CPF
  // continua só dígitos.
  const cpfCnpjLimpo = f.cpfCnpj
    ? f.fisicaJuridica
      ? somenteDigitos(f.cpfCnpj)
      : somenteAlfanumerico(f.cpfCnpj)
    : null
  return {
    fisicaJuridica: f.fisicaJuridica,
    nome: maiusculas(f.nome.trim()),
    idCategoriaCliente: f.idCategoriaCliente,
    cpfCnpj: cpfCnpjLimpo,
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
    limiteCredito: desmascararMoeda(f.limiteCredito),
    ativo: f.ativo,
  }
}

export interface PaginaClientes {
  itens: Cliente[]
  pagina: number
  tamanhoPagina: number
  totalItens: number
  totalPaginas: number
}

export interface ExclusaoCliente {
  acao: 'excluido' | 'inativado'
  motivo: string | null
}

export type ColunaOrdenacao = 'nome' | 'cpfCnpj' | 'categoria' | 'telefone' | 'cidade' | 'status'
export type DirecaoOrdenacao = 'ASC' | 'DESC'

export interface FiltrosClientes {
  nome?: string
  cpfCnpj?: string
  idCategoriaCliente?: number
  status?: StatusCliente
  pagina?: number
  tamanho?: number
  ordenarPor?: ColunaOrdenacao
  direcao?: DirecaoOrdenacao
}

export function listarClientes(filtros: FiltrosClientes): Promise<PaginaClientes> {
  const params = new URLSearchParams()
  if (filtros.nome) params.set('nome', filtros.nome)
  if (filtros.cpfCnpj) params.set('cpfCnpj', filtros.cpfCnpj)
  if (filtros.idCategoriaCliente) params.set('idCategoriaCliente', String(filtros.idCategoriaCliente))
  if (filtros.status) params.set('status', filtros.status)
  if (filtros.pagina) params.set('pagina', String(filtros.pagina))
  if (filtros.tamanho) params.set('limite', String(filtros.tamanho))
  if (filtros.ordenarPor) params.set('ordenarPor', filtros.ordenarPor)
  if (filtros.direcao) params.set('direcao', filtros.direcao)
  const query = params.toString()
  return api<PaginaClientes>(`/api/v1/clientes${query ? `?${query}` : ''}`)
}

export function buscarCliente(id: number): Promise<Cliente> {
  return api<Cliente>(`/api/v1/clientes/${id}`)
}

/**
 * Verifica se já existe cliente com esse CPF/CNPJ (validado na saída do campo —
 * docs/telas/cliente.md). Reaproveita a listagem existente, sem endpoint novo.
 * `ignorarIdCliente` evita falso positivo ao editar o próprio cliente.
 */
export async function cpfCnpjJaExiste(cpfCnpjDigitos: string, ignorarIdCliente?: number): Promise<boolean> {
  if (!cpfCnpjDigitos) return false
  const pagina = await listarClientes({ cpfCnpj: cpfCnpjDigitos, status: 'TODOS' })
  return pagina.itens.some((c) => c.idCliente !== ignorarIdCliente)
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
