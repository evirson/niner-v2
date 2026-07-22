import { api } from './api'

export interface CategoriaProduto {
  idCategoria: number
  nomeCategoria: string
}

export function listarCategoriasProduto(): Promise<CategoriaProduto[]> {
  return api<CategoriaProduto[]>('/api/v1/categorias-produto')
}

export function criarCategoriaProduto(nomeCategoria: string): Promise<CategoriaProduto> {
  return api<CategoriaProduto>('/api/v1/categorias-produto', {
    method: 'POST',
    body: JSON.stringify({ nomeCategoria }),
  })
}

export function renomearCategoriaProduto(id: number, nomeCategoria: string): Promise<CategoriaProduto> {
  return api<CategoriaProduto>(`/api/v1/categorias-produto/${id}`, {
    method: 'PUT',
    body: JSON.stringify({ nomeCategoria }),
  })
}
