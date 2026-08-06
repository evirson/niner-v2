import { useQuery } from '@tanstack/react-query'
import { gerarPdfComprovanteVenda, montarLinhasComprovanteVenda } from '../../lib/comprovante'
import { ApiError } from '../../lib/api'
import { buscarComprovanteVenda } from '../../lib/pdv'

/**
 * Papeleta de venda, formatada pra bobina térmica de 80mm (2026-08-06). Abre automaticamente
 * logo após o F5 efetivar a venda com sucesso (`Pdv.tsx`). Mesmo mecanismo do Comprovante de
 * Pagamento de Crediário: "Imprimir" usa o diálogo nativo do navegador (só `.papeleta-imprimir`
 * fica visível na impressão, via CSS — ver `styles.css`); "Salvar PDF" gera o arquivo direto com
 * `jsPDF`, sem passar pelo diálogo.
 */
export default function ComprovantePapeletaModal({ idVenda, aoFechar }: { idVenda: number; aoFechar: () => void }) {
  const {
    data: comprovante,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['comprovante-venda', idVenda],
    queryFn: () => buscarComprovanteVenda(idVenda),
    retry: 1,
  })

  const linhas = comprovante ? montarLinhasComprovanteVenda(comprovante) : []

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal modal-medio" role="dialog" aria-label="Papeleta de venda" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Papeleta de Venda</h2>

        {isLoading ? (
          <p className="muted">Carregando…</p>
        ) : error ? (
          <p className="erro">{error instanceof ApiError ? error.message : 'Não foi possível carregar a papeleta.'}</p>
        ) : (
          <pre className="papeleta-preview papeleta-imprimir">{linhas.join('\n')}</pre>
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
              onClick={() => comprovante && gerarPdfComprovanteVenda(linhas, comprovante.idVenda)}
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
