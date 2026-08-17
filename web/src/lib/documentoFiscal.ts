import { api } from './api'

export interface DocumentoFiscalItem {
  idDocumentoFiscal: number
  modelo: number
  serie: number
  numero: number
  chaveAcesso: string
  tipoOperacao: string
  situacao: string
  tipoEmissao: number
  ambiente: 'HOMOLOGACAO' | 'PRODUCAO'
  dataEmissao: string
  dataAutorizacao: string | null
  protocolo: string | null
  valorTotal: number
  idVenda: number | null
  nomeCliente: string | null
}

export interface PaginaDocumentosFiscais {
  itens: DocumentoFiscalItem[]
  pagina: number
  tamanhoPagina: number
  totalItens: number
  totalPaginas: number
}

export interface FiltrosDocumentoFiscal {
  idEmpresa: number
  dataInicial: string
  dataFinal: string
  modelo?: number
  situacao?: string
  pagina?: number
  limite?: number
}

export function listarDocumentosFiscais(filtros: FiltrosDocumentoFiscal): Promise<PaginaDocumentosFiscais> {
  const params = new URLSearchParams()
  params.set('idEmpresa', String(filtros.idEmpresa))
  params.set('dataInicial', filtros.dataInicial)
  params.set('dataFinal', filtros.dataFinal)
  if (filtros.modelo) params.set('modelo', String(filtros.modelo))
  if (filtros.situacao) params.set('situacao', filtros.situacao)
  if (filtros.pagina) params.set('pagina', String(filtros.pagina))
  if (filtros.limite) params.set('limite', String(filtros.limite))
  return api<PaginaDocumentosFiscais>(`/api/v1/fiscal/documentos?${params.toString()}`)
}

export interface XmlDocumentoFiscal {
  idDocumentoFiscal: number
  chaveAcesso: string
  xml: string | null
}

export function buscarXmlDocumentoFiscal(idDocumentoFiscal: number): Promise<XmlDocumentoFiscal> {
  return api<XmlDocumentoFiscal>(`/api/v1/fiscal/documentos/${idDocumentoFiscal}/xml`)
}

export interface ConsultaSefazResultado {
  cStat: string | null
  xMotivo: string | null
  protocolo: string | null
}

export function consultarDocumentoNaSefaz(idDocumentoFiscal: number): Promise<ConsultaSefazResultado> {
  return api<ConsultaSefazResultado>(`/api/v1/fiscal/documentos/${idDocumentoFiscal}/consultar-sefaz`, {
    method: 'POST',
  })
}
