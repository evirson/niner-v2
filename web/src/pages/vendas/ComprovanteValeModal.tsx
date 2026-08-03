import { gerarPdfComprovanteVale, montarLinhasComprovanteVale } from '../../lib/comprovante'
import type { DevolucaoEfetivada } from '../../lib/devolucaoProduto'

/**
 * Comprovante do vale-mercadoria gerado por uma devolução, formatado pra bobina térmica de
 * 80mm — mesmo padrão do Comprovante de Recebimento de Crediário (2026-08-03). Abre
 * automaticamente logo após gravar a devolução com sucesso (`DevolucaoProduto.tsx`). Recebe a
 * resposta da efetivação direto via props (já tem tudo — sem precisar de uma segunda consulta).
 */
export default function ComprovanteValeModal({
  devolucao,
  nomeEmpresa,
  aoFechar,
}: {
  devolucao: DevolucaoEfetivada
  nomeEmpresa: string
  aoFechar: () => void
}) {
  const linhas = montarLinhasComprovanteVale(devolucao, nomeEmpresa)

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal modal-medio" role="dialog" aria-label="Vale-mercadoria" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Vale-Mercadoria Gerado</h2>

        <pre className="comprovante-preview comprovante-imprimir">{linhas.join('\n')}</pre>

        <div className="ajuda-rodape">
          <button type="button" className="btn ghost" onClick={aoFechar}>
            Fechar
          </button>
          <div style={{ display: 'flex', gap: 8 }}>
            <button type="button" className="btn ghost" onClick={() => gerarPdfComprovanteVale(linhas, devolucao.idDevolucao)}>
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
