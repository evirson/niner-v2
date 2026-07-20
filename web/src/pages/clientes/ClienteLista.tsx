import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
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

export default function ClienteLista() {
  const [nome, setNome] = useState('')
  const [status, setStatus] = useState<StatusCliente>('ATIVOS')
  const [idCategoriaCliente, setIdCategoriaCliente] = useState<number | undefined>(undefined)
  const [clienteParaExcluir, setClienteParaExcluir] = useState<Cliente | null>(null)
  const [aviso, setAviso] = useState('')
  const queryClient = useQueryClient()

  const { data: categorias } = useQuery({ queryKey: ['categorias-cliente'], queryFn: listarCategorias })

  const { data, fetchNextPage, hasNextPage, isFetchingNextPage, isLoading } = useInfiniteQuery({
    queryKey: ['clientes', { nome, status, idCategoriaCliente }],
    queryFn: ({ pageParam }) =>
      listarClientes({ nome: nome || undefined, status, idCategoriaCliente, cursor: pageParam }),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (ultima) => ultima.proximoCursor ?? undefined,
  })

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

  const clientes: Cliente[] = data?.pages.flatMap((p) => p.itens) ?? []
  const { data: eu } = useEu()
  const ehAdmin = eu?.usuario.papel === 'ADMIN'

  return (
    <div>
      <div className="topbar-tela">
        <div>
          <p className="eyebrow">Cadastros</p>
          <h1 style={{ marginTop: 4 }}>Clientes</h1>
        </div>
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
          onChange={(e) => setNome(e.target.value)}
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
      </div>

      <div className="card table-wrap" style={{ marginTop: 16 }}>
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

      {hasNextPage && (
        <button
          type="button"
          className="btn ghost"
          style={{ marginTop: 12 }}
          onClick={() => fetchNextPage()}
          disabled={isFetchingNextPage}
        >
          {isFetchingNextPage ? 'Carregando…' : 'Carregar mais'}
        </button>
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
