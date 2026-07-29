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

export function listarTransferencias(pagina?: number, limite?: number): Promise<PaginaTransferencias> {
  const params = new URLSearchParams()
  if (pagina) params.set('pagina', String(pagina))
  if (limite) params.set('limite', String(limite))
  const query = params.toString()
  return api<PaginaTransferencias>(`/api/v1/estoque/transferencias${query ? `?${query}` : ''}`)
}

export function buscarTransferencia(id: number): Promise<Transferencia> {
  return api<Transferencia>(`/api/v1/estoque/transferencias/${id}`)
}

export function criarTransferencia(payload: CriarTransferenciaRequest): Promise<Transferencia> {
  return api<Transferencia>('/api/v1/estoque/transferencias', { method: 'POST', body: JSON.stringify(payload) })
}
