import JsBarcode from 'jsbarcode'
import { useEffect, useRef } from 'react'
import {
  CAMPOS_DE_BARRAS,
  CSS_ALINHAMENTO_ETIQUETA,
  CSS_FONTE_ETIQUETA,
  type CampoEtiquetaPosicionado,
  type ProdutoExemplo,
} from '../../lib/etiquetaConfig'
import { formatarMoeda } from '../../lib/masks'

/** Valor de mentirinha, formato válido de EAN-13, só pra o código de barras ter uma cara real
 * quando nenhum produto de exemplo foi escolhido — layout, não impressão de verdade. */
const VALOR_BARRA_EXEMPLO = '9000000000017'

function ehCampoDeBarras(campo: CampoEtiquetaPosicionado['campo']): boolean {
  return (CAMPOS_DE_BARRAS as string[]).includes(campo)
}

function valorTextoDoCampo(campo: CampoEtiquetaPosicionado, produto: ProdutoExemplo | null, nomeEmpresa: string): string {
  switch (campo.campo) {
    case 'NOME_EMPRESA':
      return nomeEmpresa
    case 'DESCRICAO_PRODUTO':
      return produto?.descricao ?? 'Descrição do Produto'
    case 'MARCA':
      return produto?.marca ?? 'Marca'
    case 'REFERENCIA':
      return produto?.referencia ?? 'REF: 000000'
    case 'PRECO_VENDA':
      return `R$ ${formatarMoeda(produto?.precoVenda ?? 99.9)}`
    case 'PRECO_OFERTA':
      return `R$ ${formatarMoeda(produto?.precoOferta ?? produto?.precoVenda ?? 79.9)}`
    case 'VARIANTE_LINHA':
      return produto?.variacaoLinha ?? 'Cor'
    case 'VARIANTE_COLUNA':
      return produto?.variacaoColuna ?? 'Tam'
    default:
      return ''
  }
}

function valorDeBarrasDoCampo(campo: CampoEtiquetaPosicionado, produto: ProdutoExemplo | null): string {
  if (campo.campo === 'SKU_BARRAS') return produto?.sku ?? VALOR_BARRA_EXEMPLO
  return produto?.ean ?? VALOR_BARRA_EXEMPLO
}

/** Renderiza o código de barras de verdade (SVG, `jsbarcode`) — CODE128 sempre, mesmo pra
 * SKU/EAN (que são EAN-13): CODE128 aceita qualquer string sem validar dígito verificador, então
 * nunca quebra o editor com um valor incompleto/inválido — aqui o que importa é o layout, não a
 * simbologia exata de impressão (isso é responsabilidade da futura tela de Emissão). */
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
      JsBarcode(svgRef.current, valor || '0', {
        format: 'CODE128',
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

  return <svg ref={svgRef} width={larguraPx} height={alturaPx} style={{ display: 'block' }} />
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
    whiteSpace: 'nowrap',
    textOverflow: 'ellipsis',
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
