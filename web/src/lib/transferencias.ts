import { api } from './api'

export interface EmpresaResumo {
  idEmpresa: number
  nomeEmpresa: string
}

export interface ItemTransferencia {
  idVariacao: number
  descricaoProduto: string
  variacaoLinha: string | null
  variacaoColuna: string | null
  sku: string
  qtd: number
}

export interface Transferencia {
  idTransferencia: number
  empresaOrigem: EmpresaResumo
  empresaDestino: EmpresaResumo
  nomeUsuario: string
  dataTransferencia: string
  observacoes: string | null
  itens: ItemTransferencia[]
}

export interface ItemTransferenciaRequest {
  idVariacao: number
  qtd: number
}

export interface CriarTransferenciaRequest {
  idEmpresaDestino: number
  itens: ItemTransferenciaRequest[]
  observacoes: string | null
}

export interface PaginaTransferencias {
  itens: Transferencia[]
  pagina: number
  tamanhoPagina: number
  totalItens: number
  totalPaginas: number
}

export type ColunaOrdenacaoTransferencia = 'numero' | 'data' | 'origem' | 'destino' | 'usuario' | 'itens'
export type DirecaoOrdenacao = 'ASC' | 'DESC'

export interface FiltrosTransferencias {
  idTransferencia?: number
  idEmpresaOrigem?: number
  idEmpresaDestino?: number
  /** `yyyy-mm-dd` (ISO), já convertido do texto mascarado `dd/mm/aaaa` da tela. */
  dataInicial?: string
  dataFinal?: string
  pagina?: number
  limite?: number
  ordenarPor?: ColunaOrdenacaoTransferencia
  direcao?: DirecaoOrdenacao
}

export function listarTransferencias(filtros: FiltrosTransferencias): Promise<PaginaTransferencias> {
  const params = new URLSearchParams()
  if (filtros.idTransferencia) params.set('idTransferencia', String(filtros.idTransferencia))
  if (filtros.idEmpresaOrigem) params.set('idEmpresaOrigem', String(filtros.idEmpresaOrigem))
  if (filtros.idEmpresaDestino) params.set('idEmpresaDestino', String(filtros.idEmpresaDestino))
  if (filtros.dataInicial) params.set('dataInicial', filtros.dataInicial)
  if (filtros.dataFinal) params.set('dataFinal', filtros.dataFinal)
  if (filtros.pagina) params.set('pagina', String(filtros.pagina))
  if (filtros.limite) params.set('limite', String(filtros.limite))
  if (filtros.ordenarPor) params.set('ordenarPor', filtros.ordenarPor)
  if (filtros.direcao) params.set('direcao', filtros.direcao)
  const query = params.toString()
  return api<PaginaTransferencias>(`/api/v1/estoque/transferencias${query ? `?${query}` : ''}`)
}

export function buscarTransferencia(id: number): Promise<Transferencia> {
  return api<Transferencia>(`/api/v1/estoque/transferencias/${id}`)
}

export function criarTransferencia(payload: CriarTransferenciaRequest): Promise<Transferencia> {
  return api<Transferencia>('/api/v1/estoque/transferencias', { method: 'POST', body: JSON.stringify(payload) })
}

export function excluirTransferencia(id: number): Promise<void> {
  return api<void>(`/api/v1/estoque/transferencias/${id}`, { method: 'DELETE' })
}
