import { api } from './api'

export interface Cor {
  idCor: number
  descricao: string
}

/** Sem tela própria — criada embutida na Emissão de Etiqueta ("+ Nova cor"), válvula de escape
 *  enquanto a Entrada de Produtos (onde cor nasceria na compra) não existe. */
export function listarCores(): Promise<Cor[]> {
  return api<Cor[]>('/api/v1/cores')
}

export function criarCor(descricao: string): Promise<Cor> {
  return api<Cor>('/api/v1/cores', { method: 'POST', body: JSON.stringify({ descricao }) })
}

export function renomearCor(id: number, descricao: string): Promise<Cor> {
  return api<Cor>(`/api/v1/cores/${id}`, { method: 'PUT', body: JSON.stringify({ descricao }) })
}
