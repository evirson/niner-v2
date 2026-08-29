import { api } from './api'

/**
 * Sangria de Caixa — dinheiro que sai da gaveta e entra numa conta corrente.
 *
 * ⭐ É **transferência**, não saída: toda sangria escreve os dois lados na mesma transação do
 * servidor (débito no caixa, crédito no banco). Decisão do dono do produto em 2026-08-29 —
 * *"esta sangria tem que ter um destino: sempre será depositada numa conta bancária, ou vai pro
 * caixa central que tb está definido como uma conta bancária"*.
 */
export interface SangriaContexto {
  caixaAberto: boolean
  idCaixa: number | null
  nomeCarteira: string | null
  /** Dinheiro que a sangria pode tirar — só a carteira de abertura, não o total do caixa. */
  disponivel: number
}

export interface Sangria {
  idSangria: number
  dataSangria: string
  valor: number
  idContaCorrente: string
  descricaoContaCorrente: string
  nomeUsuario: string
  observacao: string | null
}

export interface SangriaRequest {
  idContaCorrente: string
  valor: number
  idPlanoContas: string
  observacao?: string | null
}

export function buscarContextoSangria(): Promise<SangriaContexto> {
  return api<SangriaContexto>('/api/v1/caixa/sangrias/contexto')
}

export function listarSangrias(): Promise<Sangria[]> {
  return api<Sangria[]>('/api/v1/caixa/sangrias')
}

export function registrarSangria(req: SangriaRequest): Promise<Sangria> {
  return api<Sangria>('/api/v1/caixa/sangrias', { method: 'POST', body: JSON.stringify(req) })
}
