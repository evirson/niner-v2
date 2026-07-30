import { ApiError, api } from './api'

export interface Banco {
  codigoBanco: string
  nomeBanco: string
}

/** null quando o código não existe (404) — não é erro de validação, só "sem nome ainda". */
export async function buscarBanco(codigo: string): Promise<Banco | null> {
  try {
    return await api<Banco>(`/api/v1/bancos/${encodeURIComponent(codigo)}`)
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null
    throw e
  }
}
