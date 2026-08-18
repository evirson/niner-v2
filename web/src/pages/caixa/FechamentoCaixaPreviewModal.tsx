import { IconeFechar } from '../../components/Icones'
import type { FechamentoCaixa } from '../../lib/caixa'
import { montarLinhasFechamento } from '../../lib/fechamentoCaixaImpressao'

/**
 * Impressão do Fechamento de Caixa (2026-08-19) — bobina térmica 80mm/42 colunas, mesmo formato e
 * dimensões da papeleta de venda (pedido explícito do dono do produto), reaproveitando as mesmas
 * classes de impressão (`.papeleta-preview`/`.papeleta-imprimir`, `styles.css`). Abre sozinha assim
 * que o caixa fecha com sucesso. "Fechar" virou o "✕" no cabeçalho, mesmo padrão do Comprovante de
 * Pagamento de Crediário/Papeleta de Venda; sem "Salvar PDF" — o diálogo de impressão nativo já
 * oferece "Salvar como PDF".
 */
export default function FechamentoCaixaPreviewModal({
  fechamento,
  aoFechar,
}: {
  fechamento: FechamentoCaixa
  aoFechar: () => void
}) {
  const linhas = montarLinhasFechamento(fechamento)

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal modal-medio" role="dialog" aria-label="Impressão do Fechamento de Caixa" onClick={(e) => e.stopPropagation()}>
        <div className="lightbox-topo">
          <h2 style={{ margin: 0 }}>Fechamento de Caixa</h2>
          <button type="button" className="btn ghost btn-fechar-tela" onClick={aoFechar} aria-label="Fechar" title="Fechar">
            <IconeFechar />
          </button>
        </div>

        <pre className="papeleta-preview papeleta-imprimir">{linhas.join('\n')}</pre>

        <div className="ajuda-rodape">
          <span />
          <button type="button" className="btn" onClick={() => window.print()}>
            Imprimir
          </button>
        </div>
      </div>
    </div>
  )
}
