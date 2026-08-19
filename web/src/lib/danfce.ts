import html2canvas from 'html2canvas'
import { jsPDF } from 'jspdf'
import { formatarMoeda } from './masks'

/**
 * Formatação e captura do DANFE (documento auxiliar da NFC-e) — 2026-08-19, reconstrução total a
 * partir de um modelo real trazido pelo dono do produto ("DANF ÓTIMA"). Antes disso o DANFCE
 * saía como texto monoespaçado (mesma família da papeleta comum), o que ficou feio e — pior —
 * cortava a chave de acesso em silêncio (linha maior que as 42 colunas). Este arquivo é a
 * contraparte lógica de `pages/pdv/DanfceImprimir.tsx`, que monta a tabela HTML de verdade.
 */

export function moedaDanfce(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

export function formatarDataHoraDanfce(iso: string): string {
  return new Date(iso).toLocaleString('pt-BR', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

/** Chave de acesso em blocos de 4 dígitos, numa string só — diferente da antiga versão
 *  monoespaçada (que precisava quebrar manualmente em 2 linhas pra não cortar caractere), aqui o
 *  HTML quebra linha sozinho dentro da largura fixa do DANFE. */
export function formatarChaveGrupos4(chave: string): string {
  return chave.replace(/(\d{4})(?=\d)/g, '$1 ')
}

/** "000.000.015" — número da NFC-e (nNF) em 9 dígitos com pontos a cada 3, convenção do DANFE. */
export function formatarNumeroNfce(numero: number): string {
  return String(numero).padStart(9, '0').replace(/(\d{3})(?=\d)/g, '$1.')
}

/** Quantidade sempre em 3 casas ("1,000") — convenção do modelo de referência, independente de
 *  `cfg_permite_qtd_decimal` do tenant: o DANFE mostra o que foi de fato gravado na nota. */
export function formatarQuantidadeDanfce(qtd: number): string {
  return qtd.toLocaleString('pt-BR', { minimumFractionDigits: 3, maximumFractionDigits: 3 })
}

/** Largura fixa (mm) do rolo térmico — mesma de todo o resto de `comprovante.ts`. O PDF do
 *  DANFE não pagina (o rolo não tem limite de altura): a imagem inteira vira uma página só, do
 *  tamanho exato do conteúdo capturado. */
const LARGURA_MM = 80

/**
 * Captura o DANFE já renderizado no DOM (com a calibragem de impressão térmica aplicada pelo
 * `.danfce-imprimir` de `styles.css`) e embute como imagem num PDF do tamanho exato do conteúdo
 * — mesmo padrão html2canvas+jsPDF já usado nos relatórios (`relatorioVendasCaptura.ts` e
 * companhia), adaptado pra bobina térmica em vez de A4 paginado. Usado pra subir o PDF pro
 * compartilhamento por WhatsApp — a impressão em si (`window.print()`) usa o DOM real, não esta
 * captura, então print e PDF podem divergir só se a tela mudar entre um clique e outro (mesma
 * janela de risco que já existia na papeleta comum).
 */
export async function gerarBlobDanfce(elemento: HTMLElement): Promise<Blob> {
  const canvas = await html2canvas(elemento, { scale: 3, useCORS: true, backgroundColor: '#ffffff' })
  const alturaMm = (canvas.height * LARGURA_MM) / canvas.width
  const doc = new jsPDF({ unit: 'mm', format: [LARGURA_MM, alturaMm] })
  doc.addImage(canvas.toDataURL('image/jpeg', 0.95), 'JPEG', 0, 0, LARGURA_MM, alturaMm)
  return doc.output('blob')
}
