import { ApiError, api } from './api'

/** Referência de NCM (`cfg_produto_ncm`, tabela global, mantida por script). */
export interface Ncm {
  codigoNcm: string
  descricaoNcm: string
  alqFederalNacional: number | null
  alqFederalImportado: number | null
  alqEstadual: number | null
  alqMunicipal: number | null
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

/** Pesquisa por nome (2026-08-11) — quem cadastra um produto nem sempre sabe o código NCM de
 *  cabeça; devolve até 30 correspondências por `descricao_ncm`. */
export function buscarNcmsPorNome(busca: string): Promise<Ncm[]> {
  return api<Ncm[]>(`/api/v1/ncm?busca=${encodeURIComponent(busca)}`)
}
