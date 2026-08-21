import { useState, type FormEvent } from 'react'

const QUANTIDADE_MAXIMA = 200

/** Fileiras que o teste imprime quando o modal abre — a quantidade nasce daqui vezes o número de
 *  colunas, para já nascer múltipla. Quatro fileiras mostram deriva de passo sem gastar rolo. */
const FILEIRAS_PADRAO = 4

/**
 * Arredonda a quantidade PARA CIMA até o próximo múltiplo do número de colunas (2026-08-21, tarde,
 * pedido do dono do produto).
 *
 * <p>O motivo é físico, não estético: numa fileira parcial o rolo avança igual: pedir 40 com 3
 * colunas imprime 13 fileiras cheias e uma com uma etiqueta só — e os outros 2 adesivos daquela
 * fileira passam pelo cabeçote em branco e vão para o lixo. Múltiplo do número de colunas nunca
 * desperdiça adesivo.
 *
 * <p>Para cima, nunca para baixo: quem pede 40 quer pelo menos 40.
 */
export function multiploDeColunas(quantidade: number, numeroColunas: number): number {
  if (numeroColunas <= 1) return quantidade
  const paraCima = Math.ceil(quantidade / numeroColunas) * numeroColunas
  // ⚠️ Arredondar para cima pode ESTOURAR o teto e produzir um número que a própria tela recusa:
  // com 3 colunas, 200 vira 201 e o envio responde "máximo de 200". O campo escrevia um valor e
  // depois se recusava a aceitá-lo, deixando o usuário adivinhar que precisava baixar para 198.
  // Passando do teto, desce para o maior múltiplo que cabe.
  if (paraCima > QUANTIDADE_MAXIMA) return Math.floor(QUANTIDADE_MAXIMA / numeroColunas) * numeroColunas
  return paraCima
}

/**
 * Pergunta a quantidade de etiquetas pro Teste de Impressão (2026-08-05, botão na
 * Configuração de Etiqueta) — imprime N cópias do layout configurado, direto no diálogo de
 * impressão do navegador, pra testar na impressora física antes de sair emitindo de verdade.
 */
export default function TesteImpressaoModal({
  numeroColunas,
  aoFechar,
  aoConfirmar,
}: {
  numeroColunas: number
  aoFechar: () => void
  aoConfirmar: (quantidade: number, comRegua: boolean) => void
}) {
  const [quantidade, setQuantidade] = useState(String(Math.max(numeroColunas, 1) * FILEIRAS_PADRAO))
  /** ⚠️ Desligada por padrão e EXCLUSIVA das etiquetas (2026-08-21, tarde). De manhã ela vinha
   *  ligada e imprimia junto, antes da primeira fileira — o que deixou de ser possível quando
   *  cada fileira virou uma página do tamanho do passo do rolo: os 132 mm da régua não cabem numa
   *  página de 3 cm. Também deixou de ser desejável, porque os 132 mm não são múltiplos do passo
   *  e empurravam todas as etiquetas para fora do adesivo, escondendo o defeito que se queria
   *  medir. Calibrar escala e conferir alinhamento viraram duas impressões. */
  const [comRegua, setComRegua] = useState(false)
  const [erro, setErro] = useState('')

  const submeter = (e: FormEvent) => {
    e.preventDefault()
    const numero = Number(quantidade)
    if (!quantidade.trim() || !Number.isInteger(numero) || numero < 1) {
      setErro('Informe uma quantidade inteira maior que zero.')
      return
    }
    if (numero > QUANTIDADE_MAXIMA) {
      setErro(`Máximo de ${QUANTIDADE_MAXIMA} etiquetas por teste.`)
      return
    }
    // Arredonda de novo no envio: o `onBlur` não dispara quando o usuário digita e tecla Enter
    // direto, e é justamente aí que passaria uma fileira incompleta.
    aoConfirmar(multiploDeColunas(numero, numeroColunas), comRegua)
  }

  /** Ajusta o que foi digitado assim que o campo perde o foco, para o usuário VER a quantidade que
   *  vai sair antes de confirmar — mesma convenção do `completar*` dos campos decimais. */
  const ajustarAoSair = () => {
    const numero = Number(quantidade)
    if (!quantidade.trim() || !Number.isInteger(numero) || numero < 1) return
    setQuantidade(String(multiploDeColunas(Math.min(numero, QUANTIDADE_MAXIMA), numeroColunas)))
  }

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal" role="dialog" aria-label="Testar impressão" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Testar Impressão</h2>
        <p className="muted" style={{ marginTop: 4 }}>
          Imprime a quantidade de etiquetas informada no layout configurado (tamanho real), pra testar na
          impressora antes de emitir de verdade.
        </p>
        <form onSubmit={submeter}>
          <label htmlFor="etq-qtd-imprimir">Quantidade de Etiquetas *</label>
          <input
            id="etq-qtd-imprimir"
            autoFocus
            inputMode="numeric"
            value={quantidade}
            onChange={(e) => {
              setQuantidade(e.target.value.replace(/\D/g, ''))
              setErro('')
            }}
            onFocus={(e) => e.target.select()}
            onBlur={ajustarAoSair}
          />
          {erro && <p className="erro-campo">{erro}</p>}
          {numeroColunas > 1 && (
            <p className="muted" style={{ marginTop: 4, marginBottom: 0 }}>
              Ajustada para um múltiplo de {numeroColunas} (o número de colunas do rolo): numa fileira
              incompleta o papel avança igual, e os adesivos que sobram saem em branco.
            </p>
          )}
          <label style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 14 }}>
            <input type="checkbox" checked={comRegua} onChange={(e) => setComRegua(e.target.checked)} />
            <span>Imprimir a régua de calibragem (no lugar das etiquetas)</span>
          </label>
          <p className="muted" style={{ marginTop: 4, marginBottom: 0 }}>
            Imprime duas réguas de 100 mm (deitada e em pé) e mais nada. Meça com uma régua de
            verdade: se não der 100 mm, a impressora está escalando a página e nenhum ajuste de medida
            aqui vai resolver — é escala/margem no diálogo de impressão ou o tamanho de papel do driver.
          </p>
          <div className="ajuda-rodape">
            <button type="button" className="btn ghost" onClick={aoFechar}>
              Cancelar
            </button>
            <button type="submit" className="btn">
              Imprimir
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
