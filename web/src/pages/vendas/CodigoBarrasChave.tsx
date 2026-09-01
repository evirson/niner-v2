import { useEffect, useRef } from 'react'
import JsBarcode from 'jsbarcode'

/**
 * Código de barras da CHAVE DE ACESSO, no quadro do DANFE (2026-09-01).
 *
 * <p>É exigência do leiaute — o Manual de Orientação do Contribuinte manda a chave em
 * **CODE-128C** logo acima dos 44 dígitos legíveis. Ele existe para o fiscal na estrada conferir a
 * nota com um leitor em vez de digitar 44 números; sem ele, o DANFE é um formulário incompleto.
 * O Nainer imprimia só os dígitos até hoje — foi o quarto relato do dono do produto.
 *
 * <h2>⚠️ CODE-128C, não CODE-128 genérico</h2>
 * O "C" é o subconjunto que codifica **pares de dígitos** num único símbolo: 44 dígitos viram 22
 * caracteres, e é isso que faz a barra caber na largura do quadro. O CODE-128 automático do
 * jsbarcode escolheria o subconjunto sozinho e poderia sair mais largo que a caixa.
 *
 * <h2>⚠️ Zona de silêncio</h2>
 * O branco antes e depois do símbolo **não é margem estética**: é por ele que o leitor sabe onde o
 * código começa e termina, e sem ele um código impresso com precisão perfeita simplesmente não lê
 * (aprendido na etiqueta, em 2026-08-21). O CODE-128 pede **10 módulos** de cada lado; `margin`
 * cuida disso e por isso não vai a zero.
 *
 * <h2>⛔ Sem `shape-rendering: crispEdges`</h2>
 * Ele arredonda cada borda para a grade de pixels e engrossa umas barras comendo os espaços
 * vizinhos — testado e revertido na etiqueta no mesmo dia, com impressão provando.
 */
export default function CodigoBarrasChave({ chave }: { chave: string }) {
  const svgRef = useRef<SVGSVGElement>(null)

  useEffect(() => {
    const digitos = (chave ?? '').replace(/\D/g, '')
    // CODE-128C exige quantidade PAR de dígitos (ele codifica de dois em dois). A chave da NF-e
    // tem 44 — par —, mas uma chave truncada por dado ruim faria o jsbarcode lançar e derrubar a
    // tela inteira, que é o defeito de "exceção em efeito vira tela preta".
    if (!svgRef.current || digitos.length !== 44) return
    try {
      JsBarcode(svgRef.current, digitos, {
        format: 'CODE128C',
        displayValue: false, // os 44 dígitos já aparecem embaixo, no texto do quadro
        height: 40,
        width: 1,
        margin: 0,
        marginLeft: 10,
        marginRight: 10,
        background: 'transparent',
      })
    } catch {
      // Chave que o gerador recusa não pode apagar o DANFE: o documento continua válido pelos
      // dígitos impressos, que é como ele era até hoje.
    }
  }, [chave])

  return <svg ref={svgRef} className="danfe-chave-barras" role="img" aria-label="Código de barras da chave de acesso" />
}
