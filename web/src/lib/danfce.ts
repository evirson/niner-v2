import html2canvas from 'html2canvas'
import { jsPDF } from 'jspdf'
import { formatarMoeda, formatarQuantidade } from './masks'

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

/** 3 casas ("1,000") se o tenant permite quantidade decimal, inteiro ("1") se não — 2026-08-19:
 *  a versão anterior ignorava `cfg_permite_qtd_decimal` de propósito ("o DANFE mostra o que foi
 *  de fato gravado na nota"), mas isso saía errado justamente pro caso comum, tenant que não
 *  trabalha com fração nenhuma: toda quantidade já é inteira, e "1,000UN" no cupom parecia bug pro
 *  operador. Mesma convenção de {@link formatarQuantidade} (`masks.ts`), usada em todo o resto do
 *  PDV. */
export function formatarQuantidadeDanfce(qtd: number, permiteDecimal: boolean): string {
  return formatarQuantidade(qtd, permiteDecimal)
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
  // ⚠️ Piso de LARGURA_MM na altura da página (2026-08-21, achado em auditoria — era o único
  // gerador de bobina do projeto sem ele). O jsPDF decide a orientação comparando os dois lados:
  // numa captura mais baixa que larga ele entende RETRATO, TROCA largura por altura e o PDF sai
  // deitado e cortado. Já aconteceu neste projeto em 2026-08-11; os irmãos (`comprovante.ts`,
  // `orcamentoPdf.ts`) ganharam o guard na época e o DANFCE ficou de fora.
  const doc = new jsPDF({ unit: 'mm', format: [LARGURA_MM, Math.max(LARGURA_MM, alturaMm)] })
  doc.addImage(canvas.toDataURL('image/jpeg', 0.95), 'JPEG', 0, 0, LARGURA_MM, alturaMm)
  return doc.output('blob')
}
