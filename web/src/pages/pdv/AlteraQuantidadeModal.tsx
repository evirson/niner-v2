import CabecalhoModal from '../../components/CabecalhoModal'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { buscarPermiteQtdDecimal } from '../../lib/configuracaoGeral'
import { formatarQuantidade } from '../../lib/masks'
import type { ItemLedger } from '../../lib/pdv'
import { fecharAoClicarNoFundo } from '../../lib/modais'

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
  aoAlterarQtd: (idLinha: number, novaQtd: number) => void
  aoRemover: (idLinha: number) => void
}) {
  const [confirmandoRemocao, setConfirmandoRemocao] = useState<number | null>(null)
  const { data: cfgQtdDecimal } = useQuery({ queryKey: ['permite-qtd-decimal'], queryFn: buscarPermiteQtdDecimal })
  const permiteQtdDecimal = cfgQtdDecimal?.cfgPermiteQtdDecimal ?? true

  const diminuir = (item: ItemLedger) => {
    if (item.qtd <= 1) {
      setConfirmandoRemocao(item.idLinha)
      return
    }
    aoAlterarQtd(item.idLinha, item.qtd - 1)
  }

  const confirmarRemocao = (idLinha: number) => {
    aoRemover(idLinha)
    setConfirmandoRemocao(null)
  }

  return (
    <div className="modal-overlay" onClick={fecharAoClicarNoFundo(aoFechar)}>
      <div className="modal modal-medio" role="dialog" aria-label="Altera quantidade" onClick={(e) => e.stopPropagation()}>
        <CabecalhoModal titulo="Altera Quantidade" aoFechar={aoFechar} />
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
                  <tr key={item.idLinha}>
                    <td>
                      {item.descricao}
                      {item.variacao && <span className="muted"> — {item.variacao}</span>}
                    </td>
                    <td>
                      {confirmandoRemocao === item.idLinha ? (
                        <div className="pdv-confirma-remocao">
                          Remover item?
                          <button type="button" className="btn ghost" onClick={() => setConfirmandoRemocao(null)}>
                            Cancelar
                          </button>
                          <button type="button" className="btn" onClick={() => confirmarRemocao(item.idLinha)}>
                            Remover
                          </button>
                        </div>
                      ) : (
                        <div className="pdv-stepper">
                          <button type="button" aria-label="Diminuir quantidade" onClick={() => diminuir(item)}>
                            −
                          </button>
                          <span className="pdv-qtd-atual">{formatarQuantidade(item.qtd, permiteQtdDecimal)}</span>
                          <button
                            type="button"
                            aria-label="Aumentar quantidade"
                            onClick={() => aoAlterarQtd(item.idLinha, item.qtd + 1)}
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

        </div>
      </div>
    </div>
  )
}
