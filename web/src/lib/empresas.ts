import { api } from './api'

export interface Empresa {
  idEmpresa: number
  codigoEmpresa: number
  razaoSocial: string
  nomeFantasia: string | null
  ativo: boolean
}

export function listarEmpresas(): Promise<Empresa[]> {
  return api<Empresa[]>('/api/v1/empresas')
}

/** Empresas que o usuário logado pode operar (ADMIN: todas do tenant; OPERADOR: só as
 *  liberadas pra ele) — usado pela Entrada de Produtos por Compra pra escolher em qual empresa
 *  dar entrada. */
export function listarEmpresasPermitidas(): Promise<Empresa[]> {
  return api<Empresa[]>('/api/v1/empresas/permitidas')
}
