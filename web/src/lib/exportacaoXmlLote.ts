import { ApiError, api, getToken } from './api'
import { API_BASE } from './config'

/** Pré-conferência mostrada antes do clique — ver `docs/telas/exportacao-xml-lote.md`. */
export interface ResumoExportacaoXml {
  nomeArquivo: string
  totalDocumentos: number
  documentosComXml: number
  /** Notas do período que ainda não tiveram o XML gravado no bucket — ficam de fora do pacote. */
  documentosSemXml: number
  totalEventos: number
  limiteDocumentos: number
  excedeLimite: boolean
}

export interface FiltrosExportacaoXml {
  idEmpresa: number
  dataInicial: string
  dataFinal: string
  /** 65 = NFC-e · 55 = NF-e · ausente = os dois. */
  modelo?: number
}

function querystring(filtros: FiltrosExportacaoXml): string {
  const params = new URLSearchParams()
  params.set('idEmpresa', String(filtros.idEmpresa))
  params.set('dataInicial', filtros.dataInicial)
  params.set('dataFinal', filtros.dataFinal)
  if (filtros.modelo) params.set('modelo', String(filtros.modelo))
  return params.toString()
}

export function resumirExportacaoXml(filtros: FiltrosExportacaoXml): Promise<ResumoExportacaoXml> {
  return api<ResumoExportacaoXml>(`/api/v1/fiscal/exportacao-xml/resumo?${querystring(filtros)}`)
}

/**
 * Assinatura mínima da File System Access API. ⚠️ Não está na `lib.dom` desta versão do TS e
 * **não existe em todo navegador** (Firefox e Safari não têm), então é declarada aqui e sempre
 * testada antes de usar — nunca assumida.
 */
interface SalvarComoPicker {
  showSaveFilePicker(opcoes: {
    suggestedName?: string
    types?: { description: string; accept: Record<string, string[]> }[]
  }): Promise<{ createWritable(): Promise<{ write(dado: Blob): Promise<void>; close(): Promise<void> }> }>
}

function pickerDisponivel(): SalvarComoPicker | null {
  const w = window as unknown as Partial<SalvarComoPicker>
  return typeof w.showSaveFilePicker === 'function' ? (w as SalvarComoPicker) : null
}

/** O usuário fechou o "Salvar como" — desistir não é erro e não pode virar Toast vermelho. */
export class DownloadCancelado extends Error {
  constructor() {
    super('Download cancelado pelo usuário.')
  }
}

/**
 * Baixa o ZIP da exportação.
 *
 * <p>⚠️ **Sobre "o usuário escolhe onde salvar" (item 4 do pedido).** Nenhum navegador deixa uma
 * página escrever numa pasta escolhida sem intermediação. O que existe são dois caminhos, e esta
 * função usa os dois nesta ordem:
 *
 * 1. `showSaveFilePicker()` — abre o diálogo **"Salvar como"** de verdade (pasta + nome). Só no
 *    Chrome/Edge/Opera de desktop, em contexto seguro (HTTPS ou localhost).
 * 2. Fallback universal: download normal (`<a download>` sobre um `blob:`), que cai na pasta de
 *    downloads do navegador — o usuário só escolhe a pasta se tiver ligado "Perguntar onde salvar
 *    cada arquivo" nas configurações do próprio navegador, o que está fora do alcance do produto.
 *
 * <p>⚠️ A requisição precisa do Bearer token, então não dá para ser um `<a href>` simples — o
 * arquivo vem por `fetch` e vira `blob` (mesmo padrão de `baixarModeloPlanilha`).
 */
export async function baixarZipExportacaoXml(filtros: FiltrosExportacaoXml, nomeArquivo: string): Promise<void> {
  const token = getToken()
  const res = await fetch(`${API_BASE}/api/v1/fiscal/exportacao-xml?${querystring(filtros)}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  if (!res.ok) {
    // ⚠️ O backend responde 409 com uma mensagem que diz o que fazer ("aguarde o arquivamento",
    // "reduza o período"). Trocá-la por um genérico apagaria o trabalho de escrevê-la — foi o que
    // o `catch` do comprovante fiscal fez com o bloqueio preventivo F11 (2026-08-24).
    let mensagem = 'Não foi possível gerar o arquivo ZIP.'
    try {
      const problema = await res.json()
      mensagem = problema.detail || problema.title || mensagem
    } catch {
      /* resposta sem corpo JSON */
    }
    throw new ApiError(res.status, mensagem)
  }

  const blob = await res.blob()
  const picker = pickerDisponivel()
  if (picker) {
    try {
      const arquivo = await picker.showSaveFilePicker({
        suggestedName: nomeArquivo,
        types: [{ description: 'Arquivo compactado ZIP', accept: { 'application/zip': ['.zip'] } }],
      })
      const escrita = await arquivo.createWritable()
      await escrita.write(blob)
      await escrita.close()
      return
    } catch (e) {
      // Desistir do "Salvar como" lança AbortError — é escolha do usuário, não falha.
      if (e instanceof DOMException && e.name === 'AbortError') {
        throw new DownloadCancelado()
      }
      // Qualquer outro tropeço do picker (permissão negada, contexto não seguro) cai no download
      // normal: melhor entregar o arquivo na pasta de downloads que não entregar.
    }
  }

  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = nomeArquivo
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}
