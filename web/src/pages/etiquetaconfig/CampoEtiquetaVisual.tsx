import JsBarcode from 'jsbarcode'
import { useEffect, useRef } from 'react'
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
function CodigoDeBarras({ valor, larguraPx, alturaPx, exibirTexto }: {
  valor: string
  larguraPx: number
  alturaPx: number
  exibirTexto: boolean
}) {
  const svgRef = useRef<SVGSVGElement>(null)

  useEffect(() => {
    if (!svgRef.current || larguraPx <= 0 || alturaPx <= 0) return
    try {
      JsBarcode(svgRef.current, valor || VALOR_BARRA_EXEMPLO, {
        format: 'EAN13',
        width: 2,
        height: exibirTexto ? Math.max(alturaPx - 14, 10) : alturaPx,
        displayValue: exibirTexto,
        fontSize: 10,
        margin: 0,
      })
    } catch (e) {
      // Valor não desenhável (ex.: vazio) — deixa o SVG em branco em vez de derrubar a tela.
      console.warn('Não foi possível desenhar o código de barras de exemplo:', e)
    }
  }, [valor, larguraPx, alturaPx, exibirTexto])

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
  ...resto
}: {
  campo: CampoEtiquetaPosicionado
  escalaPxPorMm: number
  produtoExemplo: ProdutoExemplo | null
  nomeEmpresaExemplo: string
  style?: React.CSSProperties
} & React.HTMLAttributes<HTMLDivElement>) {
  const larguraPx = (campo.larguraMm ?? 10) * escalaPxPorMm
  const alturaPx = (campo.alturaMm ?? 6) * escalaPxPorMm

  const estiloBase: React.CSSProperties = {
    position: 'absolute',
    left: campo.posicaoXMm * escalaPxPorMm,
    top: campo.posicaoYMm * escalaPxPorMm,
    width: larguraPx,
    height: alturaPx,
    fontFamily: CSS_FONTE_ETIQUETA[campo.fonte],
    fontWeight: campo.negrito ? 700 : 400,
    fontSize: campo.tamanhoFontePt ? `${campo.tamanhoFontePt}pt` : '7pt',
    textAlign: CSS_ALINHAMENTO_ETIQUETA[campo.alinhamento],
    color: campo.fundoPreto ? '#fff' : '#000',
    background: campo.fundoPreto ? '#000' : 'transparent',
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

  return (
    <div style={estiloBase} {...resto}>
      {ehCampoDeBarras(campo.campo) ? (
        <CodigoDeBarras
          valor={valorDeBarrasDoCampo(campo, produtoExemplo)}
          larguraPx={larguraPx}
          alturaPx={alturaPx}
          exibirTexto={Boolean(campo.exibirTextoLegivel)}
        />
      ) : (
        valorTextoDoCampo(campo, produtoExemplo, nomeEmpresaExemplo)
      )}
    </div>
  )
}
