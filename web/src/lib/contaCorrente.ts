import { api } from './api'
import { dataParaIso, isoParaData } from './masks'
import { maiusculas } from './texto'

export interface ContaCorrente {
  /** Número/código da conta bancária — a própria PK de negócio, imutável após criar. */
  idContaCorrente: string
  idEmpresa: number
  nomeEmpresa: string
  idBanco: string
  idAgencia: string
  descricao: string
  ativo: boolean
  dataAbertura: string | null
  criadoEm: string
  atualizadoEm: string
}

export interface ContaCorrenteOpcao {
  idContaCorrente: string
  descricao: string
}

/** Estado do formulário — strings para casar com inputs controlados. */
export interface ContaCorrenteFormState {
  idContaCorrente: string
  idEmpresa: number | ''
  idBanco: string
  idAgencia: string
  descricao: string
  ativo: boolean
  dataAbertura: string
}

export const CONTA_CORRENTE_VAZIA: ContaCorrenteFormState = {
  idContaCorrente: '',
  idEmpresa: '',
  idBanco: '',
  idAgencia: '',
  descricao: '',
  ativo: true,
  dataAbertura: '',
}

export function paraFormulario(c: ContaCorrente): ContaCorrenteFormState {
  return {
    idContaCorrente: c.idContaCorrente,
    idEmpresa: c.idEmpresa,
    idBanco: c.idBanco,
    idAgencia: c.idAgencia,
    descricao: c.descricao,
    ativo: c.ativo,
    dataAbertura: isoParaData(c.dataAbertura),
  }
}

export function paraRequisicao(f: ContaCorrenteFormState) {
  return {
    idContaCorrente: maiusculas(f.idContaCorrente.trim()),
    idEmpresa: f.idEmpresa === '' ? null : f.idEmpresa,
    idBanco: maiusculas(f.idBanco.trim()),
    idAgencia: maiusculas(f.idAgencia.trim()),
    descricao: maiusculas(f.descricao.trim()),
    ativo: f.ativo,
    dataAbertura: f.dataAbertura ? dataParaIso(f.dataAbertura) : null,
  }
}

export interface PaginaContasCorrente {
  itens: ContaCorrente[]
  pagina: number
  tamanhoPagina: number
  totalItens: number
  totalPaginas: number
}

export interface ExclusaoContaCorrente {
  acao: 'excluido' | 'inativado'
  motivo: string | null
}

export type ColunaOrdenacaoContaCorrente = 'idContaCorrente' | 'descricao' | 'nomeEmpresa' | 'idBanco' | 'status'
export type DirecaoOrdenacao = 'ASC' | 'DESC'
export type StatusContaCorrente = 'ATIVOS' | 'INATIVOS' | 'TODOS'

export interface FiltrosContasCorrente {
  busca?: string
  status?: StatusContaCorrente
  pagina?: number
  tamanho?: number
  ordenarPor?: ColunaOrdenacaoContaCorrente
  direcao?: DirecaoOrdenacao
}

export function listarContasCorrente(filtros: FiltrosContasCorrente): Promise<PaginaContasCorrente> {
  const params = new URLSearchParams()
  if (filtros.busca) params.set('busca', filtros.busca)
  if (filtros.status) params.set('status', filtros.status)
  if (filtros.pagina) params.set('pagina', String(filtros.pagina))
  if (filtros.tamanho) params.set('limite', String(filtros.tamanho))
  if (filtros.ordenarPor) params.set('ordenarPor', filtros.ordenarPor)
  if (filtros.direcao) params.set('direcao', filtros.direcao)
  const query = params.toString()
  return api<PaginaContasCorrente>(`/api/v1/contas-corrente${query ? `?${query}` : ''}`)
}

/** Opções ativas — usada pelo select de Movimentação de Conta Corrente. */
export function listarOpcoesContaCorrente(): Promise<ContaCorrenteOpcao[]> {
  return api<ContaCorrenteOpcao[]>('/api/v1/contas-corrente/opcoes')
}

export function buscarContaCorrente(idContaCorrente: string): Promise<ContaCorrente> {
  return api<ContaCorrente>(`/api/v1/contas-corrente/${encodeURIComponent(idContaCorrente)}`)
}

export function criarContaCorrente(payload: ReturnType<typeof paraRequisicao>): Promise<ContaCorrente> {
  return api<ContaCorrente>('/api/v1/contas-corrente', { method: 'POST', body: JSON.stringify(payload) })
}

export function atualizarContaCorrente(
  idContaCorrente: string,
  payload: ReturnType<typeof paraRequisicao>,
): Promise<ContaCorrente> {
  return api<ContaCorrente>(`/api/v1/contas-corrente/${encodeURIComponent(idContaCorrente)}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export function excluirContaCorrente(idContaCorrente: string): Promise<ExclusaoContaCorrente> {
  return api<ExclusaoContaCorrente>(`/api/v1/contas-corrente/${encodeURIComponent(idContaCorrente)}`, { method: 'DELETE' })
}
