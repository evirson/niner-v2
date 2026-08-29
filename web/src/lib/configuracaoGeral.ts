import { api } from './api'
import { desmascararPercentual, formatarPercentual } from './masks'

export interface ConfiguracaoGeral {
  percentualDescontoVenda: number
  jurosCrediarioDias: number
  jurosCrediario: number
  multaCrediarioDias: number
  multaCrediario: number
  cfgUsaCorGrade: boolean
  cfgPermiteQtdDecimal: boolean
  cfgPermiteEstoqueNegativo: boolean
  cfgDiasValidadeOrcamento: number
  cfgExigeNumeroVendaDevolucao: boolean
  cfgRateiaFreteEntrada: boolean
  cfgReajustaPrecoEntrada: boolean
  cfgConsisteValorContasPagar: boolean
  idPlanoContasCompraMercadoria: string
  cfgEmiteFiscalAposVenda: boolean
  /** Módulo de serviços (S1) — desligado por padrão; é ele que faz a OS existir na tela. */
  cfgUsaServicos: boolean
  cfgExigeSangriaFechamento: boolean
  atualizadoEm: string
}

/** Estado do formulário — strings para casar com inputs controlados (padrão do projeto). */
export interface ConfiguracaoGeralFormState {
  percentualDescontoVenda: string
  jurosCrediarioDias: string
  jurosCrediario: string
  multaCrediarioDias: string
  multaCrediario: string
  cfgUsaCorGrade: boolean
  cfgPermiteQtdDecimal: boolean
  cfgPermiteEstoqueNegativo: boolean
  cfgDiasValidadeOrcamento: string
  cfgExigeNumeroVendaDevolucao: boolean
  cfgRateiaFreteEntrada: boolean
  cfgReajustaPrecoEntrada: boolean
  cfgConsisteValorContasPagar: boolean
  idPlanoContasCompraMercadoria: string
  cfgEmiteFiscalAposVenda: boolean
  cfgUsaServicos: boolean
  cfgExigeSangriaFechamento: boolean
}

export function paraFormulario(c: ConfiguracaoGeral): ConfiguracaoGeralFormState {
  return {
    percentualDescontoVenda: formatarPercentual(c.percentualDescontoVenda),
    jurosCrediarioDias: String(c.jurosCrediarioDias),
    jurosCrediario: formatarPercentual(c.jurosCrediario),
    multaCrediarioDias: String(c.multaCrediarioDias),
    multaCrediario: formatarPercentual(c.multaCrediario),
    cfgUsaCorGrade: c.cfgUsaCorGrade,
    cfgPermiteQtdDecimal: c.cfgPermiteQtdDecimal,
    cfgPermiteEstoqueNegativo: c.cfgPermiteEstoqueNegativo,
    cfgDiasValidadeOrcamento: String(c.cfgDiasValidadeOrcamento ?? 15),
    cfgExigeNumeroVendaDevolucao: c.cfgExigeNumeroVendaDevolucao,
    cfgRateiaFreteEntrada: c.cfgRateiaFreteEntrada,
    cfgReajustaPrecoEntrada: c.cfgReajustaPrecoEntrada,
    cfgConsisteValorContasPagar: c.cfgConsisteValorContasPagar,
    idPlanoContasCompraMercadoria: c.idPlanoContasCompraMercadoria,
    cfgEmiteFiscalAposVenda: c.cfgEmiteFiscalAposVenda,
    cfgUsaServicos: c.cfgUsaServicos,
    cfgExigeSangriaFechamento: c.cfgExigeSangriaFechamento,
  }
}

/** Monta o corpo da requisição — todos os campos são obrigatórios (tabela sem colunas nullable). */
export function paraRequisicao(f: ConfiguracaoGeralFormState) {
  return {
    percentualDescontoVenda: desmascararPercentual(f.percentualDescontoVenda),
    jurosCrediarioDias: Number(f.jurosCrediarioDias) || 0,
    jurosCrediario: desmascararPercentual(f.jurosCrediario),
    multaCrediarioDias: Number(f.multaCrediarioDias) || 0,
    multaCrediario: desmascararPercentual(f.multaCrediario),
    cfgUsaCorGrade: f.cfgUsaCorGrade,
    cfgPermiteQtdDecimal: f.cfgPermiteQtdDecimal,
    cfgPermiteEstoqueNegativo: f.cfgPermiteEstoqueNegativo,
    cfgDiasValidadeOrcamento: Number(f.cfgDiasValidadeOrcamento) || 15,
    cfgExigeNumeroVendaDevolucao: f.cfgExigeNumeroVendaDevolucao,
    cfgRateiaFreteEntrada: f.cfgRateiaFreteEntrada,
    cfgReajustaPrecoEntrada: f.cfgReajustaPrecoEntrada,
    cfgConsisteValorContasPagar: f.cfgConsisteValorContasPagar,
    idPlanoContasCompraMercadoria: f.idPlanoContasCompraMercadoria,
    cfgEmiteFiscalAposVenda: f.cfgEmiteFiscalAposVenda,
    cfgUsaServicos: f.cfgUsaServicos,
    cfgExigeSangriaFechamento: f.cfgExigeSangriaFechamento,
  }
}

export function buscarConfiguracaoGeral(): Promise<ConfiguracaoGeral> {
  return api<ConfiguracaoGeral>('/api/v1/config-geral')
}

export interface PlanoContasCompraMercadoria {
  idPlanoContasCompraMercadoria: string
}

/** Sem checagem de papel — usado pelo cadastro rápido de fornecedor (Entrada de Produtos por
 *  Compra) pra preencher o plano de contas padrão sem exigir ADMIN (diferente de
 *  `buscarConfiguracaoGeral`, que é ADMIN-only). */
export function buscarPlanoContasCompraMercadoria(): Promise<PlanoContasCompraMercadoria> {
  return api<PlanoContasCompraMercadoria>('/api/v1/config-geral/plano-contas-compra-mercadoria')
}

export interface UsaCorGrade {
  cfgUsaCorGrade: boolean
}

/** Aberto a qualquer papel (diferente do resto de `cfg_geral`) — usado pelo cadastro de produto
 *  (campo Grade) e pela Emissão de Etiqueta. */
export function buscarUsaCorGrade(): Promise<UsaCorGrade> {
  return api<UsaCorGrade>('/api/v1/config-geral/usa-cor-grade')
}

export interface UsaServicos {
  cfgUsaServicos: boolean
  cfgExigeSangriaFechamento: boolean
}

/** Aberto a qualquer papel — usado pelo cadastro de produto (seletor Mercadoria/Serviço) e pelo
 *  PDV. Desligado por padrão: empresa de serviço é minoria da base (decisão de 2026-08-28). */
export function buscarUsaServicos(): Promise<UsaServicos> {
  return api<UsaServicos>('/api/v1/config-geral/usa-servicos')
}

export interface DescontoVenda {
  percentualDescontoVenda: number
}

/** Aberto a qualquer papel (diferente do resto de `cfg_geral`) — usado pelo PDV (F5). */
export function buscarDescontoVenda(): Promise<DescontoVenda> {
  return api<DescontoVenda>('/api/v1/config-geral/desconto-venda')
}

export function atualizarConfiguracaoGeral(payload: ReturnType<typeof paraRequisicao>): Promise<ConfiguracaoGeral> {
  return api<ConfiguracaoGeral>('/api/v1/config-geral', { method: 'PUT', body: JSON.stringify(payload) })
}

export interface PermiteQtdDecimal {
  cfgPermiteQtdDecimal: boolean
}

/**
 * Aberto a qualquer papel (diferente do resto de `cfg_geral`) — usado por PDV, Transferência de
 * Produtos e Histórico do Cliente pra saber como formatar/validar quantidade de produto.
 */
export function buscarPermiteQtdDecimal(): Promise<PermiteQtdDecimal> {
  return api<PermiteQtdDecimal>('/api/v1/config-geral/permite-qtd-decimal')
}

export interface ExigeNumeroVendaDevolucao {
  cfgExigeNumeroVendaDevolucao: boolean
}

/** Aberto a qualquer papel (diferente do resto de `cfg_geral`) — usado pela Devolução de
 *  Produtos pra saber se o campo "Número da Venda" é obrigatório antes de gravar. */
export function buscarExigeNumeroVendaDevolucao(): Promise<ExigeNumeroVendaDevolucao> {
  return api<ExigeNumeroVendaDevolucao>('/api/v1/config-geral/exige-numero-venda-devolucao')
}

export interface RateiaFreteEntrada {
  cfgRateiaFreteEntrada: boolean
}

/** Aberto a qualquer papel (diferente do resto de `cfg_geral`) — usado pela Entrada de Produtos
 *  por Compra pra decidir se rateia frete/IPI/ICMS-ST no custo do item. */
export function buscarRateiaFreteEntrada(): Promise<RateiaFreteEntrada> {
  return api<RateiaFreteEntrada>('/api/v1/config-geral/rateia-frete-entrada')
}

export interface ReajustaPrecoEntrada {
  cfgReajustaPrecoEntrada: boolean
}

/** Aberto a qualquer papel (diferente do resto de `cfg_geral`) — usado pela Entrada de Produtos
 *  por Compra pra decidir se atualiza o preço do produto a partir do custo da entrada. */
export function buscarReajustaPrecoEntrada(): Promise<ReajustaPrecoEntrada> {
  return api<ReajustaPrecoEntrada>('/api/v1/config-geral/reajusta-preco-entrada')
}

export interface ConsisteValorContasPagar {
  cfgConsisteValorContasPagar: boolean
}

/** Aberto a qualquer papel (diferente do resto de `cfg_geral`) — usado pela Entrada de Produtos
 *  por Compra pra decidir se a soma das duplicatas é obrigada a bater com o total dos produtos. */
export function buscarConsisteValorContasPagar(): Promise<ConsisteValorContasPagar> {
  return api<ConsisteValorContasPagar>('/api/v1/config-geral/consiste-valor-contas-pagar')
}

export interface EmiteFiscalAposVenda {
  cfgEmiteFiscalAposVenda: boolean
}

/**
 * Aberto a qualquer papel (diferente do resto de `cfg_geral`) — usado pelo popup de papeleta do
 * PDV (`ComprovantePapeletaModal`) pra decidir entre emissão automática da NFC-e logo após a
 * venda (`true`, comportamento de sempre) ou botão de emissão manual (`false`).
 */
export function buscarEmiteFiscalAposVenda(): Promise<EmiteFiscalAposVenda> {
  return api<EmiteFiscalAposVenda>('/api/v1/config-geral/emite-fiscal-apos-venda')
}
