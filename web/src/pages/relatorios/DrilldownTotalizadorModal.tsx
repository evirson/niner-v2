import { useQuery } from '@tanstack/react-query'
import { IconeFechar } from '../../components/Icones'
import { ApiError } from '../../lib/api'
import { formatarMoeda } from '../../lib/masks'
import { buscarDetalheTotalizador, type FiltrosRelatorioVendas } from '../../lib/relatorioVendas'
import { fecharAoClicarNoFundo } from '../../lib/modais'

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

function formatarDataHora(iso: string): string {
  return new Date(iso).toLocaleString('pt-BR')
}

/**
 * Drill-down de uma linha da grid totalizada do Relatório de Vendas — reaplica os mesmos
 * filtros do topo mais a chave do grupo clicado, e mostra as vendas daquele grupo no formato
 * analítico (mesmo padrão de popup de DetalheVendaModal.tsx).
 */
export default function DrilldownTotalizadorModal({
  filtros,
  chave,
  nomeGrupo,
  aoFechar,
}: {
  filtros: FiltrosRelatorioVendas
  chave: string
  nomeGrupo: string
  aoFechar: () => void
}) {
  const {
    data,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['relatorio-vendas-drilldown', filtros, chave],
    queryFn: () => buscarDetalheTotalizador(filtros, chave),
  })

  const itens = data?.itens ?? []

  return (
    <div className="modal-overlay" onClick={fecharAoClicarNoFundo(aoFechar)}>
      <div
        className="modal modal-largo"
        role="dialog"
        aria-label={`Vendas de ${nomeGrupo}`}
        onClick={(e) => e.stopPropagation()}
        style={{ display: 'flex', flexDirection: 'column', overflow: 'hidden' }}
      >
        <div className="lightbox-topo" style={{ marginBottom: 12, flexShrink: 0 }}>
          <div className="titulo-tela">
            <h2 style={{ margin: 0 }}>{nomeGrupo}</h2>
          </div>
          <button type="button" className="btn ghost btn-fechar-tela" onClick={aoFechar} aria-label="Fechar" title="Fechar">
            <IconeFechar />
          </button>
        </div>

        <div style={{ overflowY: 'auto', flex: 1, minHeight: 0 }}>
          {isLoading ? (
            <p className="muted">Carregando…</p>
          ) : error ? (
            <p className="erro">{error instanceof ApiError ? error.message : 'Não foi possível carregar o detalhamento.'}</p>
          ) : itens.length === 0 ? (
            <p className="muted">Nenhuma venda neste grupo.</p>
          ) : (
            <div className="table-wrap">
              <table className="table table-compacta">
                <thead>
                  <tr>
                    <th>Empresa</th>
                    <th>Nº Venda</th>
                    <th>Data/Hora</th>
                    <th>Cliente</th>
                    <th>Vendedor</th>
                    <th>Operador</th>
                    <th>Qtd Produtos</th>
                    <th>Valor Venda</th>
                    <th>Acréscimos</th>
                    <th>Descontos</th>
                    <th>Valor Líquido</th>
                  </tr>
                </thead>
                <tbody>
                  {itens.map((i) => (
                    <tr key={i.idVenda}>
                      <td>{i.nomeEmpresa}</td>
                      <td className="mono">{i.idVenda}</td>
                      <td>{formatarDataHora(i.dataHoraVenda)}</td>
                      <td>{i.nomeCliente ?? '—'}</td>
                      <td>{i.nomeVendedor ?? '—'}</td>
                      <td>{i.nomeOperador ?? '—'}</td>
                      <td className="mono">{i.qtdProdutos}</td>
                      <td className="mono">{moeda(i.valorVenda)}</td>
                      <td className="mono">{moeda(i.acrescimos)}</td>
                      <td className="mono">{moeda(i.descontos)}</td>
                      <td className="mono">{moeda(i.valorLiquido)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
