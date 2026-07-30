import type { FechamentoCaixa } from '../../lib/caixa'
import { gerarPdfFechamento, montarLinhasFechamento } from '../../lib/fechamentoCaixaImpressao'

/**
 * Pré-visualização do relatório de Fechamento de Caixa antes de imprimir/salvar em PDF
 * (2026-07-30, pedido explícito: "primeiro visualizar, depois a opção de imprimir ou salvar em
 * pdf"). Impressão em folha A4 — diferente do Comprovante de Pagamento de Crediário (80mm, ver
 * `ComprovanteRecebimentoModal.tsx`), por isso usa uma classe/`@page` nomeada própria
 * (`.fechamento-imprimir`, `styles.css`) em vez de reaproveitar `.comprovante-imprimir`.
 */
export default function FechamentoCaixaPreviewModal({
  fechamento,
  linhaDinheiro,
  valorContadoDinheiro,
  aoFechar,
}: {
  fechamento: FechamentoCaixa
  linhaDinheiro: { nomeCarteira: string; valorEsperado: number } | null
  valorContadoDinheiro: number | null
  aoFechar: () => void
}) {
  const linhas = montarLinhasFechamento(fechamento, valorContadoDinheiro, linhaDinheiro)

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal modal-largo" role="dialog" aria-label="Pré-visualização do fechamento de caixa" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Pré-visualização — Fechamento de Caixa</h2>

        <pre className="fechamento-preview fechamento-imprimir">{linhas.join('\n')}</pre>

        <div className="ajuda-rodape">
          <button type="button" className="btn ghost" onClick={aoFechar}>
            Fechar
          </button>
          <div style={{ display: 'flex', gap: 8 }}>
            <button type="button" className="btn ghost" onClick={() => gerarPdfFechamento(linhas, fechamento.idCaixa)}>
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
