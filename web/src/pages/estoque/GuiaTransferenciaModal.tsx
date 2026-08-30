import CabecalhoModal from '../../components/CabecalhoModal'
import PortalDeImpressao from '../../components/PortalDeImpressao'
import type { Transferencia } from '../../lib/transferencias'
import { gerarPdfGuiaTransferencia, montarLinhasGuiaTransferencia } from '../../lib/transferenciaImpressao'
import { imprimirDocumentoA4 } from '../../lib/impressaoDocumento'
import { fecharAoClicarNoFundo } from '../../lib/modais'

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
    <div className="modal-overlay" onClick={fecharAoClicarNoFundo(aoFechar)}>
      <div className="modal modal-largo" role="dialog" aria-label="Pré-visualização da guia de transferência" onClick={(e) => e.stopPropagation()}>
        <CabecalhoModal titulo="Pré-visualização — Guia de Transferência" aoFechar={aoFechar} />

        <pre className="guia-transferencia-preview">{linhas.join('\n')}</pre>
        {/* ⛔ A via impressa vai por PORTAL, fora do `#root` — sem isso a folha sai EM BRANCO
            (o isolamento é `body > *` e apaga o `#root` inteiro). Ver PortalDeImpressao. */}
        <PortalDeImpressao>
          <pre className="guia-transferencia-imprimir documento-a4-imprimir">{linhas.join('\n')}</pre>
        </PortalDeImpressao>

        <div className="ajuda-rodape">

          <div style={{ display: 'flex', gap: 8 }}>
            <button
              type="button"
              className="btn ghost"
              onClick={() => gerarPdfGuiaTransferencia(linhas, transferencia.idTransferencia)}
            >
              Salvar PDF
            </button>
            <button type="button" className="btn" onClick={imprimirDocumentoA4}>
              Imprimir
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
