import CabecalhoModal from '../../components/CabecalhoModal'
import { useEffect, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeDocumentoFiscal, IconeLinkExterno, IconeOlho, IconeRecibo } from '../../components/Icones'
import { useAuth } from '../../lib/auth'
import { ApiError } from '../../lib/api'
import { usePermissaoDaTela } from '../../lib/usePermissaoDaTela'
import { hojeISO } from '../../lib/datas'
import { dataParaIso, dataValida, formatarMoeda, isoParaData, mascararData } from '../../lib/masks'
import { listarEmpresasFiscal } from '../../lib/fiscalConfiguracao'
import {
  buscarXmlDocumentoFiscal,
  consultarDocumentoNaSefaz,
  listarDocumentosFiscais,
  reprocessarDocumentoFiscal,
  type DocumentoFiscalItem,
} from '../../lib/documentoFiscal'
import Toast from '../../components/Toast'
import ComprovantePapeletaModal from '../pdv/ComprovantePapeletaModal'
import DanfeModal from '../vendas/DanfeModal'

/** Só nestas situações o comprovante do PDV vem com {@code dadosFiscais} preenchido (autorizado
 *  ou em contingência) — nas outras, abrir o DANFCE mostraria um recibo comum sem QR/protocolo. */
const SITUACOES_COM_DANFCE = new Set(['AUTORIZADO', 'CONTINGENCIA'])

/** Situações em que a nota ficou presa numa transmissão que não terminou — candidatas a
 *  reprocessar (§12: consulta a SEFAZ antes de decidir, F5). */
const SITUACOES_REPROCESSAVEIS = new Set(['TRANSMITINDO', 'ASSINADO'])

const JANELA_PAGINACAO = 7
const TAMANHO_PAGINA = 50

const ROTULO_SITUACAO: Record<string, string> = {
  RASCUNHO: 'Rascunho',
  VALIDADO: 'Validado',
  ASSINADO: 'Assinado',
  TRANSMITINDO: 'Transmitindo',
  CONTINGENCIA: 'Contingência',
  AUTORIZADO: 'Autorizado',
  REJEITADO: 'Rejeitado',
  DENEGADO: 'Denegado',
  CANCELADO: 'Cancelado',
  NAO_EMITIDO: 'Não emitido',
}

function classeBadge(situacao: string): string {
  if (situacao === 'AUTORIZADO') return 'badge badge-sucesso'
  if (situacao === 'CONTINGENCIA' || situacao === 'NAO_EMITIDO') return 'badge badge-aviso'
  if (situacao === 'REJEITADO' || situacao === 'DENEGADO') return 'badge badge-perigo'
  if (situacao === 'CANCELADO') return 'badge badge-inativo'
  // ⚠️ Nota PRESA numa transmissão que não terminou (achado de auditoria, 2026-08-21). Caía no
  // cinza neutro do fim — o mesmo de RASCUNHO e VALIDADO —, e é o estado mais grave da lista: a
  // venda foi feita, a nota saiu do sistema e ninguém sabe se a SEFAZ recebeu. Pior em
  // contingência, onde o job de dreno não recupera a nota (pendência 5) e o prazo legal corre.
  // É a mesma dupla que `SITUACOES_REPROCESSAVEIS` já trata como presa, aqui só faltava a cor.
  if (situacao === 'TRANSMITINDO' || situacao === 'ASSINADO') return 'badge badge-perigo'
  return 'badge'
}

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

function formatarDataHora(iso: string): string {
  return new Date(iso).toLocaleString('pt-BR', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' })
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
 * Documentos Fiscais (§12, bloco B8) — lista das NFC-e/NF-e emitidas, com filtros, ver XML e
 * consultar a situação atual direto na SEFAZ. ADMIN-only, mesmo padrão do resto do módulo fiscal
 * (seletor de empresa no topo, filtros inline — não popup, mesmo desvio de Pesquisa de Vendas,
 * que também é uma tela de consulta pura).
 */
export default function DocumentoFiscalLista() {
  // ⛔ Consultar SEFAZ e Reprocessar sao POST — o interceptor deriva INCLUIR em
  // `fiscal.documentos`, e a permissao e concedivel (auditoria 2026-08-29, rodada 4). Sem isto o
  // operador com so "acessar" clicava em Reprocessar numa nota travada e levava 403.
  const acoes = usePermissaoDaTela('fiscal.documentos')
  const queryClient = useQueryClient()
  const { idEmpresa: idEmpresaSessao } = useAuth()
  const [idEmpresa, setIdEmpresa] = useState<number | null>(null)
  const [dataInicialTexto, setDataInicialTexto] = useState(isoParaData(primeiroDiaDoMesISO()))
  const [dataFinalTexto, setDataFinalTexto] = useState(isoParaData(hojeISO()))
  const [modelo, setModelo] = useState<'' | '65' | '55'>('')
  const [situacao, setSituacao] = useState('')
  const [pagina, setPagina] = useState(1)
  const [xmlAberto, setXmlAberto] = useState<DocumentoFiscalItem | null>(null)
  const [idVendaDanfce, setIdVendaDanfce] = useState<number | null>(null)
  const [idDocumentoDanfe, setIdDocumentoDanfe] = useState<number | null>(null)
  const [consultando, setConsultando] = useState<number | null>(null)
  const [reprocessando, setReprocessando] = useState<number | null>(null)
  const [aviso, setAviso] = useState<{ mensagem: string; tipo: 'erro' | 'sucesso' } | null>(null)

  const { data: empresas } = useQuery({ queryKey: ['fiscal-empresas'], queryFn: listarEmpresasFiscal })

  useEffect(() => {
    if (idEmpresa === null && idEmpresaSessao !== null) setIdEmpresa(idEmpresaSessao)
    else if (idEmpresa === null && empresas && empresas.length > 0) setIdEmpresa(empresas[0].idEmpresa)
  }, [idEmpresa, idEmpresaSessao, empresas])

  useEffect(() => {
    setPagina(1)
  }, [idEmpresa, dataInicialTexto, dataFinalTexto, modelo, situacao])

  const dataInicialIso = dataValida(dataInicialTexto) ? dataParaIso(dataInicialTexto) : null
  const dataFinalIso = dataValida(dataFinalTexto) ? dataParaIso(dataFinalTexto) : null
  const podeBuscar = idEmpresa !== null && !!dataInicialIso && !!dataFinalIso

  const { data, isLoading, isFetching, error } = useQuery({
    queryKey: ['documentos-fiscais', idEmpresa, dataInicialIso, dataFinalIso, modelo, situacao, pagina],
    queryFn: () =>
      listarDocumentosFiscais({
        idEmpresa: idEmpresa as number,
        dataInicial: dataInicialIso as string,
        dataFinal: dataFinalIso as string,
        modelo: modelo ? Number(modelo) : undefined,
        situacao: situacao || undefined,
        pagina,
        limite: TAMANHO_PAGINA,
      }),
    enabled: podeBuscar,
    placeholderData: (anterior) => anterior,
  })

  const itens = data?.itens ?? []
  const totalPaginas = data?.totalPaginas ?? 1

  async function abrirXml(item: DocumentoFiscalItem) {
    setXmlAberto(item)
  }

  async function consultarSefaz(item: DocumentoFiscalItem) {
    setConsultando(item.idDocumentoFiscal)
    try {
      const resultado = await consultarDocumentoNaSefaz(item.idDocumentoFiscal)
      setAviso({
        mensagem: `SEFAZ agora: ${resultado.cStat ?? '—'} — ${resultado.xMotivo ?? 'sem resposta'}`,
        tipo: resultado.cStat === '100' || resultado.cStat === '135' ? 'sucesso' : 'erro',
      })
    } catch (e) {
      setAviso({ mensagem: e instanceof ApiError ? e.message : 'Não foi possível consultar a SEFAZ.', tipo: 'erro' })
    } finally {
      setConsultando(null)
    }
  }

  async function reprocessar(item: DocumentoFiscalItem) {
    setReprocessando(item.idDocumentoFiscal)
    try {
      const resultado = await reprocessarDocumentoFiscal(item.idDocumentoFiscal)
      setAviso({
        mensagem: resultado.mensagem,
        tipo: resultado.situacao === 'AUTORIZADO' ? 'sucesso' : 'erro',
      })
      queryClient.invalidateQueries({ queryKey: ['documentos-fiscais'] })
    } catch (e) {
      setAviso({ mensagem: e instanceof ApiError ? e.message : 'Não foi possível reprocessar este documento.', tipo: 'erro' })
    } finally {
      setReprocessando(null)
    }
  }

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeDocumentoFiscal size={34} />
            <h1>Documentos Fiscais</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="fiscal.documentos.tela" />
            <BotaoFecharTela />
          </div>
        </div>

        <div className="card filtros-bar">
          <select
            autoFocus
            value={idEmpresa ?? ''}
            onChange={(e) => setIdEmpresa(Number(e.target.value))}
            aria-label="Empresa"
          >
            {(empresas ?? []).map((emp) => (
              <option key={emp.idEmpresa} value={emp.idEmpresa}>
                {emp.razaoSocial}
              </option>
            ))}
          </select>
          <input
            className="mono"
            placeholder="dd/mm/aaaa"
            value={dataInicialTexto}
            onChange={(e) => setDataInicialTexto(mascararData(e.target.value))}
            onFocus={(e) => e.target.select()}
            aria-label="Data inicial"
            style={{ maxWidth: 130 }}
          />
          <input
            className="mono"
            placeholder="dd/mm/aaaa"
            value={dataFinalTexto}
            onChange={(e) => setDataFinalTexto(mascararData(e.target.value))}
            onFocus={(e) => e.target.select()}
            aria-label="Data final"
            style={{ maxWidth: 130 }}
          />
          <select value={modelo} onChange={(e) => setModelo(e.target.value as '' | '65' | '55')} aria-label="Modelo">
            <option value="">Todos os modelos</option>
            <option value="65">NFC-e (65)</option>
            <option value="55">NF-e (55)</option>
          </select>
          <select value={situacao} onChange={(e) => setSituacao(e.target.value)} aria-label="Situação">
            <option value="">Todas as situações</option>
            {Object.entries(ROTULO_SITUACAO).map(([valor, rotulo]) => (
              <option key={valor} value={valor}>
                {rotulo}
              </option>
            ))}
          </select>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card table-wrap">
          {!podeBuscar ? (
            <p className="muted">Informe a data inicial e final.</p>
          ) : isLoading ? (
            <p className="muted">Carregando…</p>
          ) : error ? (
            <p className="erro">{error instanceof ApiError ? error.message : 'Não foi possível buscar os documentos.'}</p>
          ) : itens.length === 0 ? (
            <p className="muted">Nenhum documento fiscal encontrado para os filtros informados.</p>
          ) : (
            <table className="table table-compacta">
              <thead>
                <tr>
                  <th>Modelo</th>
                  <th>Número</th>
                  <th>Chave de Acesso</th>
                  <th>Situação</th>
                  <th>Emissão</th>
                  <th>Cliente</th>
                  <th>Valor</th>
                  <th>Ações</th>
                </tr>
              </thead>
              <tbody>
                {itens.map((item) => (
                  <tr key={item.idDocumentoFiscal}>
                    <td>{item.modelo === 65 ? 'NFC-e' : 'NF-e'}</td>
                    <td className="mono">
                      {item.numero === null ? '—' : `${item.serie}/${item.numero}`}
                      {item.tipoEmissao === 9 && <span className="badge badge-aviso" style={{ marginLeft: 6 }}>Conting.</span>}
                    </td>
                    <td className="mono" title={item.chaveAcesso ?? undefined}>
                      {item.chaveAcesso === null ? '—' : `…${item.chaveAcesso.slice(-8)}`}
                    </td>
                    <td>
                      <span className={classeBadge(item.situacao)}>{ROTULO_SITUACAO[item.situacao] ?? item.situacao}</span>
                    </td>
                    <td>{formatarDataHora(item.dataEmissao)}</td>
                    <td>{item.nomeCliente ?? '—'}</td>
                    <td className="mono">{moeda(item.valorTotal)}</td>
                    <td>
                      <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
                        {/* Sem chave de acesso o documento nunca foi transmitido (parou no
                            bloqueio preventivo F11): não há XML para ver nem o que consultar na
                            SEFAZ. Oferecer a ação e deixá-la falhar é pior que não oferecer. */}
                        {item.chaveAcesso !== null && (
                          <button
                            type="button"
                            className="acao-icone"
                            title="Ver XML"
                            aria-label="Ver XML"
                            onClick={() => abrirXml(item)}
                          >
                            <IconeOlho size={18} />
                          </button>
                        )}
                        {item.modelo === 65 && item.idVenda !== null && SITUACOES_COM_DANFCE.has(item.situacao) && (
                          <button
                            type="button"
                            className="acao-icone"
                            title="Ver DANFCE"
                            aria-label="Ver DANFCE"
                            onClick={() => setIdVendaDanfce(item.idVenda)}
                          >
                            <IconeRecibo size={18} />
                          </button>
                        )}
                        {/* O DANFE da NF-e (modelo 55) é outro documento e outra folha: A4, não a
                            bobina de 80mm do DANFCE. Daí a ação separada, e não o mesmo botão
                            decidindo por dentro — o operador vê pelo ícone o que vai sair. */}
                        {item.modelo === 55 && SITUACOES_COM_DANFCE.has(item.situacao) && (
                          <button
                            type="button"
                            className="acao-icone"
                            title="Ver DANFE"
                            aria-label="Ver DANFE"
                            onClick={() => setIdDocumentoDanfe(item.idDocumentoFiscal)}
                          >
                            <IconeDocumentoFiscal size={18} />
                          </button>
                        )}
                        {item.urlConsultaPublica && (
                          <a
                            className="acao-icone"
                            title="Consulta pública (SEFAZ)"
                            aria-label="Consulta pública (SEFAZ)"
                            href={item.urlConsultaPublica}
                            target="_blank"
                            rel="noreferrer"
                          >
                            <IconeLinkExterno size={18} />
                          </a>
                        )}
                        {item.chaveAcesso !== null && acoes.incluir && (
                          <button
                            type="button"
                            className="btn ghost"
                            disabled={consultando === item.idDocumentoFiscal}
                            onClick={() => consultarSefaz(item)}
                          >
                            {consultando === item.idDocumentoFiscal ? 'Consultando…' : 'Consultar SEFAZ'}
                          </button>
                        )}
                        {SITUACOES_REPROCESSAVEIS.has(item.situacao) && acoes.incluir && (
                          <button
                            type="button"
                            className="btn ghost"
                            disabled={reprocessando === item.idDocumentoFiscal}
                            onClick={() => reprocessar(item)}
                          >
                            {reprocessando === item.idDocumentoFiscal ? 'Reprocessando…' : 'Reprocessar'}
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      {itens.length > 0 && (
        <div className="lista-rodape">
          <div className="paginacao-bar">
            <span className="muted">
              {data?.totalItens} documento{data?.totalItens === 1 ? '' : 's'}
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

      {xmlAberto && <XmlModal item={xmlAberto} aoFechar={() => setXmlAberto(null)} />}

      {idVendaDanfce !== null && (
        <ComprovantePapeletaModal idVenda={idVendaDanfce} reimpressao aoFechar={() => setIdVendaDanfce(null)} />
      )}

      {idDocumentoDanfe !== null && (
        <DanfeModal idDocumentoFiscal={idDocumentoDanfe} aoFechar={() => setIdDocumentoDanfe(null)} />
      )}

      {aviso && <Toast mensagem={aviso.mensagem} tipo={aviso.tipo} aoFechar={() => setAviso(null)} />}
    </div>
  )
}

function XmlModal({ item, aoFechar }: { item: DocumentoFiscalItem; aoFechar: () => void }) {
  const { data, isLoading, error } = useQuery({
    queryKey: ['documento-fiscal-xml', item.idDocumentoFiscal],
    queryFn: () => buscarXmlDocumentoFiscal(item.idDocumentoFiscal),
  })

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div
        className="modal modal-largo"
        role="dialog"
        aria-label="XML do documento fiscal"
        onClick={(e) => e.stopPropagation()}
        style={{ display: 'flex', flexDirection: 'column', overflow: 'hidden' }}
      >
        <CabecalhoModal titulo=<>XML — {item.chaveAcesso}</> aoFechar={aoFechar} />
        <div style={{ overflow: 'auto', flex: 1, minHeight: 0 }}>
          {isLoading ? (
            <p className="muted">Carregando…</p>
          ) : error ? (
            <p className="erro">Não foi possível carregar o XML.</p>
          ) : !data?.xml ? (
            <p className="muted">Este documento ainda não tem XML gravado.</p>
          ) : (
            <pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all', fontSize: 12 }}>{data.xml}</pre>
          )}
        </div>
        <div className="ajuda-rodape" style={{ flexShrink: 0 }}>

        </div>
      </div>
    </div>
  )
}
