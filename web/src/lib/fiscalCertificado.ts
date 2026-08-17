import { api, apiUpload } from './api'

/** Certificado Digital A1 (docs/telas/fiscal-certificado.md) — write-only de verdade: nenhum
 *  response aqui carrega o arquivo nem a senha. */

export type SituacaoCertificado = 'ATIVO' | 'VENCE_EM_BREVE' | 'VENCIDO' | 'SUBSTITUIDO'

export interface FiscalCertificado {
  idCertificado: number
  idEmpresa: number
  cnpjTitular: string | null
  razaoSocialTitular: string | null
  validoDe: string | null
  validoAte: string | null
  impressaoDigital: string | null
  ativo: boolean
  situacao: SituacaoCertificado
  diasParaVencer: number | null
  criadoEm: string
}

export interface FiscalCertificadoUso {
  idUso: number
  finalidade: string
  idDocumentoFiscal: number | null
  idUsuario: number | null
  ocorridoEm: string
}

export function listarCertificados(idEmpresa: number): Promise<FiscalCertificado[]> {
  return api<FiscalCertificado[]>(`/api/v1/fiscal/certificados?idEmpresa=${idEmpresa}`)
}

export function listarUsosCertificado(idCertificado: number): Promise<FiscalCertificadoUso[]> {
  return api<FiscalCertificadoUso[]>(`/api/v1/fiscal/certificados/${idCertificado}/usos`)
}

export function enviarCertificado(idEmpresa: number, arquivo: File, senha: string): Promise<FiscalCertificado> {
  const formData = new FormData()
  formData.append('idEmpresa', String(idEmpresa))
  formData.append('arquivo', arquivo)
  formData.append('senha', senha)
  return apiUpload<FiscalCertificado>('/api/v1/fiscal/certificados', formData)
}
