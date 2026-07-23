import { api, apiUpload } from './api'

/** Galeria de fotos de produto (docs/infra/armazenamento-imagens.md, ADR-013). Máximo de
 * 6 fotos por produto (regra de produto, 2026-07-23) — reforçado no servidor. */
export interface ImagemProduto {
  idImagem: number
  url: string
  indice: number
}

export const MAX_IMAGENS_POR_PRODUTO = 6

export function enviarImagem(idProduto: number, arquivo: File): Promise<ImagemProduto[]> {
  const formData = new FormData()
  formData.append('arquivo', arquivo)
  return apiUpload<ImagemProduto[]>(`/api/v1/produtos/${idProduto}/imagens`, formData)
}

export function excluirImagem(idProduto: number, idImagem: number): Promise<ImagemProduto[]> {
  return api<ImagemProduto[]>(`/api/v1/produtos/${idProduto}/imagens/${idImagem}`, { method: 'DELETE' })
}

export function reordenarImagens(idProduto: number, idsImagem: number[]): Promise<ImagemProduto[]> {
  return api<ImagemProduto[]>(`/api/v1/produtos/${idProduto}/imagens/ordem`, {
    method: 'PUT',
    body: JSON.stringify({ idsImagem }),
  })
}
