import { useState } from 'react'
import type { ItemLedger } from '../../lib/pdv'

/**
 * F3 — Altera Quantidade (2026-07-27): lista os itens já lançados na venda com um stepper de
 * quantidade por linha. Chegar a zero **nunca** remove direto — pede confirmação inline antes
 * (mesma convenção do resto do sistema: nenhuma exclusão sem avisar).
 */
export default function AlteraQuantidadeModal({
  itens,
  aoFechar,
  aoAlterarQtd,
  aoRemover,
}: {
  itens: ItemLedger[]
  aoFechar: () => void
  aoAlterarQtd: (codigo: string, novaQtd: number) => void
  aoRemover: (codigo: string) => void
}) {
  const [confirmandoRemocao, setConfirmandoRemocao] = useState<string | null>(null)

  const diminuir = (item: ItemLedger) => {
    if (item.qtd <= 1) {
      setConfirmandoRemocao(item.codigo)
      return
    }
    aoAlterarQtd(item.codigo, item.qtd - 1)
  }

  const confirmarRemocao = (codigo: string) => {
    aoRemover(codigo)
    setConfirmandoRemocao(null)
  }

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal modal-medio" role="dialog" aria-label="Altera quantidade" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Altera Quantidade</h2>
        <p className="muted" style={{ marginTop: 4 }}>
          Use os botões pra ajustar a quantidade de cada item da venda.
        </p>

        {itens.length === 0 ? (
          <p className="muted" style={{ margin: '16px 0' }}>
            Nenhum item na venda.
          </p>
        ) : (
          <div className="table-wrap" style={{ marginTop: 12, maxHeight: 360 }}>
            <table className="table table-compacta">
              <thead>
                <tr>
                  <th>Descrição</th>
                  <th style={{ textAlign: 'center', width: 260 }}>Quantidade</th>
                </tr>
              </thead>
              <tbody>
                {itens.map((item) => (
                  <tr key={item.codigo}>
                    <td>
                      {item.descricao}
                      {item.variacao && <span className="muted"> — {item.variacao}</span>}
                    </td>
                    <td>
                      {confirmandoRemocao === item.codigo ? (
                        <div className="pdv-confirma-remocao">
                          Remover item?
                          <button type="button" className="btn ghost" onClick={() => setConfirmandoRemocao(null)}>
                            Cancelar
                          </button>
                          <button type="button" className="btn" onClick={() => confirmarRemocao(item.codigo)}>
                            Remover
                          </button>
                        </div>
                      ) : (
                        <div className="pdv-stepper">
                          <button type="button" aria-label="Diminuir quantidade" onClick={() => diminuir(item)}>
                            −
                          </button>
                          <span className="pdv-qtd-atual">{item.qtd}</span>
                          <button
                            type="button"
                            aria-label="Aumentar quantidade"
                            onClick={() => aoAlterarQtd(item.codigo, item.qtd + 1)}
                          >
                            +
                          </button>
                        </div>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <div className="ajuda-rodape">
          <button type="button" className="btn ghost" onClick={aoFechar}>
            Fechar
          </button>
        </div>
      </div>
    </div>
  )
}
