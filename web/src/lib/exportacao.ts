import writeExcelFile from 'write-excel-file/browser'
import { api } from './api'

export interface TabelaExportavel {
  chave: string
  titulo: string
}

export function listarTabelasExportacao(): Promise<TabelaExportavel[]> {
  return api('/api/v1/exportacao/tabelas')
}

export function buscarDadosExportacao(tabela: string): Promise<Record<string, unknown>[]> {
  return api(`/api/v1/exportacao/${tabela}`)
}

/** Excel proíbe [ ] / \ : * ? no nome da aba, e limita a 31 caracteres. */
function nomeAbaSeguro(titulo: string): string {
  return titulo.replace(/[[\]/\\:*?]/g, '-').slice(0, 31)
}

/**
 * Gera a planilha 100% no navegador — mesmo padrão do CRM (`write-excel-file`, ver
 * `web/src/lib/crm.ts`). Os cabeçalhos já vêm prontos do backend (alias de coluna em português,
 * `ExportacaoService`), então não há mapeamento nenhum aqui: só decide o tipo de cada célula
 * (número vira `Number`, o resto `String`) e monta o arquivo. `chave` (ex.: "contas_receber",
 * já em snake_case ASCII) vira o nome do arquivo — mais simples e seguro do que derivar um
 * slug do título em português.
 */
export async function exportarParaExcel(chave: string, titulo: string, linhas: Record<string, unknown>[]): Promise<void> {
  const colunas = Object.keys(linhas[0])
  const schema = colunas.map((coluna) => ({
    header: coluna,
    cell: (linha: Record<string, unknown>) => {
      const valor = linha[coluna]
      if (typeof valor === 'number') return { value: valor, type: Number }
      return { value: valor == null ? '' : String(valor), type: String }
    },
    width: 20,
  }))
  const carimbo = new Date().toISOString().slice(0, 10)
  await writeExcelFile(linhas, { columns: schema, sheet: nomeAbaSeguro(titulo) }).toFile(`${chave}-${carimbo}.xlsx`)
}
