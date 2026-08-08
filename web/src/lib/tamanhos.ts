import { api } from './api'

export interface Tamanho {
  idTamanho: number
  descricao: string
}

/** Sem tela própria — criado embutido no modal "Gerenciar Grades" da tela de Produto. */
export function listarTamanhos(): Promise<Tamanho[]> {
  return api<Tamanho[]>('/api/v1/tamanhos')
}

export function criarTamanho(descricao: string): Promise<Tamanho> {
  return api<Tamanho>('/api/v1/tamanhos', { method: 'POST', body: JSON.stringify({ descricao }) })
}

export function renomearTamanho(id: number, descricao: string): Promise<Tamanho> {
  return api<Tamanho>(`/api/v1/tamanhos/${id}`, { method: 'PUT', body: JSON.stringify({ descricao }) })
}
