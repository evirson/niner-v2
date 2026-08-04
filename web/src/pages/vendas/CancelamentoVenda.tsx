import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeCancelamentoVenda, IconeOlho } from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { ApiError } from '../../lib/api'
import {
  listarVendasParaCancelamento,
  type ColunaOrdenacaoCancelamento,
  type VendaParaCancelamento,
} from '../../lib/cancelamentoVenda'
import { hojeISO } from '../../lib/datas'
import { listarEmpresas, type Empresa } from '../../lib/empresas'
import { dataParaIso, dataValida, formatarMoeda, isoParaData, mascararData } from '../../lib/masks'
import type { Funcionario } from '../../lib/funcionarios'
import type { PdvCliente } from '../../lib/pdv'
import CancelamentoVendaModal from './CancelamentoVendaModal'
import PesquisaClienteModal from '../pdv/PesquisaClienteModal'
import PesquisaVendedorModal from '../pdv/PesquisaVendedorModal'

const JANELA_PAGINACAO = 7
const TAMANHO_PAGINA = 50

const COLUNAS: Array<{ chave: ColunaOrdenacaoCancelamento; rotulo: string }> = [
  { chave: 'nomeEmpresa', rotulo: 'Empresa' },
  { chave: 'numeroVenda', rotulo: 'Nº Venda' },
  { chave: 'dataVenda', rotulo: 'Data' },
  { chave: 'nomeCliente', rotulo: 'Cliente' },
  { chave: 'nomeFuncionario', rotulo: 'Vendedor' },
  { chave: 'valorVenda', rotulo: 'Valor' },
]

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

function formatarData(iso: string): string {
  return new Date(iso).toLocaleDateString('pt-BR')
}

function primeiroDiaDoMesISO(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`
}

function paginasVisiveis(atual: number, total: number): number[] {
  if (total <= JANELA_PAGINACAO) return Array.from({ length: total }, (_, i) => i + 1)
  let inicio = Math.max(1, atual - Math.floor(JANELA_PAGINACAO / 2))
  const fim = Math.min(total, inicio + JANELA_PAGINACAO - 1)
  inicio = Math.max(1, fim - JANELA_PAGINACAO + 1)
  return Array.from({ length: fim - inicio + 1 }, (_, i) => inicio + i)
}

/**
 * Cancelamento de Venda (docs/telas/cancelamento-venda.md) — ADMIN-only (rota já protegida por
 * `RequireAdmin`, checado de novo no servidor). Busca por número ignora os demais filtros
 * (RN "número da venda"); fora disso, exige o intervalo de datas. Bloqueio de crediário com
 * parcela recebida (RN-03) e caixa de hoje fechado (RN-02) são resolvidos dentro do modal de
 * confirmação, que também mostra tudo que será revertido.
 */
export default function CancelamentoVenda() {
  const navigate = useNavigate()
  const location = useLocation()

  const [numeroVendaTexto, setNumeroVendaTexto] = useState('')
  const [idEmpresa, setIdEmpresa] = useState<number | ''>('')
  const [cliente, setCliente] = useState<PdvCliente | null>(null)
  const [vendedor, setVendedor] = useState<Funcionario | null>(null)
  const [dataInicialTexto, setDataInicialTexto] = useState(isoParaData(primeiroDiaDoMesISO()))
  const [dataFinalTexto, setDataFinalTexto] = useState(isoParaData(hojeISO()))
  const [pagina, setPagina] = useState(1)
  const [ordenarPor, setOrdenarPor] = useState<ColunaOrdenacaoCancelamento>('dataVenda')
  const [direcao, setDirecao] = useState<'ASC' | 'DESC'>('DESC')
  const [mostrarBuscaCliente, setMostrarBuscaCliente] = useState(false)
  const [mostrarBuscaVendedor, setMostrarBuscaVendedor] = useState(false)
  const [vendaSelecionada, setVendaSelecionada] = useState<VendaParaCancelamento | null>(null)
  const [aviso, setAviso] = useState<{ texto: string; tipo: TipoToast } | null>(
    () => (location.state as { toast?: { texto: string; tipo: TipoToast } } | null)?.toast ?? null,
  )

  useEffect(() => {
    if (location.state) window.history.replaceState({}, '')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const primeiraRenderizacao = useRef(true)
  useEffect(() => {
    if (primeiraRenderizacao.current) {
      primeiraRenderizacao.current = false
      return
    }
    setPagina(1)
  }, [numeroVendaTexto, idEmpresa, cliente, vendedor, dataInicialTexto, dataFinalTexto, ordenarPor, direcao])

  const { data: empresas } = useQuery({ queryKey: ['empresas'], queryFn: listarEmpresas })

  const numeroVenda = numeroVendaTexto.trim() ? Number(numeroVendaTexto.trim()) : undefined
  const dataInicialIso = dataValida(dataInicialTexto) ? dataParaIso(dataInicialTexto) ?? undefined : undefined
  const dataFinalIso = dataValida(dataFinalTexto) ? dataParaIso(dataFinalTexto) ?? undefined : undefined

  const podeBuscar = numeroVenda !== undefined || (dataInicialIso !== undefined && dataFinalIso !== undefined)

  const { data, isLoading, isFetching, error } = useQuery({
    queryKey: [
      'vendas-cancelamento',
      { numeroVenda, idEmpresa, idCliente: cliente?.idCliente, idFuncionario: vendedor?.idFuncionario,
        dataInicialIso, dataFinalIso, pagina, ordenarPor, direcao },
    ],
    queryFn: () =>
      listarVendasParaCancelamento({
        numeroVenda,
        idEmpresa: idEmpresa || undefined,
        idCliente: cliente?.idCliente,
        idFuncionario: vendedor?.idFuncionario,
        dataInicial: dataInicialIso,
        dataFinal: dataFinalIso,
        pagina,
        tamanho: TAMANHO_PAGINA,
        ordenarPor,
        direcao,
      }),
    enabled: podeBuscar,
    placeholderData: (anterior) => anterior,
  })

  const ordenarPorColuna = (coluna: ColunaOrdenacaoCancelamento) => {
    if (coluna === ordenarPor) {
      setDirecao((d) => (d === 'ASC' ? 'DESC' : 'ASC'))
    } else {
      setOrdenarPor(coluna)
      setDirecao('ASC')
    }
  }

  const vendas: VendaParaCancelamento[] = data?.itens ?? []
  const totalPaginas = data?.totalPaginas ?? 1

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeCancelamentoVenda size={34} />
            <h1>Cancelamento de Venda</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="vendas.cancelamentovenda.lista" />
            <BotaoFecharTela />
          </div>
        </div>

        {aviso && <Toast mensagem={aviso.texto} tipo={aviso.tipo} aoFechar={() => setAviso(null)} />}

        <div className="card filtros-bar">
          <input
            placeholder="Nº da venda…"
            value={numeroVendaTexto}
            onChange={(e) => setNumeroVendaTexto(e.target.value.replace(/\D/g, ''))}
            aria-label="Buscar por número da venda"
            style={{ maxWidth: 140 }}
          />
          <input
            className="mono"
            placeholder="dd/mm/aaaa"
            value={dataInicialTexto}
            onChange={(e) => setDataInicialTexto(mascararData(e.target.value))}
            onFocus={(e) => e.target.select()}
            aria-label="Data inicial"
            disabled={!!numeroVendaTexto}
            style={{ maxWidth: 130 }}
          />
          <input
            className="mono"
            placeholder="dd/mm/aaaa"
            value={dataFinalTexto}
            onChange={(e) => setDataFinalTexto(mascararData(e.target.value))}
            onFocus={(e) => e.target.select()}
            aria-label="Data final"
            disabled={!!numeroVendaTexto}
            style={{ maxWidth: 130 }}
          />
          <select
            value={idEmpresa}
            onChange={(e) => setIdEmpresa(e.target.value ? Number(e.target.value) : '')}
            aria-label="Filtrar por empresa"
            disabled={!!numeroVendaTexto}
          >
            <option value="">Todas as empresas</option>
            {empresas?.map((emp: Empresa) => (
              <option key={emp.idEmpresa} value={emp.idEmpresa}>
                {emp.nomeFantasia ?? emp.razaoSocial}
              </option>
            ))}
          </select>
          <button
            type="button"
            className="btn ghost"
            onClick={() => setMostrarBuscaCliente(true)}
            disabled={!!numeroVendaTexto}
          >
            {cliente ? cliente.nome : 'Cliente…'}
          </button>
          {cliente && (
            <button type="button" className="btn ghost" onClick={() => setCliente(null)} aria-label="Limpar cliente">
              ✕
            </button>
          )}
          <button
            type="button"
            className="btn ghost"
            onClick={() => setMostrarBuscaVendedor(true)}
            disabled={!!numeroVendaTexto}
          >
            {vendedor ? vendedor.nome : 'Vendedor…'}
          </button>
          {vendedor && (
            <button type="button" className="btn ghost" onClick={() => setVendedor(null)} aria-label="Limpar vendedor">
              ✕
            </button>
          )}
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card table-wrap">
          {!podeBuscar ? (
            <p className="muted">Informe o número da venda, ou a data inicial e final.</p>
          ) : isLoading ? (
            <p className="muted">Carregando…</p>
          ) : error ? (
            <p className="erro">{error instanceof ApiError ? error.message : 'Não foi possível buscar as vendas.'}</p>
          ) : vendas.length === 0 ? (
            <p className="muted">Nenhuma venda encontrada.</p>
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
                {vendas.map((v) => (
                  <tr key={v.idVenda}>
                    <td>{v.nomeEmpresa}</td>
                    <td className="mono">{v.idVenda}</td>
                    <td>{formatarData(v.dataVenda)}</td>
                    <td>{v.nomeCliente ?? '—'}</td>
                    <td>{v.nomeFuncionario ?? '—'}</td>
                    <td className="mono">{moeda(v.valorVenda)}</td>
                    <td className="acoes-cell">
                      <button
                        type="button"
                        className="acao-icone acao-visualizar"
                        onClick={() => setVendaSelecionada(v)}
                        aria-label={`Ver venda nº ${v.idVenda}`}
                        title={v.cancelada || v.bloqueadaCredario ? 'Visualizar' : 'Visualizar / Cancelar'}
                      >
                        <IconeOlho />
                      </button>
                      {v.cancelada ? (
                        <span className="badge badge-inativo">Cancelada</span>
                      ) : v.bloqueadaCredario ? (
                        <span className="badge" title="Crediário com parcela recebida — cancelamento bloqueado">
                          🔒 Bloqueada
                        </span>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      {vendas.length > 0 && (
        <div className="lista-rodape">
          <div className="paginacao-bar">
            <span className="muted">
              {data?.totalItens} venda{data?.totalItens === 1 ? '' : 's'}
              {isFetching && ' · atualizando…'}
            </span>
            <div className="paginacao-paginas">
              <button type="button" className="btn ghost paginacao-seta" onClick={() => setPagina(1)}
                      disabled={pagina <= 1 || isFetching} aria-label="Primeira página" title="Primeira página">
                «
              </button>
              <button type="button" className="btn ghost paginacao-seta" onClick={() => setPagina((p) => Math.max(1, p - 1))}
                      disabled={pagina <= 1 || isFetching} aria-label="Página anterior" title="Página anterior">
                ‹
              </button>
              {paginasVisiveis(pagina, totalPaginas).map((p) => (
                <button key={p} type="button" className={`btn ghost paginacao-numero ${p === pagina ? 'ativa' : ''}`}
                        onClick={() => setPagina(p)} disabled={isFetching} aria-current={p === pagina ? 'page' : undefined}>
                  {p}
                </button>
              ))}
              <button type="button" className="btn ghost paginacao-seta" onClick={() => setPagina((p) => Math.min(totalPaginas, p + 1))}
                      disabled={pagina >= totalPaginas || isFetching} aria-label="Próxima página" title="Próxima página">
                ›
              </button>
              <button type="button" className="btn ghost paginacao-seta" onClick={() => setPagina(totalPaginas)}
                      disabled={pagina >= totalPaginas || isFetching} aria-label="Última página" title="Última página">
                »
              </button>
            </div>
          </div>
        </div>
      )}

      {mostrarBuscaCliente && (
        <PesquisaClienteModal
          aoFechar={() => setMostrarBuscaCliente(false)}
          aoSelecionar={(c) => {
            setCliente(c)
            setMostrarBuscaCliente(false)
          }}
        />
      )}
      {mostrarBuscaVendedor && (
        <PesquisaVendedorModal
          aoFechar={() => setMostrarBuscaVendedor(false)}
          aoSelecionar={(f) => {
            setVendedor(f)
            setMostrarBuscaVendedor(false)
          }}
        />
      )}
      {vendaSelecionada && (
        <CancelamentoVendaModal
          venda={vendaSelecionada}
          aoFechar={() => setVendaSelecionada(null)}
          aoCancelarComSucesso={() => {
            setVendaSelecionada(null)
            setAviso({ texto: `Venda nº ${vendaSelecionada.idVenda} cancelada com sucesso.`, tipo: 'sucesso' })
          }}
        />
      )}
    </div>
  )
}
