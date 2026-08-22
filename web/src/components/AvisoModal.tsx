import { IconeFechar } from './Icones'

/**
 * Popup de aviso/erro centralizado na tela, com título fixo + botão X (mesmo padrão de
 * `.lightbox-topo`/`.btn-fechar-tela` já usado em `CancelamentoVendaModal.tsx`/
 * `DetalheVendaModal.tsx`). Diferente do `Toast.tsx` (canto superior direito, some sozinho em
 * 6s): usado quando a mensagem é importante o bastante pra exigir leitura/confirmação explícita
 * do operador antes de seguir — ex.: uma venda rejeitada por regra de negócio (limite de
 * crédito, saldo não fecha) no meio do fluxo de pagamento do PDV — em vez de um aviso rápido que
 * pode passar despercebido.
 */
export default function AvisoModal({
  titulo = 'Não foi possível continuar',
  mensagem,
  aoFechar,
}: {
  titulo?: string
  mensagem: string
  aoFechar: () => void
}) {
  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div
        className="modal modal-medio"
        role="alertdialog"
        aria-label={titulo}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="lightbox-topo" style={{ marginBottom: 12 }}>
          <h2 style={{ margin: 0 }}>{titulo}</h2>
          <button type="button" className="btn ghost btn-fechar-tela" onClick={aoFechar} aria-label="Fechar" title="Fechar">
            <IconeFechar />
          </button>
        </div>
        {/* ⚠️ `pre-line` (auditoria 2026-08-21, item 10): mensagem de negócio do servidor costuma
            vir em mais de uma linha — o motivo e depois o que fazer. Sem isto, tudo colapsa numa
            linha só e a instrução some no meio do parágrafo. */}
        <p className="erro" style={{ margin: 0, whiteSpace: 'pre-line' }}>{mensagem}</p>
      </div>
    </div>
  )
}
