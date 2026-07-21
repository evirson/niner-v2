import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { IconeEngrenagem } from '../../components/Icones'
import { ApiError } from '../../lib/api'
import {
  excluirCliente,
  listarCategorias,
  listarClientes,
  type Cliente,
  type StatusCliente,
} from '../../lib/clientes'
import { useEu } from '../../lib/eu'
import { mascararCpfCnpj, mascararIdWhatsapp, mascararTelefone } from '../../lib/masks'
import { maiusculas } from '../../lib/texto'

/**
 * Monta a lista de páginas exibidas na navegação numerada: as 5 primeiras, reticências e as
 * 2 últimas (ex.: "1 2 3 4 5 … 9 10"). Sem reticências quando cabe tudo (até 7 páginas).
 */
function paginasVisiveis(total: number): Array<number | 'reticencias'> {
  if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1)
  return [1, 2, 3, 4, 5, 'reticencias', total - 1, total]
}

export default function ClienteLista() {
  const [nome, setNome] = useState('')
  const [status, setStatus] = useState<StatusCliente>('ATIVOS')
  const [idCategoriaCliente, setIdCategoriaCliente] = useState<number | undefined>(undefined)
  const [tamanhoPagina, setTamanhoPagina] = useState(10)
  const [clienteParaExcluir, setClienteParaExcluir] = useState<Cliente | null>(null)
  const [aviso, setAviso] = useState('')
  const queryClient = useQueryClient()

  const { data: categorias } = useQuery({ queryKey: ['categorias-cliente'], queryFn: listarCategorias })

  // Paginação por número de página (não por scroll infinito nem cursor — permite pular
  // direto para qualquer página, inclusive a última).
  const [pagina, setPagina] = useState(1)

  useEffect(() => {
    setPagina(1)
  }, [nome, status, idCategoriaCliente, tamanhoPagina])

  const { data, isLoading, isFetching } = useQuery({
    queryKey: ['clientes', { nome, status, idCategoriaCliente, tamanhoPagina, pagina }],
    queryFn: () =>
      listarClientes({
        nome: nome || undefined,
        status,
        idCategoriaCliente,
        pagina,
        tamanho: tamanhoPagina,
      }),
    placeholderData: (anterior) => anterior,
  })

  const totalPaginas = data?.totalPaginas ?? 1

  const excluir = useMutation({
    mutationFn: excluirCliente,
    onSuccess: (resposta) => {
      queryClient.invalidateQueries({ queryKey: ['clientes'] })
      setClienteParaExcluir(null)
      setAviso(
        resposta.acao === 'inativado'
          ? (resposta.motivo ?? 'Cliente inativado (possui vínculos).')
          : 'Cliente excluído.',
      )
    },
    onError: (e: unknown) => {
      setClienteParaExcluir(null)
      setAviso(e instanceof ApiError ? e.message : 'Não foi possível excluir o cliente.')
    },
  })

  const clientes: Cliente[] = data?.itens ?? []
  const { data: eu } = useEu()
  const ehAdmin = eu?.usuario.papel === 'ADMIN'

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <h1>Clientes</h1>
          <div className="topbar-acoes">
            {ehAdmin && (
              <Link
                className="btn ghost ajuda-gatilho"
                to="/clientes/configuracao"
                aria-label="Configurar tela de cliente"
                title="Configurar campos desta tela"
              >
                <IconeEngrenagem />
              </Link>
            )}
            <AjudaDaTela chaveTela="cadastros.cliente.lista" />
            <Link className="btn" to="/clientes/novo">
              ＋ Novo cliente
            </Link>
          </div>
        </div>

        {aviso && (
          <div className="card aviso-banner" role="status">
            <span>{aviso}</span>
            <button type="button" className="btn ghost" onClick={() => setAviso('')}>
              Ok
            </button>
          </div>
        )}

        <div className="card filtros-bar">
          <input
            placeholder="Buscar por nome…"
            value={nome}
            onChange={(e) => setNome(maiusculas(e.target.value))}
            aria-label="Buscar por nome"
          />
          <select
            value={idCategoriaCliente ?? ''}
            onChange={(e) => setIdCategoriaCliente(e.target.value ? Number(e.target.value) : undefined)}
            aria-label="Filtrar por categoria"
          >
            <option value="">Todas as categorias</option>
            {categorias?.map((c) => (
              <option key={c.idCategoriaCliente} value={c.idCategoriaCliente}>
                {c.nomeCategoria}
              </option>
            ))}
          </select>
          <select value={status} onChange={(e) => setStatus(e.target.value as StatusCliente)} aria-label="Filtrar por status">
            <option value="ATIVOS">Ativos</option>
            <option value="INATIVOS">Inativos</option>
            <option value="TODOS">Todos</option>
          </select>
          <select
            value={tamanhoPagina}
            onChange={(e) => setTamanhoPagina(Number(e.target.value))}
            aria-label="Itens por página"
          >
            <option value={10}>10 por página</option>
            <option value={20}>20 por página</option>
            <option value={50}>50 por página</option>
          </select>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card table-wrap">
        {isLoading ? (
          <p className="muted">Carregando…</p>
        ) : clientes.length === 0 ? (
          <p className="muted">Nenhum cliente encontrado.</p>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Nome / Razão Social</th>
                <th>CPF/CNPJ</th>
                <th>Categoria</th>
                <th>Celular</th>
                <th>Cidade/UF</th>
                <th>Status</th>
                <th aria-label="Ações" />
              </tr>
            </thead>
            <tbody>
              {clientes.map((c) => (
                <tr key={c.idCliente}>
                  <td>{c.nome}</td>
                  <td className="mono">{c.cpfCnpj ? mascararCpfCnpj(c.cpfCnpj, c.fisicaJuridica) : '—'}</td>
                  <td>{c.nomeCategoria}</td>
                  <td className="mono">
                    {c.telefone ? mascararTelefone(c.telefone) : c.whatsapp ? mascararIdWhatsapp(c.whatsapp) : '—'}
                  </td>
                  <td>{c.cidade ? `${c.cidade}${c.estado ? '/' + c.estado : ''}` : '—'}</td>
                  <td>
                    <span className={`badge ${c.ativo ? '' : 'badge-inativo'}`}>{c.ativo ? 'Ativo' : 'Inativo'}</span>
                  </td>
                  <td className="acoes-cell">
                    <Link className="btn ghost" to={`/clientes/${c.idCliente}`}>
                      Editar
                    </Link>
                    <button
                      type="button"
                      className="btn ghost"
                      disabled={excluir.isPending}
                      onClick={() => setClienteParaExcluir(c)}
                    >
                      Excluir
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        </div>
      </div>

      {clientes.length > 0 && (
        <div className="lista-rodape">
          <div className="paginacao-bar">
            <span className="muted">
              {data?.totalItens} cliente{data?.totalItens === 1 ? '' : 's'}
              {isFetching && ' · atualizando…'}
            </span>
            <div className="paginacao-paginas">
              {paginasVisiveis(totalPaginas).map((p, i) =>
                p === 'reticencias' ? (
                  <span key={`reticencias-${i}`} className="paginacao-reticencias">
                    …
                  </span>
                ) : (
                  <button
                    key={p}
                    type="button"
                    className={`btn ghost paginacao-numero ${p === pagina ? 'ativa' : ''}`}
                    onClick={() => setPagina(p)}
                    disabled={isFetching}
                    aria-current={p === pagina ? 'page' : undefined}
                  >
                    {p}
                  </button>
                ),
              )}
              <button
                type="button"
                className="btn ghost"
                onClick={() => setPagina((p) => Math.min(totalPaginas, p + 1))}
                disabled={pagina >= totalPaginas || isFetching}
                aria-label="Próxima página"
              >
                →
              </button>
            </div>
          </div>
        </div>
      )}

      {clienteParaExcluir && (
        <div className="modal-overlay" onClick={() => setClienteParaExcluir(null)}>
          <div
            className="modal"
            role="dialog"
            aria-label="Confirmar exclusão"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 style={{ marginTop: 0 }}>Excluir cliente?</h2>
            <p className="muted">
              Tem certeza que deseja excluir <strong>{clienteParaExcluir.nome}</strong>? Se houver vendas
              associadas, o cliente será inativado em vez de excluído.
            </p>
            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" onClick={() => setClienteParaExcluir(null)}>
                Cancelar
              </button>
              <button
                type="button"
                className="btn"
                disabled={excluir.isPending}
                onClick={() => excluir.mutate(clienteParaExcluir.idCliente)}
              >
                {excluir.isPending ? 'Excluindo…' : 'Excluir'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
