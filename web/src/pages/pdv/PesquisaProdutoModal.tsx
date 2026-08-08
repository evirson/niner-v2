import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { buscarPermiteQtdDecimal } from '../../lib/configuracaoGeral'
import { formatarQuantidade } from '../../lib/masks'
import { buscarProdutosPdv, type PdvProduto } from '../../lib/pdv'

/**
 * F2 — Pesquisa Produto (docs/telas/pdv.md): busca por nome via `GET /api/v1/pdv/produtos`
 * (produtos reais, ativos, com estoque por empresa). Ao selecionar uma linha, o item já entra
 * na venda (equivalente a ler o código de barras).
 */
export default function PesquisaProdutoModal({
  aoFechar,
  aoSelecionar,
}: {
  aoFechar: () => void
  aoSelecionar: (produto: PdvProduto) => void
}) {
  const [busca, setBusca] = useState('')

  const { data: resultados, isLoading, isError } = useQuery({
    queryKey: ['pdv-produtos', busca],
    queryFn: () => buscarProdutosPdv(busca),
  })
  const { data: cfgQtdDecimal } = useQuery({ queryKey: ['permite-qtd-decimal'], queryFn: buscarPermiteQtdDecimal })
  const permiteQtdDecimal = cfgQtdDecimal?.cfgPermiteQtdDecimal ?? true

  const empresas = resultados && resultados.length > 0 ? resultados[0].estoquePorEmpresa.map((e) => e.nomeEmpresa) : []

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal modal-largo" role="dialog" aria-label="Pesquisa de produto" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Pesquisa de Produto</h2>
        <p className="muted" style={{ marginTop: 4 }}>
          Digite o nome do produto e clique numa linha (ou Enter/Espaço com o foco nela) pra lançar na venda.
        </p>

        <label htmlFor="pdv-busca-produto">Nome do Produto</label>
        <input
          id="pdv-busca-produto"
          autoFocus
          placeholder="Ex.: ARROZ, TÊNIS…"
          value={busca}
          onChange={(e) => setBusca(e.target.value.toUpperCase())}
        />

        <div className="table-wrap" style={{ marginTop: 16, maxHeight: 360 }}>
          <table className="table table-compacta">
            <thead>
              <tr>
                <th>Descrição</th>
                <th>Cor</th>
                <th>Tamanho</th>
                {empresas.map((nome) => (
                  <th key={nome} style={{ textAlign: 'right' }}>
                    {nome}
                  </th>
                ))}
                <th style={{ textAlign: 'right' }}>Estoque Total</th>
              </tr>
            </thead>
            <tbody>
              {isLoading ? (
                <tr>
                  <td colSpan={4 + empresas.length}>
                    <p className="muted" style={{ margin: '8px 0' }}>
                      Buscando…
                    </p>
                  </td>
                </tr>
              ) : isError ? (
                <tr>
                  <td colSpan={4 + empresas.length}>
                    <p className="erro" style={{ margin: '8px 0' }}>
                      Não foi possível buscar produtos.
                    </p>
                  </td>
                </tr>
              ) : !resultados || resultados.length === 0 ? (
                <tr>
                  <td colSpan={4 + empresas.length}>
                    <p className="muted" style={{ margin: '8px 0' }}>
                      Nenhum produto encontrado.
                    </p>
                  </td>
                </tr>
              ) : (
                resultados.map((produto) => (
                  <tr
                    key={produto.idVariacao}
                    tabIndex={0}
                    onClick={() => aoSelecionar(produto)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault()
                        aoSelecionar(produto)
                      }
                    }}
                  >
                    <td>{produto.descricaoProduto}</td>
                    <td>{produto.variacaoCor ?? '—'}</td>
                    <td>{produto.variacaoTamanho ?? '—'}</td>
                    {produto.estoquePorEmpresa.map((e) => (
                      <td key={e.codigoEmpresa} className="mono" style={{ textAlign: 'right' }}>
                        {formatarQuantidade(e.qtd, permiteQtdDecimal)}
                      </td>
                    ))}
                    <td className="mono" style={{ textAlign: 'right' }}>
                      <strong>{formatarQuantidade(produto.estoqueTotal, permiteQtdDecimal)}</strong>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        <div className="ajuda-rodape">
          <button type="button" className="btn ghost" onClick={aoFechar}>
            Fechar
          </button>
        </div>
      </div>
    </div>
  )
}
