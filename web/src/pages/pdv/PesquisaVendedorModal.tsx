import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { listarFuncionarios, type Funcionario } from '../../lib/funcionarios'

/**
 * F6 — Vendedor da Venda (2026-07-28): busca por nome via `GET /api/v1/funcionarios` (só
 * funcionário ativo, já existente — não precisou de endpoint novo). Vendedor é obrigatório
 * pra confirmar a venda.
 */
export default function PesquisaVendedorModal({
  aoFechar,
  aoSelecionar,
}: {
  aoFechar: () => void
  aoSelecionar: (vendedor: Funcionario) => void
}) {
  const [busca, setBusca] = useState('')

  const { data: pagina, isLoading, isError } = useQuery({
    queryKey: ['pdv-vendedores', busca],
    queryFn: () => listarFuncionarios({ nome: busca, tamanho: 20, status: 'ATIVOS' }),
  })

  const resultados = pagina?.itens ?? []

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal modal-largo" role="dialog" aria-label="Pesquisa de vendedor" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Pesquisa de Vendedor</h2>
        <p className="muted" style={{ marginTop: 4 }}>
          Digite o nome do funcionário e clique numa linha (ou Enter/Espaço com o foco nela) pra selecionar.
        </p>

        <label htmlFor="pdv-busca-vendedor">Nome do Funcionário</label>
        <input
          id="pdv-busca-vendedor"
          autoFocus
          placeholder="Ex.: JOÃO…"
          value={busca}
          onChange={(e) => setBusca(e.target.value.toUpperCase())}
        />

        <div className="table-wrap" style={{ marginTop: 16, maxHeight: 360 }}>
          <table className="table table-compacta">
            <thead>
              <tr>
                <th>Nome</th>
                <th>Cargo</th>
              </tr>
            </thead>
            <tbody>
              {isLoading ? (
                <tr>
                  <td colSpan={2}>
                    <p className="muted" style={{ margin: '8px 0' }}>
                      Buscando…
                    </p>
                  </td>
                </tr>
              ) : isError ? (
                <tr>
                  <td colSpan={2}>
                    <p className="erro" style={{ margin: '8px 0' }}>
                      Não foi possível buscar funcionários.
                    </p>
                  </td>
                </tr>
              ) : resultados.length === 0 ? (
                <tr>
                  <td colSpan={2}>
                    <p className="muted" style={{ margin: '8px 0' }}>
                      Nenhum funcionário encontrado.
                    </p>
                  </td>
                </tr>
              ) : (
                resultados.map((vendedor) => (
                  <tr
                    key={vendedor.idFuncionario}
                    tabIndex={0}
                    onClick={() => aoSelecionar(vendedor)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault()
                        aoSelecionar(vendedor)
                      }
                    }}
                  >
                    <td>{vendedor.nome}</td>
                    <td>{vendedor.cargo ?? '—'}</td>
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
          <button type="button" className="btn ghost" onClick={aoFechar}>
            Fechar
          </button>
        </div>
      </div>
    </div>
  )
}
