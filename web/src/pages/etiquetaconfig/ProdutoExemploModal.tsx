import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { buscarProdutosExemplo, type ProdutoExemplo } from '../../lib/etiquetaConfig'
import { formatarMoeda } from '../../lib/masks'

function rotuloVariacao(p: ProdutoExemplo): string | null {
  if (!p.variacaoCor && !p.variacaoTamanho) return null
  return [p.variacaoCor, p.variacaoTamanho].filter(Boolean).join(' · ')
}

/**
 * Busca de produto de exemplo pra pré-visualizar a etiqueta com dado real (2026-08-04) — mesmo
 * padrão visual de `PesquisaVariacaoModal.tsx` do Kardex, mas endpoint/DTO próprios (aquele não
 * tem referência/preço, que o editor de etiqueta precisa).
 */
export default function ProdutoExemploModal({
  aoFechar,
  aoSelecionar,
}: {
  aoFechar: () => void
  aoSelecionar: (produto: ProdutoExemplo) => void
}) {
  const [busca, setBusca] = useState('')

  const { data: resultados, isLoading, isError } = useQuery({
    queryKey: ['etiqueta-config-produtos-exemplo', busca],
    queryFn: () => buscarProdutosExemplo(busca),
  })

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal modal-largo" role="dialog" aria-label="Escolher produto de exemplo" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Escolher Produto de Exemplo</h2>
        <p className="muted" style={{ marginTop: 4 }}>
          Digite a descrição ou o código (SKU) do produto e clique numa linha (ou Enter/Espaço com o foco nela) pra
          pré-visualizar a etiqueta com os dados reais desse produto.
        </p>

        <label htmlFor="etq-busca-produto">Descrição ou SKU</label>
        <input
          id="etq-busca-produto"
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
                <th>Preço</th>
                <th>Variação</th>
              </tr>
            </thead>
            <tbody>
              {isLoading ? (
                <tr>
                  <td colSpan={5}>
                    <p className="muted" style={{ margin: '8px 0' }}>
                      Buscando…
                    </p>
                  </td>
                </tr>
              ) : isError ? (
                <tr>
                  <td colSpan={5}>
                    <p className="erro" style={{ margin: '8px 0' }}>
                      Não foi possível buscar produtos.
                    </p>
                  </td>
                </tr>
              ) : (resultados ?? []).length === 0 ? (
                <tr>
                  <td colSpan={5}>
                    <p className="muted" style={{ margin: '8px 0' }}>
                      Nenhum produto encontrado.
                    </p>
                  </td>
                </tr>
              ) : (
                (resultados ?? []).map((p) => (
                  <tr
                    key={p.idVariacao}
                    tabIndex={0}
                    onClick={() => aoSelecionar(p)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault()
                        aoSelecionar(p)
                      }
                    }}
                  >
                    <td className="mono">{p.sku}</td>
                    <td>{p.descricao}</td>
                    <td>{p.marca ?? '—'}</td>
                    <td>R$ {formatarMoeda(p.precoVenda)}</td>
                    <td>{rotuloVariacao(p) ?? '—'}</td>
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
