import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import {
  IconeEstoque,
  IconeExcluir,
  IconeOlho,
  IconePaginaAnterior,
  IconePrimeiraPagina,
  IconeProximaPagina,
  IconeUltimaPagina,
} from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { listarEmpresas } from '../../lib/empresas'
import { dataParaIso, dataValida, mascararData } from '../../lib/masks'
import {
  excluirTransferencia,
  listarTransferencias,
  type ColunaOrdenacaoTransferencia,
  type Transferencia,
} from '../../lib/transferencias'

const JANELA_PAGINACAO = 7
const TAMANHO_PAGINA = 50

const COLUNAS: Array<{ chave: ColunaOrdenacaoTransferencia; rotulo: string; alinhamento?: 'right' }> = [
  { chave: 'numero', rotulo: 'Nº', alinhamento: 'right' },
  { chave: 'data', rotulo: 'Data' },
  { chave: 'origem', rotulo: 'Origem' },
  { chave: 'destino', rotulo: 'Destino' },
  { chave: 'usuario', rotulo: 'Usuário' },
  { chave: 'itens', rotulo: 'Itens', alinhamento: 'right' },
]

function paginasVisiveis(atual: number, total: number): number[] {
  if (total <= JANELA_PAGINACAO) return Array.from({ length: total }, (_, i) => i + 1)
  let inicio = Math.max(1, atual - Math.floor(JANELA_PAGINACAO / 2))
  const fim = Math.min(total, inicio + JANELA_PAGINACAO - 1)
  inicio = Math.max(1, fim - JANELA_PAGINACAO + 1)
  return Array.from({ length: fim - inicio + 1 }, (_, i) => inicio + i)
}

function formatarData(iso: string): string {
  return new Date(iso).toLocaleString('pt-BR')
}

export default function TransferenciaLista() {
  const location = useLocation()
  const queryClient = useQueryClient()
  const [pagina, setPagina] = useState(1)
  const [ordenarPor, setOrdenarPor] = useState<ColunaOrdenacaoTransferencia>('data')
  const [direcao, setDirecao] = useState<'ASC' | 'DESC'>('DESC')
  const [idTransferenciaTexto, setIdTransferenciaTexto] = useState('')
  const [idEmpresaOrigem, setIdEmpresaOrigem] = useState('')
  const [idEmpresaDestino, setIdEmpresaDestino] = useState('')
  const [dataInicialTexto, setDataInicialTexto] = useState('')
  const [dataFinalTexto, setDataFinalTexto] = useState('')
  const [transferenciaParaExcluir, setTransferenciaParaExcluir] = useState<Transferencia | null>(null)
  const [aviso, setAviso] = useState<{ texto: string; tipo: TipoToast } | null>(
    () => (location.state as { toast?: { texto: string; tipo: TipoToast } } | null)?.toast ?? null,
  )

  useEffect(() => {
    if (location.state) window.history.replaceState({}, '')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const { data: empresas } = useQuery({ queryKey: ['empresas'], queryFn: listarEmpresas })

  // Só entra no filtro quando a data está completa e é uma data de calendário real — enquanto o
  // usuário ainda está digitando, o filtro simplesmente não é aplicado ainda.
  const dataInicialIso = dataValida(dataInicialTexto) ? dataParaIso(dataInicialTexto) : undefined
  const dataFinalIso = dataValida(dataFinalTexto) ? dataParaIso(dataFinalTexto) : undefined

  useEffect(() => {
    setPagina(1)
  }, [idTransferenciaTexto, idEmpresaOrigem, idEmpresaDestino, dataInicialIso, dataFinalIso, ordenarPor, direcao])

  const ordenarPorColuna = (coluna: ColunaOrdenacaoTransferencia) => {
    if (coluna === ordenarPor) {
      setDirecao((d) => (d === 'ASC' ? 'DESC' : 'ASC'))
    } else {
      setOrdenarPor(coluna)
      setDirecao('ASC')
    }
  }

  const { data, isLoading, isFetching } = useQuery({
    queryKey: [
      'transferencias',
      { idTransferenciaTexto, idEmpresaOrigem, idEmpresaDestino, dataInicialIso, dataFinalIso, pagina, ordenarPor, direcao },
    ],
    queryFn: () =>
      listarTransferencias({
        idTransferencia: idTransferenciaTexto ? Number(idTransferenciaTexto) : undefined,
        idEmpresaOrigem: idEmpresaOrigem ? Number(idEmpresaOrigem) : undefined,
        idEmpresaDestino: idEmpresaDestino ? Number(idEmpresaDestino) : undefined,
        dataInicial: dataInicialIso ?? undefined,
        dataFinal: dataFinalIso ?? undefined,
        pagina,
        limite: TAMANHO_PAGINA,
        ordenarPor,
        direcao,
      }),
    placeholderData: (anterior) => anterior,
  })

  const excluir = useMutation({
    mutationFn: excluirTransferencia,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['transferencias'] })
      setTransferenciaParaExcluir(null)
      setAviso({ texto: 'Transferência excluída — o estoque foi devolvido à empresa de origem.', tipo: 'sucesso' })
    },
    onError: (e: unknown) => {
      setTransferenciaParaExcluir(null)
      setAviso({ texto: e instanceof ApiError ? e.message : 'Não foi possível excluir a transferência.', tipo: 'erro' })
    },
  })

  const totalPaginas = data?.totalPaginas ?? 1
  const transferencias = data?.itens ?? []

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeEstoque size={34} />
            <h1>Transferência de Produtos</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="estoque.transferencia.lista" />
            <Link className="btn" to="/estoque/nova">
              ＋ Nova Transferência
            </Link>
            <BotaoFecharTela />
          </div>
        </div>

        {aviso && <Toast mensagem={aviso.texto} tipo={aviso.tipo} aoFechar={() => setAviso(null)} />}

        <div className="card filtros-bar">
          <input
            autoFocus
            placeholder="Data inicial"
            value={dataInicialTexto}
            onChange={(e) => setDataInicialTexto(mascararData(e.target.value))}
            onFocus={(e) => e.target.select()}
            aria-label="Data inicial"
            style={{ maxWidth: 140 }}
          />
          <input
            placeholder="Data final"
            value={dataFinalTexto}
            onChange={(e) => setDataFinalTexto(mascararData(e.target.value))}
            onFocus={(e) => e.target.select()}
            aria-label="Data final"
            style={{ maxWidth: 140 }}
          />
          <input
            placeholder="Nº da transferência"
            value={idTransferenciaTexto}
            onChange={(e) => setIdTransferenciaTexto(e.target.value.replace(/\D/g, ''))}
            onFocus={(e) => e.target.select()}
            inputMode="numeric"
            aria-label="Filtrar por número da transferência"
            style={{ maxWidth: 150 }}
          />
          <select
            value={idEmpresaOrigem}
            onChange={(e) => setIdEmpresaOrigem(e.target.value)}
            aria-label="Filtrar por empresa de saída"
          >
            <option value="">Empresa de saída (todas)</option>
            {empresas?.map((emp) => (
              <option key={emp.idEmpresa} value={emp.idEmpresa}>
                {emp.nomeFantasia ?? emp.razaoSocial}
              </option>
            ))}
          </select>
          <select
            value={idEmpresaDestino}
            onChange={(e) => setIdEmpresaDestino(e.target.value)}
            aria-label="Filtrar por empresa de entrada"
          >
            <option value="">Empresa de entrada (todas)</option>
            {empresas?.map((emp) => (
              <option key={emp.idEmpresa} value={emp.idEmpresa}>
                {emp.nomeFantasia ?? emp.razaoSocial}
              </option>
            ))}
          </select>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card table-wrap">
          {isLoading ? (
            <p className="muted">Carregando…</p>
          ) : transferencias.length === 0 ? (
            <p className="muted">Nenhuma transferência encontrada.</p>
          ) : (
            <table className="table table-compacta">
              <thead>
                <tr>
                  {COLUNAS.map((c) => {
                    const ativa = ordenarPor === c.chave
                    return (
                      <th
                        key={c.chave}
                        className="th-ordenavel"
                        style={c.alinhamento === 'right' ? { textAlign: 'right' } : undefined}
                        onClick={() => ordenarPorColuna(c.chave)}
                        title="Clique para ordenar"
                        aria-sort={ativa ? (direcao === 'ASC' ? 'ascending' : 'descending') : 'none'}
                      >
                        {c.rotulo}
                        <span className={`th-seta ${ativa ? 'th-seta-ativa' : ''}`}>
                          {ativa ? (direcao === 'ASC' ? '▲' : '▼') : '⇅'}
                        </span>
                      </th>
                    )
                  })}
                  <th aria-label="Ações" />
                </tr>
              </thead>
              <tbody>
                {transferencias.map((t) => (
                  <tr key={t.idTransferencia}>
                    <td className="mono" style={{ textAlign: 'right' }}>
                      {t.idTransferencia}
                    </td>
                    <td className="mono">{formatarData(t.dataTransferencia)}</td>
                    <td>{t.empresaOrigem.nomeEmpresa}</td>
                    <td>{t.empresaDestino.nomeEmpresa}</td>
                    <td>{t.nomeUsuario}</td>
                    <td className="mono" style={{ textAlign: 'right' }}>
                      {t.itens.length}
                    </td>
                    <td className="acoes-cell">
                      <Link
                        className="acao-icone acao-visualizar"
                        to={`/estoque/${t.idTransferencia}`}
                        aria-label={`Visualizar transferência ${t.idTransferencia}`}
                        title="Visualizar"
                      >
                        <IconeOlho />
                      </Link>
                      <button
                        type="button"
                        className="acao-icone acao-excluir"
                        disabled={excluir.isPending}
                        onClick={() => setTransferenciaParaExcluir(t)}
                        aria-label={`Excluir transferência ${t.idTransferencia}`}
                        title="Excluir"
                      >
                        <IconeExcluir />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      {transferencias.length > 0 && (
        <div className="lista-rodape">
          <div className="paginacao-bar">
            <span className="muted">
              {data?.totalItens} transferência{data?.totalItens === 1 ? '' : 's'}
              {isFetching && ' · atualizando…'}
            </span>
            <div className="paginacao-paginas">
              <button
                type="button"
                className="btn ghost paginacao-seta"
                onClick={() => setPagina(1)}
                disabled={pagina <= 1 || isFetching}
                aria-label="Primeira página"
                title="Primeira página"
              >
                <IconePrimeiraPagina />
              </button>
              <button
                type="button"
                className="btn ghost paginacao-seta"
                onClick={() => setPagina((p) => Math.max(1, p - 1))}
                disabled={pagina <= 1 || isFetching}
                aria-label="Página anterior"
                title="Página anterior"
              >
                <IconePaginaAnterior />
              </button>
              {paginasVisiveis(pagina, totalPaginas).map((p) => (
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
              ))}
              <button
                type="button"
                className="btn ghost paginacao-seta"
                onClick={() => setPagina((p) => Math.min(totalPaginas, p + 1))}
                disabled={pagina >= totalPaginas || isFetching}
                aria-label="Próxima página"
                title="Próxima página"
              >
                <IconeProximaPagina />
              </button>
              <button
                type="button"
                className="btn ghost paginacao-seta"
                onClick={() => setPagina(totalPaginas)}
                disabled={pagina >= totalPaginas || isFetching}
                aria-label="Última página"
                title="Última página"
              >
                <IconeUltimaPagina />
              </button>
            </div>
          </div>
        </div>
      )}

      {transferenciaParaExcluir && (
        <div className="modal-overlay" onClick={() => setTransferenciaParaExcluir(null)}>
          <div className="modal" role="dialog" aria-label="Confirmar exclusão" onClick={(e) => e.stopPropagation()}>
            <h2 style={{ marginTop: 0 }}>Excluir transferência?</h2>
            <p className="muted">
              Tem certeza que deseja excluir a transferência <strong>#{transferenciaParaExcluir.idTransferencia}</strong> (
              {transferenciaParaExcluir.empresaOrigem.nomeEmpresa} → {transferenciaParaExcluir.empresaDestino.nomeEmpresa})?
              A quantidade de cada produto será devolvida à empresa de origem, mesmo que a de destino não tenha mais
              saldo suficiente. Esta ação não pode ser desfeita.
            </p>
            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" onClick={() => setTransferenciaParaExcluir(null)}>
                Cancelar
              </button>
              <button
                type="button"
                className="btn"
                disabled={excluir.isPending}
                onClick={() => excluir.mutate(transferenciaParaExcluir.idTransferencia)}
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
