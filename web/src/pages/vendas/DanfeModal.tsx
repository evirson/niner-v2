import CabecalhoModal from '../../components/CabecalhoModal'
import { useQuery } from '@tanstack/react-query'
import { useRef } from 'react'
import { ApiError } from '../../lib/api'
import { buscarDanfe } from '../../lib/danfe55'
import DanfeImprimir from './DanfeImprimir'
import { imprimirDocumentoA4 } from '../../lib/impressaoDocumento'

/**
 * Popup do DANFE modelo 55 (A4) — §10.2, bloco B9. Abre a partir da nota de devolução (do popup
 * do vale-mercadoria) e da tela de Documentos Fiscais.
 *
 * <p>Mesmo esqueleto dos outros popups de documento do sistema (cabeçalho e rodapé fixos, só o
 * documento rola), mas o miolo é uma folha A4 inteira em vez de um cupom de 80mm — por isso é o
 * modal mais largo do projeto. "Imprimir" usa o diálogo nativo (que também oferece "Salvar como
 * PDF"), com a calibragem de página em `.danfe-imprimir` (`styles.css`).
 */
export default function DanfeModal({
  idDocumentoFiscal,
  aoFechar,
}: {
  idDocumentoFiscal: number
  aoFechar: () => void
}) {
  const danfeRef = useRef<HTMLDivElement>(null)
  const { data: danfe, isLoading, error } = useQuery({
    queryKey: ['danfe', idDocumentoFiscal],
    queryFn: () => buscarDanfe(idDocumentoFiscal),
    retry: 1,
  })

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div
        className="modal"
        role="dialog"
        aria-label="DANFE da nota fiscal"
        onClick={(e) => e.stopPropagation()}
        // ⚠️ `width` explícito, não só `maxWidth`: a classe `.modal` já define uma largura própria
        // (menor), e `maxWidth` sozinho apenas limita — não alarga. A folha A4 tem 210mm ≈ 794px e
        // precisa dos 24px de padding de cada lado, daí os 850.
        style={{
          display: 'flex', flexDirection: 'column', overflow: 'hidden',
          width: 'min(96vw, 850px)', maxWidth: 'min(96vw, 850px)', maxHeight: '92vh',
        }}
      >
        <CabecalhoModal titulo="DANFE — Nota Fiscal Eletrônica" aoFechar={aoFechar} />

        <div style={{ overflow: 'auto', flex: 1, minHeight: 0, marginTop: 12 }}>
          {isLoading ? (
            <p className="muted">Carregando…</p>
          ) : error || !danfe ? (
            <p className="erro">
              {error instanceof ApiError ? error.message : 'Não foi possível carregar o DANFE.'}
            </p>
          ) : (
            // 210mm ≈ 794px, que cabe inteiro neste modal — a folha aparece em tamanho real, sem
            // `transform: scale`. ⚠️ Escalar aqui seria pior que inútil: `transform` não encolhe a
            // caixa de layout, então o wrapper continuava com a largura original e o modal ganhava
            // uma barra de rolagem horizontal para um conteúdo que já cabia.
            <DanfeImprimir ref={danfeRef} danfe={danfe} />
          )}
        </div>

        <div className="ajuda-rodape" style={{ flexShrink: 0 }}>

          <button type="button" className="btn" disabled={!danfe} onClick={imprimirDocumentoA4}>
            Imprimir
          </button>
        </div>
      </div>
    </div>
  )
}
