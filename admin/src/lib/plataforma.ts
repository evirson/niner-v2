import { api } from './api'

/* ------------------------------------------------------------------------------------------
 * Contratos do backoffice. Espelham os DTOs do backend (`/api/admin/**`) — quando um endpoint
 * mudar lá, é aqui que o TypeScript acusa.
 * ---------------------------------------------------------------------------------------- */

export interface LinhaOrigem {
  origem: string
  campanha: string
  visitantes: number
  leads: number
  contas: number
  pagantes: number
  mrr: number
}

export interface Funil {
  de: string
  ate: string
  visitas: number
  visitantes: number
  leads: number
  contas: number
  contasComVenda: number
  pagantes: number
  mrr: number
  porOrigem: LinhaOrigem[]
}

export function buscarFunil(de?: string, ate?: string): Promise<Funil> {
  const q = new URLSearchParams()
  if (de) q.set('de', de)
  if (ate) q.set('ate', ate)
  return api<Funil>(`/api/admin/marketing/funil${q.toString() ? `?${q}` : ''}`)
}

export interface LeadResumo {
  idLead: number
  nome: string | null
  email: string
  telefoneWhatsapp: string | null
  nomeLoja: string | null
  origem: string
  campanha: string
  status: string
  idTenant: number | null
  nomeConta: string | null
  criadoEm: string
}

export interface PaginaLeads {
  itens: LeadResumo[]
  total: number
  pagina: number
  limite: number
}

export interface MomentoLead {
  quando: string
  tipo: string
  detalhe: string | null
}

export interface LeadDetalhe {
  lead: LeadResumo
  linhaDoTempo: MomentoLead[]
}

export function listarLeads(filtros: { status?: string; origem?: string; pagina?: number }): Promise<PaginaLeads> {
  const q = new URLSearchParams()
  if (filtros.status) q.set('status', filtros.status)
  if (filtros.origem) q.set('origem', filtros.origem)
  q.set('pagina', String(filtros.pagina ?? 1))
  return api<PaginaLeads>(`/api/admin/marketing/leads?${q}`)
}

export function buscarLead(id: number): Promise<LeadDetalhe> {
  return api<LeadDetalhe>(`/api/admin/marketing/leads/${id}`)
}

export function atualizarLead(id: number, dados: { status?: string; anotacao?: string }): Promise<void> {
  return api<void>(`/api/admin/marketing/leads/${id}`, { method: 'PUT', body: JSON.stringify(dados) })
}

export interface ContaPertoDoLimite {
  idTenant: number
  nomeConta: string
  emailContato: string
  qtdVendas: number
  limite: number
  percentual: number
}

export function contasPertoDoLimite(): Promise<ContaPertoDoLimite[]> {
  return api<ContaPertoDoLimite[]>('/api/admin/marketing/contas-perto-do-limite')
}

export interface TenantResumo {
  idTenant: number
  nomeConta: string
  slug: string
  emailContato: string
  status: string
  plano: string | null
  gratuito: boolean
  limiteVendasMes: number | null
  vendasNoMes: number
  percentualCota: number | null
  qtdEmpresas: number
  qtdUsuarios: number
  mrr: number
  criadoEm: string
}

export interface PaginaTenants {
  itens: TenantResumo[]
  total: number
  pagina: number
  limite: number
}

export interface TenantDetalhe {
  resumo: TenantResumo
  historico: { competencia: string; qtdVendas: number }[]
  faturas: {
    idFatura: number
    competencia: string
    plano: string
    valor: number
    situacao: string
    pagoEm: string | null
  }[]
  empresas: { codigoEmpresa: number; razaoSocial: string; cnpj: string | null; cidade: string | null; estado: string | null }[]
}

export function listarTenants(filtros: { busca?: string; status?: string; pagina?: number }): Promise<PaginaTenants> {
  const q = new URLSearchParams()
  if (filtros.busca) q.set('busca', filtros.busca)
  if (filtros.status) q.set('status', filtros.status)
  q.set('pagina', String(filtros.pagina ?? 1))
  return api<PaginaTenants>(`/api/admin/tenants?${q}`)
}

export function buscarTenant(id: number): Promise<TenantDetalhe> {
  return api<TenantDetalhe>(`/api/admin/tenants/${id}`)
}

export interface Configuracao {
  smtpHabilitado: boolean
  smtpHost: string | null
  smtpPorta: number | null
  smtpUsuario: string | null
  smtpSenhaDefinida: boolean
  smtpStarttls: boolean
  smtpRemetenteEmail: string | null
  smtpRemetenteNome: string | null
  backupHabilitado: boolean
  backupHora: string
  backupRetencaoDias: number
  backupUltimoEm: string | null
  backupUltimoStatus: string | null
  backupUltimoDetalhe: string | null
  mpAccessTokenDefinido: boolean
  mpWebhookSecretDefinido: boolean
  mpNotificationUrl: string | null
  atualizadoEm: string
}

export function buscarConfiguracao(): Promise<Configuracao> {
  return api<Configuracao>('/api/admin/configuracao')
}

/** Campos de segredo em branco = manter o gravado; a palavra LIMPAR apaga (contrato do backend). */
export function salvarConfiguracao(dados: Record<string, unknown>): Promise<Configuracao> {
  return api<Configuracao>('/api/admin/configuracao', { method: 'PUT', body: JSON.stringify(dados) })
}

export function executarBackup(): Promise<{ resultado: string }> {
  return api<{ resultado: string }>('/api/admin/backup/executar', { method: 'POST' })
}

// ---------------------------------------------------------------------------------------------
// CSRT por UF (NT 2018.005) — o código do responsável técnico é emitido pela SEFAZ de CADA UF, e
// o Nainer atende as 27 unidades da federação. O código em si nunca volta do servidor: a listagem
// traz só o identificador (que é público, vai no XML) e quando foi trocado.
// ---------------------------------------------------------------------------------------------

export interface CsrtUf {
  uf: string
  /** tpAmb do XML: 1 produção, 2 homologação. */
  ambiente: number
  idCsrt: string
  definido: boolean
  observacao: string | null
  atualizadoEm: string
}

export function listarCsrt(): Promise<CsrtUf[]> {
  return api<CsrtUf[]>('/api/admin/fiscal/csrt')
}

/** `csrt` em branco numa UF já cadastrada mantém o código gravado (contrato do backend). */
export function salvarCsrt(
  uf: string,
  ambiente: number,
  dados: { idCsrt: string; csrt: string; observacao: string },
): Promise<CsrtUf> {
  return api<CsrtUf>(`/api/admin/fiscal/csrt/${uf}/${ambiente}`, {
    method: 'PUT',
    body: JSON.stringify(dados),
  })
}

export function excluirCsrt(uf: string, ambiente: number): Promise<void> {
  return api<void>(`/api/admin/fiscal/csrt/${uf}/${ambiente}`, { method: 'DELETE' })
}
