import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconePdv } from '../../components/Icones'
import { ApiError } from '../../lib/api'
import { hojeISO } from '../../lib/datas'
import { useEu } from '../../lib/eu'
import { dataParaIso, dataValida, formatarMoeda, isoParaData, mascararData } from '../../lib/masks'
import type { PdvCliente } from '../../lib/pdv'
import { pesquisarVendas, type VendaPesquisa } from '../../lib/pesquisaVendas'
import PesquisaClienteModal from '../pdv/PesquisaClienteModal'
import ComprovantePapeletaModal from '../pdv/ComprovantePapeletaModal'

const JANELA_PAGINACAO = 7
const TAMANHO_PAGINA = 50

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
 * Reimpressão de Papeleta de Venda (2026-08-06) — localiza uma venda já efetivada (por número,
 * ou por período + cliente) e reabre a papeleta pra imprimir/salvar de novo. Reaproveita
 * `pesquisarVendas` (mesmo endpoint da Pesquisa de Vendas, só com um subconjunto dos filtros —
 * aqui só Nº/período/cliente, sem empresa/situação/vendedor) e `ComprovantePapeletaModal` (mesmo
 * componente da papeleta pós-F5, com `reimpressao` ligado — muda o título e acrescenta
 * "REIMPRESSÃO DE PAPELETA DE VENDA" + "Impresso em: <data/hora atual>" no final).
 */
export default function ReimpressaoPapeletaVenda() {
  const { data: eu } = useEu()
  const ehAdmin = eu?.usuario.papel === 'ADMIN'

  const [numeroVendaTexto, setNumeroVendaTexto] = useState('')
  const [cliente, setCliente] = useState<PdvCliente | null>(null)
  const [dataInicialTexto, setDataInicialTexto] = useState(isoParaData(primeiroDiaDoMesISO()))
  const [dataFinalTexto, setDataFinalTexto] = useState(isoParaData(hojeISO()))
  const [pagina, setPagina] = useState(1)
  const [mostrarBuscaCliente, setMostrarBuscaCliente] = useState(false)
  const [idVendaReimpressao, setIdVendaReimpressao] = useState<number | null>(null)

  const primeiraRenderizacao = useRef(true)
  useEffect(() => {
    if (primeiraRenderizacao.current) {
      primeiraRenderizacao.current = false
      return
    }
    setPagina(1)
  }, [numeroVendaTexto, cliente, dataInicialTexto, dataFinalTexto])

  const numeroVenda = numeroVendaTexto.trim() ? Number(numeroVendaTexto.trim()) : undefined
  const dataInicialIso = dataValida(dataInicialTexto) ? dataParaIso(dataInicialTexto) ?? undefined : undefined
  const dataFinalIso = dataValida(dataFinalTexto) ? dataParaIso(dataFinalTexto) ?? undefined : undefined

  const podeBuscar = numeroVenda !== undefined || (dataInicialIso !== undefined && dataFinalIso !== undefined)

  const { data, isLoading, isFetching, error } = useQuery({
    queryKey: ['reimpressao-papeleta-venda', { numeroVenda, idCliente: cliente?.idCliente, dataInicialIso, dataFinalIso, pagina }],
    queryFn: () =>
      pesquisarVendas({
        numeroVenda,
        idCliente: cliente?.idCliente,
        dataInicial: dataInicialIso,
        dataFinal: dataFinalIso,
        pagina,
        tamanho: TAMANHO_PAGINA,
        ordenarPor: 'dataVenda',
        direcao: 'DESC',
      }),
    enabled: podeBuscar,
    placeholderData: (anterior) => anterior,
  })

  const vendas: VendaPesquisa[] = data?.itens ?? []
  const totalPaginas = data?.totalPaginas ?? 1

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconePdv size={34} />
            <h1>Reimpressão de Papeleta de Venda</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="vendas.reimpressaopapeleta.tela" />
            <BotaoFecharTela />
          </div>
        </div>

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
            <p className="muted">Nenhuma venda encontrada para os filtros informados.</p>
          ) : (
            <table className="table table-compacta">
              <thead>
                <tr>
                  {ehAdmin && <th>Empresa</th>}
                  <th>Nº Venda</th>
                  <th>Data</th>
                  <th>Cliente</th>
                  <th>Vendedor</th>
                  <th style={{ textAlign: 'right' }}>Valor</th>
                  <th aria-label="Situação" />
                </tr>
              </thead>
              <tbody>
                {vendas.map((v) => (
                  <tr
                    key={v.idVenda}
                    tabIndex={0}
                    onClick={() => setIdVendaReimpressao(v.idVenda)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault()
                        setIdVendaReimpressao(v.idVenda)
                      }
                    }}
                    title="Clique para reimprimir a papeleta desta venda"
                  >
                    {ehAdmin && <td>{v.nomeEmpresa}</td>}
                    <td className="mono">{v.idVenda}</td>
                    <td>{formatarData(v.dataVenda)}</td>
                    <td>{v.nomeCliente ?? '—'}</td>
                    <td>{v.nomeFuncionario ?? '—'}</td>
                    <td className="mono" style={{ textAlign: 'right' }}>
                      {moeda(v.valorVenda)}
                    </td>
                    <td>{v.cancelada && <span className="badge badge-inativo">Cancelada</span>}</td>
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
      {idVendaReimpressao !== null && (
        <ComprovantePapeletaModal idVenda={idVendaReimpressao} reimpressao aoFechar={() => setIdVendaReimpressao(null)} />
      )}
    </div>
  )
}
