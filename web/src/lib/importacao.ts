import { readSheet } from 'read-excel-file/browser'
import { api, apiUpload, ApiError, getToken } from './api'
import { API_BASE } from './config'

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

/** {@code tabela} vem `null` quando o backend não identificou o tipo da planilha com confiança
 *  suficiente — usado para validar, assim que o usuário escolhe o arquivo, que a planilha
 *  selecionada é mesmo da tabela desta tela (ver `ImportacaoTabelaPage.tsx`). */
export interface DeteccaoArquivo {
  tabela: string | null
  colunasArquivo: string[]
}

export function detectarArquivo(arquivo: File): Promise<DeteccaoArquivo> {
  const fd = new FormData()
  fd.append('arquivo', arquivo)
  return apiUpload('/api/v1/importacao/detectar', fd)
}

/** Total de registros da planilha, contado no navegador assim que o arquivo é escolhido
 *  (2026-08-11, mostrado antes mesmo de clicar em Validar/Importar) — lê o arquivo localmente
 *  (via Web Worker, não trava a tela) e subtrai 1 pela linha de cabeçalho. Falha de leitura aqui
 *  não deve travar o fluxo (é só um número informativo): o chamador decide o que fazer. */
export async function contarLinhasPlanilha(arquivo: File): Promise<number> {
  const linhas = await readSheet(arquivo)
  return Math.max(0, linhas.length - 1)
}

export interface ProgressoImportacao {
  /** `"leitura"` | `"validando"` | `"importando"` | `"desconhecido"` (id não existe — ainda não
   *  começou ou já terminou; o front deve simplesmente ignorar essa leitura). */
  etapa: string
  atual: number
  total: number
}

/** Polling de progresso "ao vivo" (2026-08-11) — consultado em intervalo curto enquanto
 *  `processarImportacao` está em voo, pro gauge mostrar registro atual/total real em vez de só
 *  uma animação. */
export function buscarProgressoImportacao(idProgresso: string): Promise<ProgressoImportacao> {
  return api(`/api/v1/importacao/progresso/${idProgresso}`)
}

/** Baixa o modelo `.xlsx` e dispara o download no navegador (a rota exige o Bearer token, então
 *  não dá para ser um `<a href>` simples). */
export async function baixarModeloPlanilha(tabela: string): Promise<void> {
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
  a.download = `${tabela}_modelo.xlsx`
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

/** `confirmar=false` (padrão) só simula — nada é gravado, o relatório mostra o que aconteceria.
 *  `escolhas` é um objeto livre, interpretado por cada tabela (ver docs/telas/importacao-dados.md).
 *  `idProgresso` (2026-08-11) é gerado pelo chamador a cada clique — usado só pelo polling de
 *  progresso, ver {@link buscarProgressoImportacao}. */
export function processarImportacao(
  tabela: string,
  arquivo: File,
  escolhas: unknown,
  confirmar: boolean,
  idProgresso: string,
): Promise<RelatorioImportacao> {
  const fd = new FormData()
  fd.append('arquivo', arquivo)
  fd.append('escolhas', JSON.stringify(escolhas ?? {}))
  return apiUpload(
    `/api/v1/importacao/${tabela}/processar?confirmar=${confirmar}&idProgresso=${encodeURIComponent(idProgresso)}`,
    fd,
  )
}
