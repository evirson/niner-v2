import { api } from './api'

/**
 * DANFE do modelo 55 (A4) — o documento auxiliar da NF-e, hoje usado pela **nota de devolução**
 * (§10.2/B9). Não confundir com o DANFCE (`lib/danfce.ts`), que é o cupom térmico de 80mm da
 * NFC-e: são dois documentos diferentes, com layout e finalidade próprios.
 *
 * Os dados vêm montados do servidor (`GET /fiscal/documentos/{id}/danfe`), nunca reparseados do
 * XML aqui — o impresso tem que ser o documento, e reinterpretar XML no navegador abriria espaço
 * para divergir dele.
 */
export interface DanfeParticipante {
  nome: string
  cpfCnpj: string | null
  inscricaoEstadual: string | null
  enderecoLinha1: string
  enderecoLinha2: string | null
  municipio: string | null
  uf: string | null
  cep: string | null
  telefone: string | null
}

export interface DanfeItem {
  numeroItem: number
  codigoProduto: string
  descricao: string
  ncm: string | null
  cfop: string
  origemMercadoria: number
  /** CSOSN (Simples) ou CST — o DANFE imprime "O/CST" = origem + o código que existir. */
  cstOuCsosn: string | null
  unidadeComercial: string
  quantidade: number
  valorUnitario: number
  valorTotal: number
  baseCalculoIcms: number
  valorIcms: number
  aliquotaIcms: number
}

export interface DanfeTotais {
  baseCalculoIcms: number
  valorIcms: number
  baseCalculoSt: number
  valorIcmsSt: number
  valorProdutos: number
  valorFrete: number
  valorSeguro: number
  valorDesconto: number
  valorOutros: number
  valorPis: number
  valorCofins: number
  valorTotalTributos: number
  valorTotal: number
}

export interface Danfe {
  idDocumentoFiscal: number
  chaveAcesso: string
  modelo: number
  serie: number
  numero: number
  naturezaOperacao: string
  /** 0 = entrada, 1 = saída — o DANFE marca o quadrado correspondente. */
  tipoNf: number
  situacao: string
  homologacao: boolean
  dataEmissao: string
  dataAutorizacao: string | null
  protocolo: string | null
  emitente: DanfeParticipante
  destinatario: DanfeParticipante
  itens: DanfeItem[]
  totais: DanfeTotais
  informacoesComplementares: string | null
  chaveReferenciada: string | null
}

export function buscarDanfe(idDocumentoFiscal: number): Promise<Danfe> {
  return api<Danfe>(`/api/v1/fiscal/documentos/${idDocumentoFiscal}/danfe`)
}

/** A chave de acesso é impressa em grupos de 4 — é assim que se confere dígito a dígito no papel. */
export function formatarChaveGrupos4(chave: string): string {
  return (chave ?? '').replace(/(.{4})/g, '$1 ').trim()
}
