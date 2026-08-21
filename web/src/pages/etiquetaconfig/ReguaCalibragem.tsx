/**
 * Régua de calibragem do Teste de Impressão (2026-08-21).
 *
 * Duas réguas de 100 mm — uma deitada, uma em pé — desenhadas em mm na mesma escala física das
 * etiquetas, impressas antes delas. O lojista mede com uma régua de verdade e lê o resultado:
 * deu 100 mm, a impressora está 1:1; deu 92, ela encolhe 8%.
 *
 * <p><b>Por que isto existe.</b> Quando o quadro impresso se afasta da etiqueta física e o erro
 * cresce a cada coluna, há duas causas possíveis e elas produzem exatamente o mesmo estrago:
 * (a) as medidas do cadastro estão erradas — o vão do rolo não é o que se digitou; (b) as medidas
 * estão certas e o driver/navegador **escala a página inteira**. Olhando a etiqueta impressa é
 * impossível separar as duas, e o conserto de uma não conserta a outra: corrigir os mm quando o
 * problema era escala deixa certo naquela impressora e errado em todas as outras.
 *
 * <p><b>Por que 100 mm e não o tamanho da etiqueta.</b> Erro percentual só é legível em distância
 * longa. 3% numa etiqueta de 33 mm dá 1 mm, dentro do erro de quem lê uma régua; os mesmos 3% em
 * 100 mm dão 3 mm, que qualquer um enxerga. É o mesmo motivo pelo qual a medição do rolo se faz
 * do começo de uma etiqueta ao começo da terceira, nunca medindo o vão.
 *
 * <p><b>Duas réguas porque os dois eixos podem escalar diferente.</b> Em impressora térmica a
 * horizontal é a densidade de pontos do cabeçote (fixa, de fábrica) e a vertical é o avanço do
 * motor, que varia com a tração do rolo. Uma régua só esconderia metade do problema.
 *
 * <p>⚠️ <b>Os traços são SVG, não `<div>`</b> — e as duas tentativas anteriores morreram na
 * impressora, cada uma por um motivo diferente (2026-08-21, as duas descobertas imprimindo):
 * <ol>
 *   <li><b>`background: #000` não imprime.</b> Saiu do papel uma régua só com os números. O
 *       navegador suprime cor de fundo na impressão por padrão — é a caixa "Gráficos de segundo
 *       plano" do diálogo, desmarcada de fábrica. `color` de texto não é afetado (por isso os
 *       números saíram) e conteúdo SVG também não (por isso o código de barras, que o `JsBarcode`
 *       desenha como `<rect>`, sempre saiu perfeito).</li>
 *   <li><b>`border` imprime, mas o navegador arredonda a espessura para pixel inteiro.</b> Medido:
 *       0,5 mm pedido (1,89 px) virou 1 px = 0,26 mm. Traço fino em térmica, que é 1 bit, sai
 *       falhado — o mesmo motivo pelo qual todo comprovante do projeto sai em negrito.</li>
 * </ol>
 * SVG não sofre nenhum dos dois: é conteúdo (não fundo) e é vetor (rasterizado na resolução de
 * saída, sem arredondar em pixel de tela). O código de barras é a prova viva de que funciona
 * nesta impressora.
 *
 * <p>⚠️ Todo tamanho aqui — inclusive `fontSize` — sai de mm × `escalaPxPorMm`. Unidade absoluta
 * (`pt`/`px` fixo) dentro de um desenho escalado faz a peça mentir; foi o defeito da V056.
 */

/** Comprimento de referência das réguas. Redondo de propósito: o lojista confere de cabeça. */
export const COMPRIMENTO_REGUA_PADRAO_MM = 100

const ALTURA_BANDA_HORIZONTAL_MM = 14
const ALTURA_LEGENDA_MM = 9
/** 0,5 mm ≈ 4 pontos numa térmica de 203 dpi — grosso o bastante para não sair falhado. */
const ESPESSURA_TRACO_MM = 0.5
const RECUO_ESQUERDO_VERTICAL_MM = 5
/** y (mm) da linha de base da régua deitada, dentro do bloco. Os traços sobem a partir dela. */
const BASE_HORIZONTAL_MM = 4

/** Altura total (mm) que o bloco de calibragem ocupa na folha — entra no `@page` e no `top` da
 *  primeira fileira de etiquetas, por isso é exportada em vez de ficar implícita no JSX. */
export const ALTURA_CALIBRAGEM_MM =
  ALTURA_BANDA_HORIZONTAL_MM + ALTURA_LEGENDA_MM + COMPRIMENTO_REGUA_PADRAO_MM + ALTURA_LEGENDA_MM

/** y (mm) onde a régua em pé começa. */
const TOPO_VERTICAL_MM = ALTURA_BANDA_HORIZONTAL_MM + ALTURA_LEGENDA_MM

/**
 * Quanto a régua deitada pode medir neste rolo — 100 mm quando cabe, senão o maior múltiplo de
 * 10 que cabe (mínimo 20). Rolo estreito ainda calibra; só com menos resolução.
 */
export function comprimentoReguaHorizontalMm(larguraRoloMm: number, margemEsquerdaMm: number): number {
  const disponivel = larguraRoloMm - margemEsquerdaMm - 2
  const emDezenas = Math.floor(disponivel / 10) * 10
  return Math.max(20, Math.min(COMPRIMENTO_REGUA_PADRAO_MM, emDezenas))
}

/** Marcas de uma régua: ponta (0 e fim), dezena, e meia-dezena. Comprimento do traço em mm. */
function marcas(comprimentoMm: number): { posicaoMm: number; tracoMm: number; numerada: boolean }[] {
  const lista: { posicaoMm: number; tracoMm: number; numerada: boolean }[] = []
  for (let mm = 0; mm <= comprimentoMm; mm += 5) {
    const ponta = mm === 0 || mm === comprimentoMm
    const dezena = mm % 10 === 0
    lista.push({
      posicaoMm: mm,
      tracoMm: ponta ? 4 : dezena ? 2.5 : 1.2,
      // A cada 20 mm pra não amontoar número em rolo estreito; as pontas sempre.
      numerada: ponta || mm % 20 === 0,
    })
  }
  return lista
}

export default function ReguaCalibragem({
  escalaPxPorMm,
  larguraRoloMm,
  margemEsquerdaMm,
}: {
  escalaPxPorMm: number
  larguraRoloMm: number
  margemEsquerdaMm: number
}) {
  const px = (mm: number) => mm * escalaPxPorMm
  const comprimentoH = comprimentoReguaHorizontalMm(larguraRoloMm, margemEsquerdaMm)
  const comprimentoV = COMPRIMENTO_REGUA_PADRAO_MM
  const espessura = px(ESPESSURA_TRACO_MM)
  const xBaseV = margemEsquerdaMm + RECUO_ESQUERDO_VERTICAL_MM

  const estiloTexto = (tamanhoMm: number) => ({
    position: 'absolute' as const,
    fontFamily: 'Arial, Helvetica, sans-serif',
    // ⚠️ mm × escala, nunca `pt` — ver o cabeçalho deste arquivo.
    fontSize: px(tamanhoMm),
    lineHeight: 1,
    fontWeight: 700,
    color: '#000',
    whiteSpace: 'nowrap' as const,
  })

  const legenda = (texto: string, topoMm: number) => (
    <div style={{ ...estiloTexto(2.6), left: px(margemEsquerdaMm), top: px(topoMm), width: px(larguraRoloMm) }}>{texto}</div>
  )

  return (
    <div style={{ position: 'absolute', left: 0, top: 0, width: px(larguraRoloMm), height: px(ALTURA_CALIBRAGEM_MM) }}>
      {/* Todo traço das duas réguas num SVG só — conteúdo vetorial, imune à supressão de fundo e
          ao arredondamento de borda em pixel inteiro. Ver o cabeçalho deste arquivo. */}
      <svg
        width={px(larguraRoloMm)}
        height={px(ALTURA_CALIBRAGEM_MM)}
        viewBox={`0 0 ${px(larguraRoloMm)} ${px(ALTURA_CALIBRAGEM_MM)}`}
        style={{ position: 'absolute', left: 0, top: 0, display: 'block' }}
        shapeRendering="crispEdges"
      >
        {/* Régua deitada: linha de base, traços subindo a partir dela */}
        <rect x={px(margemEsquerdaMm)} y={px(BASE_HORIZONTAL_MM)} width={px(comprimentoH)} height={espessura} fill="#000" />
        {marcas(comprimentoH).map(({ posicaoMm, tracoMm }) => (
          <rect
            key={`h${posicaoMm}`}
            x={px(margemEsquerdaMm + posicaoMm)}
            y={px(BASE_HORIZONTAL_MM - tracoMm)}
            width={espessura}
            height={px(tracoMm)}
            fill="#000"
          />
        ))}

        {/* Régua em pé: linha de base, traços saindo para a esquerda */}
        <rect x={px(xBaseV)} y={px(TOPO_VERTICAL_MM)} width={espessura} height={px(comprimentoV)} fill="#000" />
        {marcas(comprimentoV).map(({ posicaoMm, tracoMm }) => (
          <rect
            key={`v${posicaoMm}`}
            x={px(xBaseV - tracoMm)}
            y={px(TOPO_VERTICAL_MM + posicaoMm)}
            width={px(tracoMm)}
            height={espessura}
            fill="#000"
          />
        ))}
      </svg>

      {/* Números e legendas ficam em HTML: texto sai por `color`, que a impressão nunca suprime. */}
      {marcas(comprimentoH)
        .filter((m) => m.numerada)
        .map(({ posicaoMm }) => (
          <div
            key={`nh${posicaoMm}`}
            style={{
              ...estiloTexto(2.4),
              // Caixa de 10 mm centrada no traço, presa às bordas da régua para o "0" não sair do
              // papel nem o último número passar do fim do rolo.
              left: px(margemEsquerdaMm + Math.min(Math.max(posicaoMm - 5, 0), comprimentoH - 10)),
              top: px(BASE_HORIZONTAL_MM + 1),
              width: px(10),
              textAlign: posicaoMm === 0 ? 'left' : posicaoMm === comprimentoH ? 'right' : 'center',
            }}
          >
            {posicaoMm}
          </div>
        ))}
      {legenda(`MEDE ${comprimentoH} MM NA HORIZONTAL`, ALTURA_BANDA_HORIZONTAL_MM - 4)}

      {marcas(comprimentoV)
        .filter((m) => m.numerada)
        .map(({ posicaoMm }) => (
          <div
            key={`nv${posicaoMm}`}
            style={{
              ...estiloTexto(2.4),
              left: px(xBaseV + 1.5),
              top: px(TOPO_VERTICAL_MM + posicaoMm - 1.2),
            }}
          >
            {posicaoMm}
          </div>
        ))}
      {legenda(
        `MEDE ${comprimentoV} MM NA VERTICAL — SE NAO DER, A IMPRESSORA ESTA ESCALANDO`,
        ALTURA_CALIBRAGEM_MM - 5,
      )}
    </div>
  )
}
