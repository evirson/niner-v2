import { api } from './api'
import { somenteAlfanumerico } from './masks'
import { maiusculas } from './texto'

/**
 * Painel do assinante (ADR-015, docs/telas/painel-assinatura.md) — o que a loja paga à Vetor.
 * Não confundir com o financeiro do lojista (caixa/crediário): são planos diferentes (P9).
 */

export type SituacaoUso = 'NORMAL' | 'ATENCAO' | 'TOLERANCIA' | 'BLOQUEADO'

export interface PlanoAtual {
  idPlano: number
  nome: string
  gratuito: boolean
  precoMensal: number
  precoAnual: number
  ciclo: string
  limiteVendasMes: number | null
}

export interface UsoAtual {
  competencia: string
  qtdVendas: number
  limite: number | null
  restantes: number | null
  tolerancia: number
  toleranciaRestante: number | null
  situacao: SituacaoUso
  zeraEm: string
}

export interface UsoMes {
  competencia: string
  qtdVendas: number
}

export interface FaixaSugerida {
  nome: string
  limiteVendasMes: number | null
  precoMensal: number
  precoAnual: number
}

export interface EmpresaDoTenant {
  idEmpresa: number
  codigoEmpresa: number
  razaoSocial: string
  nomeFantasia: string | null
  cnpj: string | null
  cidade: string | null
  estado: string | null
  matriz: boolean
  ativo: boolean
}

export interface MinhaConta {
  plano: PlanoAtual
  uso: UsoAtual
  historico: UsoMes[]
  faixaRecomendada: FaixaSugerida | null
  empresas: EmpresaDoTenant[]
}

export function buscarMinhaConta(): Promise<MinhaConta> {
  return api<MinhaConta>('/api/v1/minha-conta')
}

export interface NovaEmpresaForm {
  razaoSocial: string
  nomeFantasia: string
  cnpj: string
}

/** CNPJ é alfanumérico desde a IN RFB 2.229/2024 — nunca limpar com filtro só-dígitos. */
export function criarEmpresa(f: NovaEmpresaForm): Promise<{ idEmpresa: number; codigoEmpresa: number }> {
  return api('/api/v1/empresas', {
    method: 'POST',
    body: JSON.stringify({
      razaoSocial: maiusculas(f.razaoSocial.trim()),
      nomeFantasia: f.nomeFantasia.trim() ? maiusculas(f.nomeFantasia.trim()) : null,
      cnpj: f.cnpj.trim() ? somenteAlfanumerico(f.cnpj) : null,
    }),
  })
}

/** "2026-08" a partir de "2026-08-01" — rótulo curto do gráfico. */
export function rotuloCompetencia(iso: string): string {
  const [ano, mes] = iso.split('-')
  const nomes = ['jan', 'fev', 'mar', 'abr', 'mai', 'jun', 'jul', 'ago', 'set', 'out', 'nov', 'dez']
  return `${nomes[Number(mes) - 1]}/${ano.slice(2)}`
}
