import html2canvas from 'html2canvas'
import jsPDF from 'jspdf'

/** A4 em milímetros. */
const LARGURA_A4_MM = 210
const ALTURA_A4_MM = 297

/**
 * PDF do orçamento a partir do DOM já renderizado — mesmo motor (html2canvas + jsPDF) que o DANFE
 * e os relatórios usam, garantindo que o arquivo sai idêntico ao que o operador está vendo.
 *
 * <p>⚠️ `Math.max` na altura: o jsPDF **inverte largura/altura** quando o formato tem altura menor
 * que a largura, e um orçamento de 2 itens gera uma captura baixa. Sem o piso, o PDF sairia
 * deitado e com as colunas cortadas — defeito real já visto nos comprovantes curtos em
 * 2026-08-11.
 */
export async function gerarBlobOrcamentoA4(elemento: HTMLElement): Promise<Blob> {
  const canvas = await html2canvas(elemento, { scale: 2, useCORS: true, backgroundColor: '#ffffff' })
  const alturaProporcional = (canvas.height * LARGURA_A4_MM) / canvas.width
  const altura = Math.max(alturaProporcional, ALTURA_A4_MM)
  const doc = new jsPDF({ unit: 'mm', format: [LARGURA_A4_MM, altura] })
  doc.addImage(canvas.toDataURL('image/jpeg', 0.95), 'JPEG', 0, 0, LARGURA_A4_MM, alturaProporcional)
  return doc.output('blob')
}
