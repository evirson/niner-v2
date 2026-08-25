import CabecalhoModal from '../../components/CabecalhoModal'
import { useQuery } from '@tanstack/react-query'
import { ApiError } from '../../lib/api'
import { listarLancamentosDaCarteira } from '../../lib/caixa'
import { formatarMoeda } from '../../lib/masks'

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

function formatarDataHora(iso: string): string {
  return new Date(iso).toLocaleString('pt-BR')
}

/**
 * Drill-down analítico (2026-07-30) — quando a conferência de uma carteira não bate, o
 * operador clica nela e vê lançamento a lançamento o que compõe o valor esperado (inclusive a
 * linha sintética de abertura de caixa), pra conferir sem sair da tela.
 */
export default function LancamentosCarteiraModal({
  idCaixa,
  idCarteira,
  nomeCarteira,
  aoFechar,
}: {
  idCaixa: number
  idCarteira: number
  nomeCarteira: string
  aoFechar: () => void
}) {
  const { data, isLoading, error } = useQuery({
    queryKey: ['caixa-lancamentos-carteira', idCaixa, idCarteira],
    queryFn: () => listarLancamentosDaCarteira(idCaixa, idCarteira),
    retry: 1,
  })

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal modal-largo" role="dialog" aria-label={`Lançamentos de ${nomeCarteira}`} onClick={(e) => e.stopPropagation()}>
        <CabecalhoModal titulo=<>Lançamentos — {nomeCarteira}</> aoFechar={aoFechar} />

        {isLoading ? (
          <p className="muted">Carregando…</p>
        ) : error || !data ? (
          <p className="erro">{error instanceof ApiError ? error.message : 'Não foi possível carregar os lançamentos.'}</p>
        ) : data.length === 0 ? (
          <p className="muted">Nenhum lançamento nesta carteira.</p>
        ) : (
          <div className="table-wrap" style={{ maxHeight: 420 }}>
            <table className="table table-compacta">
              <thead>
                <tr>
                  <th>Data/Hora</th>
                  <th>Operação</th>
                  <th>Origem</th>
                  <th>C/D</th>
                  <th>Valor</th>
                </tr>
              </thead>
              <tbody>
                {data.map((l, indice) => (
                  <tr key={indice}>
                    <td>{formatarDataHora(l.dataHora)}</td>
                    <td>{l.tipoOperacao}</td>
                    <td>{l.origem}</td>
                    <td className="mono">{l.creditoDebito}</td>
                    <td className="mono">{moeda(l.valor)}</td>
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
