import { api } from './api'
import type { Tamanho } from './tamanhos'

export interface Grade {
  idGrade: number
  descricao: string
  tamanhos: Tamanho[]
}

/** Sem tela própria — gerida embutida no formulário de Produto (modal "＋ Gerenciar Grades").
 *  Até 20 tamanhos, sempre em ordem (V017). */
export function listarGrades(): Promise<Grade[]> {
  return api<Grade[]>('/api/v1/grades')
}

export function buscarGrade(id: number): Promise<Grade> {
  return api<Grade>(`/api/v1/grades/${id}`)
}

export function criarGrade(descricao: string, idsTamanho: number[]): Promise<Grade> {
  return api<Grade>('/api/v1/grades', { method: 'POST', body: JSON.stringify({ descricao, idsTamanho }) })
}

export function atualizarGrade(id: number, descricao: string, idsTamanho: number[]): Promise<Grade> {
  return api<Grade>(`/api/v1/grades/${id}`, { method: 'PUT', body: JSON.stringify({ descricao, idsTamanho }) })
}
