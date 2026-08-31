import { api } from './api'

/** Configuração fiscal por EMPRESA (docs/telas/fiscal-configuracao.md) — diferente de
 *  `cfg_geral`, a linha pode não existir (`configurado: false`) até o primeiro PUT. */

export type AmbienteFiscal = 'HOMOLOGACAO' | 'PRODUCAO'

/** DF37 — o Nainer atende só Simples Nacional (1 e 2) e MEI (4). O 3 nunca aparece aqui. */
export const CRT_OPCOES = [
  { valor: 1, rotulo: '1 — Simples Nacional' },
  { valor: 2, rotulo: '2 — Simples Nacional (excesso de sublimite)' },
  { valor: 4, rotulo: '4 — MEI' },
] as const

export interface FiscalConfig {
  idEmpresa: number
  razaoSocialEmpresa: string
  configurado: boolean
  crt: number
  emiteNfce: boolean
  emiteNfe: boolean
  ambiente: AmbienteFiscal
  serieNfce: number
  serieNfe: number
  serieContingencia: number
  inscricaoEstadualSt: string | null
  suframa: string | null
  cscId: string | null
  cscConfigurado: boolean
  /** ⭐ Um par por AMBIENTE. O segredo NUNCA volta — só o id e o "está definido", que é o que a
   *  tela precisa para avisar antes do go-live. */
  cscIdHomologacao: string | null
  cscConfiguradoHomologacao: boolean
  cscIdProducao: string | null
  cscConfiguradoProducao: boolean
  versaoTabelaIbpt: string | null
  serieNfceBloqueada: boolean
  serieNfeBloqueada: boolean
  /** A instalação fixa o ambiente de emissão: a tela esconde a escolha (decisão de 2026-08-27). */
  ambienteTravado: boolean
  criadoEm: string | null
  atualizadoEm: string | null
}

export interface EmpresaFiscal {
  idEmpresa: number
  razaoSocial: string
  configurado: boolean
  emiteNfce: boolean
  emiteNfe: boolean
}

export interface FiscalConfigRequest {
  crt: number
  emiteNfce: boolean
  emiteNfe: boolean
  ambiente: AmbienteFiscal
  serieNfce: number
  serieNfe: number
  serieContingencia: number
  inscricaoEstadualSt: string | null
  suframa: string | null
  cscId: string | null
  /** `undefined`/omitido preserva o token já gravado; string troca; `removerCsc: true` apaga. */
  cscToken?: string | null
  removerCsc?: boolean
  cscIdHomologacao?: string | null
  cscTokenHomologacao?: string | null
  removerCscHomologacao?: boolean
  cscIdProducao?: string | null
  cscTokenProducao?: string | null
  removerCscProducao?: boolean
}

/** Empresas do tenant e se cada uma já tem fiscal configurado — alimenta o seletor do topo. */
export function listarEmpresasFiscal(): Promise<EmpresaFiscal[]> {
  return api<EmpresaFiscal[]>('/api/v1/fiscal/config/empresas')
}

export function buscarFiscalConfig(idEmpresa: number): Promise<FiscalConfig> {
  return api<FiscalConfig>(`/api/v1/fiscal/config/${idEmpresa}`)
}

export function salvarFiscalConfig(idEmpresa: number, payload: FiscalConfigRequest): Promise<FiscalConfig> {
  return api<FiscalConfig>(`/api/v1/fiscal/config/${idEmpresa}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}
