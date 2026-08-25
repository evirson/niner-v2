import CabecalhoModal from '../components/CabecalhoModal'
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { buscarNcmsPorNome, type Ncm } from '../lib/ncm'

/**
 * Pesquisa de NCM por nome (2026-08-11, pedido do dono do produto) — quem cadastra um produto
 * nem sempre sabe o código NCM de cabeça. Mesmo padrão de `PesquisaProdutoModal` (PDV): digita,
 * busca ao vivo, clica numa linha pra usar. Usada tanto pela tela completa de Produto quanto
 * pelo cadastro rápido embutido na Entrada de Produtos.
 */
export default function PesquisaNcmModal({
  aoFechar,
  aoSelecionar,
}: {
  aoFechar: () => void
  aoSelecionar: (ncm: Ncm) => void
}) {
  const [busca, setBusca] = useState('')

  const { data: resultados, isLoading, isError } = useQuery({
    queryKey: ['ncm-busca', busca],
    queryFn: () => buscarNcmsPorNome(busca),
    enabled: busca.trim().length >= 3,
  })

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal modal-largo" role="dialog" aria-label="Pesquisa de NCM" onClick={(e) => e.stopPropagation()}>
        <CabecalhoModal titulo="Pesquisa de NCM" aoFechar={aoFechar} />
        <p className="muted" style={{ marginTop: 4 }}>
          Digite parte do nome da mercadoria e clique numa linha (ou Enter/Espaço com o foco nela) pra usar este NCM.
        </p>

        <label htmlFor="ncm-busca-nome">Nome</label>
        <input
          id="ncm-busca-nome"
          autoFocus
          placeholder="Ex.: CAMISETA, TÊNIS…"
          value={busca}
          onChange={(e) => setBusca(e.target.value.toUpperCase())}
        />

        <div className="table-wrap" style={{ marginTop: 16, maxHeight: 360 }}>
          <table className="table table-compacta">
            <thead>
              <tr>
                <th>Código</th>
                <th>Descrição</th>
              </tr>
            </thead>
            <tbody>
              {busca.trim().length < 3 ? (
                <tr>
                  <td colSpan={2}>
                    <p className="muted" style={{ margin: '8px 0' }}>
                      Digite ao menos 3 letras para buscar.
                    </p>
                  </td>
                </tr>
              ) : isLoading ? (
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
                      Não foi possível buscar o NCM.
                    </p>
                  </td>
                </tr>
              ) : !resultados || resultados.length === 0 ? (
                <tr>
                  <td colSpan={2}>
                    <p className="muted" style={{ margin: '8px 0' }}>
                      Nenhum NCM encontrado.
                    </p>
                  </td>
                </tr>
              ) : (
                resultados.map((ncm) => (
                  <tr
                    key={ncm.codigoNcm}
                    tabIndex={0}
                    onClick={() => aoSelecionar(ncm)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault()
                        aoSelecionar(ncm)
                      }
                    }}
                  >
                    <td className="mono">{ncm.codigoNcm}</td>
                    <td>{ncm.descricaoNcm}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        <div className="ajuda-rodape">

        </div>
      </div>
    </div>
  )
}
