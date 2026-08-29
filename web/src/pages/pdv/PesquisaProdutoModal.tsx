import CabecalhoModal from '../../components/CabecalhoModal'
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
    queryFn: () => buscarProdutosPdv({ busca }),
  })
  const { data: cfgQtdDecimal } = useQuery({ queryKey: ['permite-qtd-decimal'], queryFn: buscarPermiteQtdDecimal })
  const permiteQtdDecimal = cfgQtdDecimal?.cfgPermiteQtdDecimal ?? true

  const empresas = resultados && resultados.length > 0 ? resultados[0].estoquePorEmpresa.map((e) => e.nomeEmpresa) : []

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal modal-largo" role="dialog" aria-label="Pesquisa de produto" onClick={(e) => e.stopPropagation()}>
        <CabecalhoModal titulo="Pesquisa de Produto" aoFechar={aoFechar} />
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
                    {/* ⚠️ Serviço mostra "—", nunca "0" (S1): serviço não tem saldo, e um zero
                        aqui lê como "acabou" — o operador deixaria de vender o banho e tosa
                        achando que faltou alguma coisa. */}
                    {produto.estoquePorEmpresa.map((e) => (
                      <td key={e.codigoEmpresa} className="mono" style={{ textAlign: 'right' }}>
                        {produto.tipoItem === 'SERVICO' ? '—' : formatarQuantidade(e.qtd, permiteQtdDecimal)}
                      </td>
                    ))}
                    <td className="mono" style={{ textAlign: 'right' }}>
                      <strong>
                        {produto.tipoItem === 'SERVICO'
                          ? '—'
                          : formatarQuantidade(produto.estoqueTotal, permiteQtdDecimal)}
                      </strong>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
        {/* ⚠️ A busca corta em 20 no servidor e a tela não dizia nada (achado de auditoria,
            2026-08-21). Quem tinha 20+ resultados parecidos não via o seu, concluía "não está
            cadastrado" — e o botão de criar está ao lado, sem checagem de duplicidade. O resultado
            era cadastro duplicado com o mesmo documento, partindo histórico e financeiro em dois. */}
        {resultados && resultados.length === 20 && (
          <p className="muted" style={{ marginTop: 6 }}>
            Mostrando os primeiros {20} — refine a busca para ver mais.
          </p>
        )}

        <div className="ajuda-rodape">

        </div>
      </div>
    </div>
  )
}
