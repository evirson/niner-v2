import { apiUpload } from './api'
import { API_BASE } from './config'

interface ArquivoCompartilhadoResponse {
  token: string
}

/**
 * Sobe um arquivo (ex.: o PDF do comprovante, já gerado no navegador) pro cache temporário da
 * API — fica disponível por um link público por um tempo curto (24h por padrão, configurável em
 * `niner.arquivo-compartilhado.expiracao-horas`), sem passar por object storage nenhum: é
 * guardado só em memória do lado da API, sem custo de infraestrutura nova. Devolve o link já
 * pronto pra download.
 */
export async function compartilharArquivo(blob: Blob, nomeArquivo: string): Promise<string> {
  const formData = new FormData()
  formData.append('arquivo', blob, nomeArquivo)
  const { token } = await apiUpload<ArquivoCompartilhadoResponse>('/api/v1/arquivos-compartilhados', formData)
  return `${API_BASE}/api/publico/arquivos-compartilhados/${token}`
}
