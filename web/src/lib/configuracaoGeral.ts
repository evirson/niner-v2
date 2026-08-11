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
  cfgExigeNumeroVendaDevolucao: boolean
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
  cfgExigeNumeroVendaDevolucao: boolean
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
    cfgExigeNumeroVendaDevolucao: c.cfgExigeNumeroVendaDevolucao,
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
    cfgExigeNumeroVendaDevolucao: f.cfgExigeNumeroVendaDevolucao,
  }
}

export function buscarConfiguracaoGeral(): Promise<ConfiguracaoGeral> {
  return api<ConfiguracaoGeral>('/api/v1/config-geral')
}

export interface UsaCorGrade {
  cfgUsaCorGrade: boolean
}

/** Aberto a qualquer papel (diferente do resto de `cfg_geral`) — usado pelo cadastro de produto
 *  (campo Grade) e pela Emissão de Etiqueta. */
export function buscarUsaCorGrade(): Promise<UsaCorGrade> {
  return api<UsaCorGrade>('/api/v1/config-geral/usa-cor-grade')
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
