import { api } from './api'

/** NFS-e Nacional (docs/MODULONFSE.md). Contrato em duas telas: configuração e emissão. */

export type AmbienteFiscal = 'HOMOLOGACAO' | 'PRODUCAO'

/** Anexo II do layout nacional. ⛔ Não existem outros valores — não inventar. */
export const MOTIVOS_CANCELAMENTO = [
  { valor: 1, rotulo: 'Erro na emissão' },
  { valor: 2, rotulo: 'Serviço não prestado' },
  { valor: 9, rotulo: 'Outros' },
] as const

/** Anexos da LC 123 que o produto atende. */
export const ANEXOS_SIMPLES = ['III', 'V'] as const

export interface NfseConfig {
  idEmpresa: number
  emiteNfse: boolean
  ambiente: AmbienteFiscal
  serie: number
  rbt12: number | null
  simplesAnexo: string | null
  aliquotaSimplesEfetiva: number | null
  ultimoTesteEm: string | null
  ultimoTesteStatus: string | null
  ultimoTesteMensagem: string | null
  configurado: boolean
  /** Vêm do cadastro da Empresa — mostrados aqui só para o lojista ver o que falta. */
  cnpj: string | null
  inscricaoMunicipal: string | null
  codigoMunicipioIbge: number | null
  crt: number
}

/** Um item do assistente. `telaParaResolver` é o que separa aviso útil de aviso que irrita. */
export interface VerificacaoNfse {
  item: string
  ok: boolean
  detalhe: string
  telaParaResolver: string | null
}

export interface TesteConexaoNfse {
  status: 'OK' | 'FALHA'
  mensagem: string
  em: string
}

/**
 * Situações da NFS-e. ⛔ Não há CONTINGENCIA: a NFS-e não tem equivalente ao tpEmis=9 da NFC-e.
 * PENDENTE não é situação do banco — é o rótulo que a emissão devolve quando o serviço não
 * respondeu, e a nota fica ASSINADA aguardando reenvio com o MESMO número.
 */
export type SituacaoNfse =
  | 'RASCUNHO' | 'ASSINADA' | 'TRANSMITINDO'
  | 'AUTORIZADA' | 'REJEITADA' | 'CANCELADA' | 'NAO_EMITIDA'

export interface NfseDocumento {
  idNfse: number
  idEmpresa: number
  idVenda: number
  serie: number
  numeroDps: number
  idDps: string
  chaveAcesso: string | null
  numeroNfse: number | null
  situacao: SituacaoNfse
  ambiente: AmbienteFiscal
  codigoMunicipioIbge: number
  competencia: string
  codigoTributacaoNacional: string
  descricaoServico: string
  valorServicos: number
  xmlChave: string | null
  xmlCancelamentoChave: string | null
  codigoStatus: string | null
  motivoStatus: string | null
  tentativas: number
  dataAutorizacao: string | null
  dataCancelamento: string | null
}

export interface ResultadoEmissao {
  idNfse: number
  situacao: string
  chaveAcesso: string | null
  numeroNfse: number | null
  codigo: string | null
  mensagem: string
}

export interface AliquotaSugerida {
  encontrada: boolean
  percentual?: number
  incide?: boolean
  vigenteDesde?: string
  aviso?: string
}

// ---- configuração -------------------------------------------------------------------------

export function buscarNfseConfig(idEmpresa: number): Promise<NfseConfig> {
  return api<NfseConfig>(`/api/v1/fiscal/nfse/${idEmpresa}`)
}

export function salvarNfseConfig(idEmpresa: number, payload: Partial<NfseConfig>): Promise<NfseConfig> {
  return api<NfseConfig>(`/api/v1/fiscal/nfse/${idEmpresa}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

/** ⭐ A resposta esperada é HTTP 404 + E2401 no SEFIN — o back traduz isso para status OK. */
export function testarConexaoNfse(idEmpresa: number): Promise<TesteConexaoNfse> {
  return api<TesteConexaoNfse>(`/api/v1/fiscal/nfse/${idEmpresa}/testar-conexao`, { method: 'PUT' })
}

/** O assistente: o que passou, o que falta e onde resolver. */
export function verificarNfse(idEmpresa: number): Promise<VerificacaoNfse[]> {
  return api<VerificacaoNfse[]>(`/api/v1/fiscal/nfse/${idEmpresa}/verificar`, { method: 'PUT' })
}

/**
 * Alíquota do ISS que o ADN devolve para o código, no município da empresa.
 * ⚠️ `encontrada: false` é resposta legítima — nem todo município publicou a tabela.
 */
export function aliquotaSugerida(
  idEmpresa: number,
  cTribNac: string,
  cTribMun?: string | null,
): Promise<AliquotaSugerida> {
  const busca = new URLSearchParams({ cTribNac })
  if (cTribMun) busca.set('cTribMun', cTribMun)
  return api<AliquotaSugerida>(`/api/v1/fiscal/nfse/${idEmpresa}/aliquota-sugerida?${busca}`)
}

// ---- emissão ------------------------------------------------------------------------------

/** ⚠️ Devolve LISTA: a DPS carrega um código de serviço, então uma venda pode render N notas. */
export function emitirNfseDaVenda(idVenda: number): Promise<ResultadoEmissao[]> {
  return api<ResultadoEmissao[]>(`/api/v1/nfse/vendas/${idVenda}/emitir`, { method: 'POST' })
}

export function nfseDaVenda(idVenda: number): Promise<NfseDocumento[]> {
  return api<NfseDocumento[]>(`/api/v1/nfse/vendas/${idVenda}`)
}

export function cancelarNfse(
  idNfse: number,
  codigoMotivo: number,
  motivo: string,
): Promise<ResultadoEmissao> {
  return api<ResultadoEmissao>(`/api/v1/nfse/${idNfse}`, {
    method: 'DELETE',
    body: JSON.stringify({ codigoMotivo, motivo }),
  })
}

export function urlXmlNfse(idNfse: number): string {
  return `/api/v1/nfse/${idNfse}/xml`
}

// ---- apresentação -------------------------------------------------------------------------

/**
 * Como a nota é chamada na tela.
 *
 * ⚠️ Enquanto não autorizada ela NÃO tem número oficial: o `numeroNfse` é atribuído pela
 * prefeitura. Antes disso o nome é o nosso sequencial (`DPS 12`), nunca um número que imite
 * numeração fiscal — foi a lição do workshop, onde a mesma nota aparecia com dois nomes.
 */
export function nomeDaNfse(doc: NfseDocumento): string {
  return doc.numeroNfse ? `Nº ${doc.numeroNfse}` : `DPS ${doc.numeroDps}`
}

/** Cor da tarja. ⚠️ Amarelo é "não avaliada, reenvie"; vermelho é "corrija o dado apontado". */
export function tomDaSituacao(situacao: SituacaoNfse): 'sucesso' | 'erro' | 'aviso' | 'neutro' {
  switch (situacao) {
    case 'AUTORIZADA':
      return 'sucesso'
    case 'REJEITADA':
      return 'erro'
    case 'RASCUNHO':
    case 'ASSINADA':
    case 'TRANSMITINDO':
      return 'aviso'
    default:
      return 'neutro'
  }
}
