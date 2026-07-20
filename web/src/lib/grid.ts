/**
 * Distribui `pesos` (relativos, ex.: os `col-N` originais de cada campo) em colunas
 * inteiras do grid de 12 (§3.7), somando sempre exatamente 12. Usado para a linha de campos
 * se reajustar quando um deles é ocultado pela configuração de tela (docs/telas/cliente.md,
 * item "quando um campo for setado para não visível, ajuste a tela") — em vez de deixar um
 * vão vazio, os campos que sobraram crescem proporcionalmente para preencher a linha.
 */
export function distribuirSpans(pesos: number[]): number[] {
  const total = pesos.reduce((a, b) => a + b, 0)
  if (total === 0) return []

  const brutos = pesos.map((p) => (p * 12) / total)
  const spans = brutos.map((v) => Math.max(1, Math.floor(v)))
  const somaInicial = spans.reduce((a, b) => a + b, 0)

  // método dos maiores restos: distribui as unidades que faltam pros campos com maior
  // parte fracionária, garantindo soma exata de 12 mesmo com arredondamento.
  let faltam = 12 - somaInicial
  const porResto = brutos
    .map((v, i) => ({ i, resto: v - Math.floor(v) }))
    .sort((a, b) => b.resto - a.resto)
  for (let k = 0; k < porResto.length && faltam > 0; k++) {
    spans[porResto[k].i] += 1
    faltam--
  }
  return spans
}
