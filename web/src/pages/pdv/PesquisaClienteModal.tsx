import CabecalhoModal from '../../components/CabecalhoModal'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { buscarClientesPdv, type PdvCliente } from '../../lib/pdv'
import ClienteFormModal from './ClienteFormModal'
import { usePermissaoDaTela } from '../../lib/usePermissaoDaTela'

/**
 * F6 — Cliente da Venda (2026-07-28): busca por nome, CPF/CNPJ ou celular via
 * `GET /api/v1/pdv/clientes` (só cliente ativo). Cliente é obrigatório pra confirmar a venda.
 */
export default function PesquisaClienteModal({
  aoFechar,
  aoSelecionar,
}: {
  aoFechar: () => void
  aoSelecionar: (cliente: PdvCliente) => void
}) {
  const [busca, setBusca] = useState('')
  const [modalNovoClienteAberto, setModalNovoClienteAberto] = useState(false)
  const podeCriarCliente = usePermissaoDaTela('clientes').incluir

  const { data: resultados, isLoading, isError } = useQuery({
    queryKey: ['pdv-clientes', busca],
    queryFn: () => buscarClientesPdv(busca),
  })

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal modal-largo" role="dialog" aria-label="Pesquisa de cliente" onClick={(e) => e.stopPropagation()}>
        <CabecalhoModal titulo="Pesquisa de Cliente" aoFechar={aoFechar} />
        <p className="muted" style={{ marginTop: 4 }}>
          Digite o nome, CPF/CNPJ ou celular e clique numa linha (ou Enter/Espaço com o foco nela) pra selecionar.
          Não encontrou? Cadastre um cliente novo sem sair da venda.
        </p>

        <label htmlFor="pdv-busca-cliente">Nome, CPF/CNPJ ou Celular</label>
        <div className="linha-com-botao">
          <input
            id="pdv-busca-cliente"
            autoFocus
            style={{ flex: 1 }}
            placeholder="Ex.: MARIA, 111.444.777-35, 11988887777…"
            value={busca}
            onChange={(e) => setBusca(e.target.value.toUpperCase())}
          />
          {/* ⛔ O "＋" de modal embutido é o mesmo defeito noutro lugar (auditoria 2026-08-29,
              rodada 3): o cadastro rápido daqui é um POST em `clientes`, então quem não recebeu
              `clientes.incluir` levava 403 NO MEIO DA VENDA, com o cliente no balcão. */}
          {podeCriarCliente && (
            <button type="button" className="btn ghost" onClick={() => setModalNovoClienteAberto(true)}>
              ＋ Novo Cliente
            </button>
          )}
        </div>

        <div className="table-wrap" style={{ marginTop: 16, maxHeight: 360 }}>
          <table className="table table-compacta">
            <thead>
              <tr>
                <th>Nome</th>
                <th>CPF/CNPJ</th>
                <th>Celular</th>
              </tr>
            </thead>
            <tbody>
              {isLoading ? (
                <tr>
                  <td colSpan={3}>
                    <p className="muted" style={{ margin: '8px 0' }}>
                      Buscando…
                    </p>
                  </td>
                </tr>
              ) : isError ? (
                <tr>
                  <td colSpan={3}>
                    <p className="erro" style={{ margin: '8px 0' }}>
                      Não foi possível buscar clientes.
                    </p>
                  </td>
                </tr>
              ) : !resultados || resultados.length === 0 ? (
                <tr>
                  <td colSpan={3}>
                    <p className="muted" style={{ margin: '8px 0' }}>
                      Nenhum cliente encontrado.
                    </p>
                  </td>
                </tr>
              ) : (
                resultados.map((cliente) => (
                  <tr
                    key={cliente.idCliente}
                    tabIndex={0}
                    onClick={() => aoSelecionar(cliente)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault()
                        aoSelecionar(cliente)
                      }
                    }}
                  >
                    <td>{cliente.nome}</td>
                    <td className="mono">{cliente.cpfCnpj ?? '—'}</td>
                    <td className="mono">{cliente.telefone ?? '—'}</td>
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

      {modalNovoClienteAberto && (
        <ClienteFormModal
          aoFechar={() => setModalNovoClienteAberto(false)}
          aoCriar={(cliente) => aoSelecionar(cliente)}
        />
      )}
    </div>
  )
}
