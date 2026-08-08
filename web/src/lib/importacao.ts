import { api, apiUpload, ApiError, getToken } from './api'
import { API_BASE } from './config'

export interface TabelaImportavel {
  chave: string
  titulo: string
  descricao: string
}

export interface LinhaErro {
  linha: number
  motivo: string
  /** Preenchidos só quando o erro é de conversão de um campo específico (data/número
   *  inválidos) — `null` para erros de regra de negócio sem um valor cru associado. */
  campo: string | null
  valor: string | null
}

export interface RelatorioImportacao {
  confirmado: boolean
  totalLinhas: number
  linhasImportadas: number
  linhasIgnoradas: number
  linhasRejeitadas: number
  erros: LinhaErro[]
  avisos: string[]
  pendencias: string[]
}

/** Só as colunas de estoque com dado (2026-08-08) — o passo de "escolher rótulo da variante"
 *  deixou de existir: cor/tamanho têm nome fixo no sistema todo, e a grade de cada produto vem
 *  direto das colunas `NOME_GRADE`/`DESCRICAO_COR`/`DESCRICAO_TAMANHO` do próprio arquivo. */
export interface AnaliseProduto {
  colunasEstoqueComDado: string[]
}

export function listarTabelasImportacao(): Promise<TabelaImportavel[]> {
  return api('/api/v1/importacao/tabelas')
}

/** Baixa o modelo `.csv` e dispara o download no navegador (a rota exige o Bearer token, então
 *  não dá para ser um `<a href>` simples). */
export async function baixarModeloCsv(tabela: string): Promise<void> {
  const token = getToken()
  const res = await fetch(`${API_BASE}/api/v1/importacao/${tabela}/modelo`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  if (!res.ok) {
    throw new ApiError(res.status, 'Não foi possível baixar o modelo.')
  }
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${tabela}_modelo.csv`
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

export function analisarProduto(arquivo: File): Promise<AnaliseProduto> {
  const fd = new FormData()
  fd.append('arquivo', arquivo)
  return apiUpload('/api/v1/importacao/produto/analise', fd)
}

/** `confirmar=false` (padrão) só simula — nada é gravado, o relatório mostra o que aconteceria.
 *  `escolhas` é um objeto livre, interpretado por cada tabela (ver docs/telas/importacao-dados.md). */
export function processarImportacao(
  tabela: string,
  arquivo: File,
  escolhas: unknown,
  confirmar: boolean,
): Promise<RelatorioImportacao> {
  const fd = new FormData()
  fd.append('arquivo', arquivo)
  fd.append('escolhas', JSON.stringify(escolhas ?? {}))
  return apiUpload(`/api/v1/importacao/${tabela}/processar?confirmar=${confirmar}`, fd)
}
