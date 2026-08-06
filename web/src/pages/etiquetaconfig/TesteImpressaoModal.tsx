import { useState, type FormEvent } from 'react'

const QUANTIDADE_MAXIMA = 200

/**
 * Pergunta a quantidade de etiquetas pro Teste de Impressão (2026-08-05, botão na
 * Configuração de Etiqueta) — imprime N cópias do layout configurado, direto no diálogo de
 * impressão do navegador, pra testar na impressora física antes de sair emitindo de verdade.
 */
export default function TesteImpressaoModal({
  aoFechar,
  aoConfirmar,
}: {
  aoFechar: () => void
  aoConfirmar: (quantidade: number) => void
}) {
  const [quantidade, setQuantidade] = useState('10')
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
    aoConfirmar(numero)
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
          />
          {erro && <p className="erro-campo">{erro}</p>}
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
