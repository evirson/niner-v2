import { ApiError, api } from './api'

/** Referência de NCM (`cfg_produto_ncm`, tabela global, mantida por script). */
export interface Ncm {
  codigoNcm: string
  descricaoNcm: string
  aliquotaIbpt: number | null
}

/** {@code null} quando o código não existe (404) — não é erro de validação, só "sem descrição ainda". */
export async function buscarNcm(codigo: string): Promise<Ncm | null> {
  try {
    return await api<Ncm>(`/api/v1/ncm/${encodeURIComponent(codigo)}`)
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null
    throw e
  }
}
