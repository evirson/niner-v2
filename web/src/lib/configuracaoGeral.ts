import { api } from './api'
import { desmascararPercentual, formatarPercentual } from './masks'

export interface ConfiguracaoGeral {
  percentualDescontoVenda: number
  jurosCrediarioDias: number
  jurosCrediario: number
  multaCrediarioDias: number
  multaCrediario: number
  cfgUsaVarianteLinha: boolean
  cfgUsaVarianteColuna: boolean
  atualizadoEm: string
}

/** Estado do formulário — strings para casar com inputs controlados (padrão do projeto). */
export interface ConfiguracaoGeralFormState {
  percentualDescontoVenda: string
  jurosCrediarioDias: string
  jurosCrediario: string
  multaCrediarioDias: string
  multaCrediario: string
  cfgUsaVarianteLinha: boolean
  cfgUsaVarianteColuna: boolean
}

export function paraFormulario(c: ConfiguracaoGeral): ConfiguracaoGeralFormState {
  return {
    percentualDescontoVenda: formatarPercentual(c.percentualDescontoVenda),
    jurosCrediarioDias: String(c.jurosCrediarioDias),
    jurosCrediario: formatarPercentual(c.jurosCrediario),
    multaCrediarioDias: String(c.multaCrediarioDias),
    multaCrediario: formatarPercentual(c.multaCrediario),
    cfgUsaVarianteLinha: c.cfgUsaVarianteLinha,
    cfgUsaVarianteColuna: c.cfgUsaVarianteColuna,
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
    cfgUsaVarianteLinha: f.cfgUsaVarianteLinha,
    cfgUsaVarianteColuna: f.cfgUsaVarianteColuna,
  }
}

export function buscarConfiguracaoGeral(): Promise<ConfiguracaoGeral> {
  return api<ConfiguracaoGeral>('/api/v1/config-geral')
}

export function atualizarConfiguracaoGeral(payload: ReturnType<typeof paraRequisicao>): Promise<ConfiguracaoGeral> {
  return api<ConfiguracaoGeral>('/api/v1/config-geral', { method: 'PUT', body: JSON.stringify(payload) })
}
