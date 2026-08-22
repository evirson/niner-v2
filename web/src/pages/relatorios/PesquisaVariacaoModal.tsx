import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { buscarVariacoesMovimentacao, type VariacaoEncontrada } from '../../lib/relatorioMovimentacaoProdutos'

const LIMITE_BUSCA = 20 // igual ao do servidor — ver o javadoc de LIMITE_BUSCA no service

function rotuloVariacao(v: VariacaoEncontrada): string | null {
  if (!v.variacaoCor && !v.variacaoTamanho) return null
  return [v.variacaoCor, v.variacaoTamanho].filter(Boolean).join(' · ')
}

/**
 * Busca de produto/variação pro modelo Kardex do Relatório de Movimentação de Produtos
 * (2026-08-04) — mesmo padrão de `PesquisaVendedorModal.tsx` do PDV (busca por texto + lista,
 * clique ou Enter/Espaço com foco na linha seleciona).
 */
export default function PesquisaVariacaoModal({
  aoFechar,
  aoSelecionar,
}: {
  aoFechar: () => void
  aoSelecionar: (variacao: VariacaoEncontrada) => void
}) {
  const [busca, setBusca] = useState('')

  const { data: resultados, isLoading, isError } = useQuery({
    queryKey: ['movimentacao-produtos-variacoes', busca],
    queryFn: () => buscarVariacoesMovimentacao(busca),
  })

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal modal-largo" role="dialog" aria-label="Pesquisa de produto" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Pesquisa de Produto</h2>
        <p className="muted" style={{ marginTop: 4 }}>
          Digite a descrição ou o código (SKU) do produto e clique numa linha (ou Enter/Espaço com o foco nela) pra selecionar.
        </p>

        <label htmlFor="mov-busca-variacao">Descrição ou SKU</label>
        <input
          id="mov-busca-variacao"
          autoFocus
          placeholder="Ex.: CAMISA…"
          value={busca}
          onChange={(e) => setBusca(e.target.value.toUpperCase())}
        />

        <div className="table-wrap" style={{ marginTop: 16, maxHeight: 360 }}>
          <table className="table table-compacta">
            <thead>
              <tr>
                <th>SKU</th>
                <th>Produto</th>
                <th>Marca</th>
                <th>Variação</th>
              </tr>
            </thead>
            <tbody>
              {isLoading ? (
                <tr>
                  <td colSpan={4}>
                    <p className="muted" style={{ margin: '8px 0' }}>
                      Buscando…
                    </p>
                  </td>
                </tr>
              ) : isError ? (
                <tr>
                  <td colSpan={4}>
                    <p className="erro" style={{ margin: '8px 0' }}>
                      Não foi possível buscar produtos.
                    </p>
                  </td>
                </tr>
              ) : (resultados ?? []).length === 0 ? (
                <tr>
                  <td colSpan={4}>
                    <p className="muted" style={{ margin: '8px 0' }}>
                      Nenhum produto encontrado.
                    </p>
                  </td>
                </tr>
              ) : (
                (resultados ?? []).map((v) => (
                  <tr
                    key={v.idVariacao}
                    tabIndex={0}
                    onClick={() => aoSelecionar(v)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault()
                        aoSelecionar(v)
                      }
                    }}
                  >
                    <td className="mono">{v.sku}</td>
                    <td>{v.descricaoProduto}</td>
                    <td>{v.marca ?? '—'}</td>
                    <td>{rotuloVariacao(v) ?? '—'}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
        {/* A busca corta no servidor e a tela não dizia nada (auditoria 2026-08-21 item 33,
            estendido em 2026-08-22 às telas que a auditoria não tinha listado). */}
        {resultados && resultados.length === LIMITE_BUSCA && (
          <p className="muted" style={{ marginTop: 6 }}>
            Mostrando os primeiros {LIMITE_BUSCA} — refine a busca para ver mais.
          </p>
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
