import { api } from './api'

/** Configuração por tenant de um campo de tela (docs/telas/configuracao-tela.md). */
export interface ConfiguracaoCampo {
  campo: string
  visivel: boolean
  obrigatorio: boolean
}

export function buscarConfiguracaoTela(chaveTela: string): Promise<ConfiguracaoCampo[]> {
  return api<ConfiguracaoCampo[]>(`/api/v1/config-tela/${chaveTela}`)
}

export function salvarConfiguracaoTela(
  chaveTela: string,
  campos: ConfiguracaoCampo[],
): Promise<ConfiguracaoCampo[]> {
  return api<ConfiguracaoCampo[]>(`/api/v1/config-tela/${chaveTela}`, {
    method: 'PUT',
    body: JSON.stringify(campos),
  })
}

/** Monta um mapa `campo -> configuração` para consulta rápida ao renderizar um formulário. */
export function paraMapa(config: ConfiguracaoCampo[] | undefined): Map<string, ConfiguracaoCampo> {
  return new Map((config ?? []).map((c) => [c.campo, c]))
}
