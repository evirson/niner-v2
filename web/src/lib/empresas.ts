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
