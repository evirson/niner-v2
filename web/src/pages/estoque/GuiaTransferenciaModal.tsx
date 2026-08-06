import type { Transferencia } from '../../lib/transferencias'
import { gerarPdfGuiaTransferencia, montarLinhasGuiaTransferencia } from '../../lib/transferenciaImpressao'

/**
 * Pré-visualização da Guia de Transferência antes de imprimir/salvar em PDF (2026-08-06) — folha
 * A4, mesmo mecanismo do Fechamento de Caixa (`FechamentoCaixaPreviewModal.tsx`): "Imprimir" usa
 * o diálogo nativo do navegador (`.guia-transferencia-imprimir`, `styles.css`); "Salvar PDF" gera
 * o arquivo direto com `jsPDF`, sem passar pelo diálogo.
 */
export default function GuiaTransferenciaModal({
  transferencia,
  permiteQtdDecimal,
  aoFechar,
}: {
  transferencia: Transferencia
  permiteQtdDecimal: boolean
  aoFechar: () => void
}) {
  const linhas = montarLinhasGuiaTransferencia(transferencia, permiteQtdDecimal)

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal modal-largo" role="dialog" aria-label="Pré-visualização da guia de transferência" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Pré-visualização — Guia de Transferência</h2>

        <pre className="guia-transferencia-preview guia-transferencia-imprimir">{linhas.join('\n')}</pre>

        <div className="ajuda-rodape">
          <button type="button" className="btn ghost" onClick={aoFechar}>
            Fechar
          </button>
          <div style={{ display: 'flex', gap: 8 }}>
            <button
              type="button"
              className="btn ghost"
              onClick={() => gerarPdfGuiaTransferencia(linhas, transferencia.idTransferencia)}
            >
              Salvar PDF
            </button>
            <button type="button" className="btn" onClick={() => window.print()}>
              Imprimir
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
