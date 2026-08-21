import JsBarcode from 'jsbarcode'
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import {
  CAMPOS_DE_BARRAS,
  CSS_ALINHAMENTO_ETIQUETA,
  CSS_FONTE_ETIQUETA,
  montarDescricaoImpressa,
  type CampoEtiquetaPosicionado,
  type ProdutoExemplo,
} from '../../lib/etiquetaConfig'
import { formatarMoeda } from '../../lib/masks'

/** Valor de mentirinha, EAN-13 válido de verdade (dígito verificador conferido — obrigatório
 * agora que o desenho usa o formato `EAN13`, que valida o dígito e recusa desenhar se não bater),
 * só pra o código de barras ter uma cara real quando nenhum produto de exemplo foi escolhido —
 * layout, não impressão de verdade. */
const VALOR_BARRA_EXEMPLO = '9000000000018'

/**
 * ⚠️ **O tamanho da fonte precisa acompanhar a escala do desenho — este era o defeito que fazia a
 * tela mentir** (achado em 2026-08-20, com etiqueta impressa na mão).
 *
 * <p>Todo o resto deste componente é dimensionado em `mm × escalaPxPorMm`, mas o `font-size` saía
 * em `pt`, que é unidade **absoluta de tela**: 7pt são sempre ~9,3px, não importa se 1mm vale
 * 12px (editor a 200%), 3,78px (impressão) ou 3px (prévia do rolo). O texto ficava então **3,2×
 * menor que o real** no editor e 1,26× maior na prévia — a descrição cabia numa linha na tela e
 * quebrava em três no papel, invadindo o campo do preço.
 *
 * <p>Convertendo pt→mm→px pela mesma escala do restante, os três contextos passam a mostrar a
 * MESMA proporção texto/etiqueta. Na impressão o resultado é idêntico ao anterior
 * (`pt × 25,4/72 × 96/25,4 = pt × 96/72`, que é exatamente o que `Npt` já valia a 96dpi) — ou
 * seja, **o papel não muda; a tela é que passa a dizer a verdade**.
 */
const MM_POR_PONTO = 25.4 / 72

/** Altura reservada para o texto legível sob as barras, em mm — o jsbarcode desenha esse texto
 *  com fonte própria, então ele também precisa ser medido em milímetro e não em pixel fixo. */
const ALTURA_TEXTO_BARRAS_MM = 3

function ehCampoDeBarras(campo: CampoEtiquetaPosicionado['campo']): boolean {
  return (CAMPOS_DE_BARRAS as string[]).includes(campo)
}

function valorTextoDoCampo(campo: CampoEtiquetaPosicionado, produto: ProdutoExemplo | null, nomeEmpresa: string): string {
  switch (campo.campo) {
    case 'NOME_EMPRESA':
      return nomeEmpresa
    case 'DESCRICAO_PRODUTO':
      return montarDescricaoImpressa(produto)
    case 'PRECO_VENDA':
      return `R$ ${formatarMoeda(produto?.precoVenda ?? 99.9)}`
    default:
      return ''
  }
}

function valorDeBarrasDoCampo(_campo: CampoEtiquetaPosicionado, produto: ProdutoExemplo | null): string {
  return produto?.sku ?? VALOR_BARRA_EXEMPLO
}

/** Renderiza o código de barras de verdade (SVG, `jsbarcode`) — **sempre `EAN13`** (2026-08-05,
 * pedido do dono do produto: a tela tem que imprimir no formato real, não um placeholder
 * genérico). Seguro porque `sku` **sempre** vem de `gerar_ean13_interno()` (13 dígitos, dígito
 * verificador correto por construção — `CLAUDE.md`) ou do valor de exemplo fixo acima, também
 * válido — nunca um valor livre digitado. O `try/catch` continua como rede de segurança (deixa o
 * SVG em branco em vez de derrubar a tela) para qualquer valor inesperado que escape dessa
 * garantia, já que `format: 'EAN13'` do jsbarcode **valida** o dígito verificador e recusa
 * desenhar se não bater (diferente do CODE128 anterior, que aceitava qualquer string).
 * `preserveAspectRatio="none"` (2026-08-05): o `viewBox` que o jsbarcode gera tem a largura
 * sempre fixa (função só do texto codificado, não da escala do desenho) mas a altura muda com
 * `alturaPx` — então a PROPORÇÃO do viewBox varia entre o editor grande e a prévia do rolo
 * pequena, e sem essa flag o SVG preserva a proporção original ("meet") deixando espaço vazio
 * ao redor das barras, mais sobra quanto menor a escala. Esticar sem manter proporção garante
 * que as barras preencham a caixa inteira nos dois lugares, do mesmo jeito. */
function CodigoDeBarras({ valor, larguraPx, alturaPx, exibirTexto, escalaPxPorMm }: {
  valor: string
  larguraPx: number
  alturaPx: number
  exibirTexto: boolean
  escalaPxPorMm: number
}) {
  const svgRef = useRef<SVGSVGElement>(null)

  useEffect(() => {
    if (!svgRef.current || larguraPx <= 0 || alturaPx <= 0) return
    try {
      // ⚠️ `alturaTextoPx` e `fontSize` em MILÍMETRO convertido, não em pixel fixo: com valores
      // fixos, a proporção barras/texto mudava entre editor, impressão e prévia — o mesmo defeito
      // do `font-size` em pt (ver MM_POR_PONTO acima).
      const alturaTextoPx = exibirTexto ? ALTURA_TEXTO_BARRAS_MM * escalaPxPorMm : 0
      JsBarcode(svgRef.current, valor || VALOR_BARRA_EXEMPLO, {
        format: 'EAN13',
        width: 2,
        height: Math.max(alturaPx - alturaTextoPx, 1),
        displayValue: exibirTexto,
        fontSize: Math.max(alturaTextoPx * 0.9, 1),
        textMargin: 0,
        margin: 0,
      })
    } catch (e) {
      // Valor não desenhável (ex.: vazio) — deixa o SVG em branco em vez de derrubar a tela.
      console.warn('Não foi possível desenhar o código de barras de exemplo:', e)
    }
  }, [valor, larguraPx, alturaPx, exibirTexto, escalaPxPorMm])

  return (
    <svg
      ref={svgRef}
      width={larguraPx}
      height={alturaPx}
      preserveAspectRatio="none"
      style={{ display: 'block' }}
    />
  )
}

/**
 * Conteúdo (texto real de mentirinha ou de produto de exemplo, ou código de barras de verdade)
 * + estilo (fonte/tamanho/negrito/fundo/alinhamento) de UM campo posicionado na etiqueta.
 * Componente "puro" — não sabe de arraste nem seleção; é reaproveitado pela célula interativa do
 * editor e pela faixa de prévia (só leitura) do rolo completo.
 */
export default function CampoEtiquetaVisual({
  campo,
  escalaPxPorMm,
  produtoExemplo,
  nomeEmpresaExemplo,
  style,
  aoMedirTransbordo,
  ...resto
}: {
  campo: CampoEtiquetaPosicionado
  escalaPxPorMm: number
  produtoExemplo: ProdutoExemplo | null
  nomeEmpresaExemplo: string
  style?: React.CSSProperties
  /** Chamado quando o conteúdo passa a caber (ou a não caber) na caixa — ver {@link useTransbordo}. */
  aoMedirTransbordo?: (transborda: boolean) => void
} & React.HTMLAttributes<HTMLDivElement>) {
  const larguraPx = (campo.larguraMm ?? 10) * escalaPxPorMm
  const alturaPx = (campo.alturaMm ?? 6) * escalaPxPorMm
  const conteudoRef = useRef<HTMLDivElement>(null)
  const [transborda, setTransborda] = useState(false)

  const estiloBase: React.CSSProperties = {
    position: 'absolute',
    left: campo.posicaoXMm * escalaPxPorMm,
    top: campo.posicaoYMm * escalaPxPorMm,
    width: larguraPx,
    height: alturaPx,
    fontFamily: CSS_FONTE_ETIQUETA[campo.fonte],
    fontWeight: campo.negrito ? 700 : 400,
    // ⚠️ pt → mm → px pela escala do desenho. Nunca volte para `${pt}pt`: ver MM_POR_PONTO.
    fontSize: `${(campo.tamanhoFontePt ?? 7) * MM_POR_PONTO * escalaPxPorMm}px`,
    textAlign: CSS_ALINHAMENTO_ETIQUETA[campo.alinhamento],
    color: campo.fundoPreto ? '#fff' : '#000',
    background: campo.fundoPreto ? '#000' : 'transparent',
    // ⚠️ Sem isto, "fundo preto" imprime BRANCO NO BRANCO — campo invisível no papel, perfeito na
    // tela (2026-08-21). O navegador suprime cor de fundo na impressão por padrão (a caixa
    // "Gráficos de segundo plano" nasce desmarcada); texto e SVG não são afetados, fundo é.
    // Descoberto porque a régua de calibragem saiu do papel só com os números.
    printColorAdjust: 'exact',
    WebkitPrintColorAdjust: 'exact',
    overflow: 'hidden',
    lineHeight: 1.15,
    whiteSpace: 'normal',
    wordBreak: 'break-word',
    overflowWrap: 'break-word',
    display: 'flex',
    alignItems: 'center',
    justifyContent: campo.alinhamento === 'CENTRO' ? 'center' : campo.alinhamento === 'DIREITA' ? 'flex-end' : 'flex-start',
    ...style,
  }

  /**
   * ⚠️ Mede se o texto REALMENTE cabe, comparando `scrollHeight` com `clientHeight` — não estima.
   *
   * <p>Motivo: foi exatamente assim que a etiqueta saiu bagunçada (2026-08-20). A descrição
   * "SAPATENIS MASC PEGADA REF: 111801 COURO PRETO 38" ocupava uma linha na tela e três no papel;
   * as duas linhas extras vazavam por cima do preço. Com `overflow: hidden` o excesso some
   * silenciosamente na tela — o operador só descobre com a etiqueta impressa na mão.
   *
   * <p>`useLayoutEffect` porque a medida tem de acontecer depois do layout e antes da pintura,
   * senão a borda de aviso pisca a cada arraste.
   */
  useLayoutEffect(() => {
    const el = conteudoRef.current
    if (!el || !aoMedirTransbordo) return
    const passou = el.scrollHeight > el.clientHeight + 1 || el.scrollWidth > el.clientWidth + 1
    setTransborda(passou)
    aoMedirTransbordo(passou)
  })

  return (
    <div ref={conteudoRef} style={estiloBase} data-transborda={transborda ? 'sim' : undefined} {...resto}>
      {ehCampoDeBarras(campo.campo) ? (
        <CodigoDeBarras
          valor={valorDeBarrasDoCampo(campo, produtoExemplo)}
          larguraPx={larguraPx}
          alturaPx={alturaPx}
          exibirTexto={Boolean(campo.exibirTextoLegivel)}
          escalaPxPorMm={escalaPxPorMm}
        />
      ) : (
        valorTextoDoCampo(campo, produtoExemplo, nomeEmpresaExemplo)
      )}
    </div>
  )
}
