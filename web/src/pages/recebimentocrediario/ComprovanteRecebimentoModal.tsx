import { useQuery } from '@tanstack/react-query'
import { gerarPdfComprovante, montarLinhasComprovante } from '../../lib/comprovante'
import { ApiError } from '../../lib/api'
import { buscarComprovanteRecebimento } from '../../lib/recebimentoCrediario'

/**
 * Comprovante de pagamento de crediário, formatado pra bobina térmica de 80mm (2026-07-30).
 * Abre automaticamente logo após um recebimento efetivado com sucesso (`RecebimentoCrediario.
 * tsx`). "Imprimir" usa o diálogo nativo do navegador (só o `.comprovante-imprimir` fica visível
 * na impressão, via CSS — ver `styles.css`); "Salvar PDF" gera o arquivo direto com `jsPDF`, sem
 * passar pelo diálogo (pedido explícito, dois botões separados).
 */
export default function ComprovanteRecebimentoModal({
  idLoteRecebimento,
  aoFechar,
}: {
  idLoteRecebimento: number
  aoFechar: () => void
}) {
  const {
    data: comprovante,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['comprovante-recebimento', idLoteRecebimento],
    queryFn: () => buscarComprovanteRecebimento(idLoteRecebimento),
    retry: 1,
  })

  const linhas = comprovante ? montarLinhasComprovante(comprovante) : []

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal modal-medio" role="dialog" aria-label="Comprovante de pagamento" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Comprovante de Pagamento</h2>

        {isLoading ? (
          <p className="muted">Carregando…</p>
        ) : error ? (
          <p className="erro">{error instanceof ApiError ? error.message : 'Não foi possível carregar o comprovante.'}</p>
        ) : (
          <pre className="comprovante-preview comprovante-imprimir">{linhas.join('\n')}</pre>
        )}

        <div className="ajuda-rodape">
          <button type="button" className="btn ghost" onClick={aoFechar}>
            Fechar
          </button>
          <div style={{ display: 'flex', gap: 8 }}>
            <button
              type="button"
              className="btn ghost"
              disabled={!comprovante}
              onClick={() => comprovante && gerarPdfComprovante(linhas, comprovante.idLoteRecebimento)}
            >
              Salvar PDF
            </button>
            <button type="button" className="btn" disabled={!comprovante} onClick={() => window.print()}>
              Imprimir
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
