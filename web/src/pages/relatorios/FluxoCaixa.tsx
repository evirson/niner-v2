import CabecalhoModal from '../../components/CabecalhoModal'
import { useQuery } from '@tanstack/react-query'
import { Fragment, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import EmpresaMultiSelect from '../../components/EmpresaMultiSelect'
import { IconeContaCorrente } from '../../components/Icones'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { listarEmpresas } from '../../lib/empresas'
import { useEu } from '../../lib/eu'
import {
  gerarFluxoProjecao,
  gerarFluxoRealizado,
  type AgrupamentoProjecao,
  type OrigemDinheiro,
} from '../../lib/fluxoCaixa'
import { dataParaIso, dataValida, formatarMoeda, mascararData } from '../../lib/masks'
import { gerarPdfCapturaFluxoCaixa } from '../../lib/fluxoCaixaCaptura'

type Aba = 'REALIZADO' | 'PROJECAO'

const ROTULO_ORIGEM: Record<OrigemDinheiro, string> = {
  TODAS: 'Caixa e conta corrente',
  CAIXA: 'Somente caixa',
  CONTA_CORRENTE: 'Somente conta corrente',
}

interface FiltrosTexto {
  dataInicial: string
  dataFinal: string
  idsEmpresa: number[]
  origem: OrigemDinheiro
  agrupamento: AgrupamentoProjecao
}

function paraTexto(d: Date): string {
  return `${String(d.getDate()).padStart(2, '0')}/${String(d.getMonth() + 1).padStart(2, '0')}/${d.getFullYear()}`
}

/** Realizado abre no mês corrente; projeção, nos próximos 90 dias — os dois períodos que o
 *  lojista quer ver ao entrar, e que a spec fixou como padrão de cada aba. */
function filtrosIniciais(aba: Aba): FiltrosTexto {
  const hoje = new Date()
  const inicio = aba === 'REALIZADO' ? new Date(hoje.getFullYear(), hoje.getMonth(), 1) : hoje
  const fim = aba === 'REALIZADO' ? hoje : new Date(hoje.getFullYear(), hoje.getMonth(), hoje.getDate() + 90)
  return {
    dataInicial: paraTexto(inicio),
    dataFinal: paraTexto(fim),
    idsEmpresa: [],
    origem: 'TODAS',
    agrupamento: 'DIA',
  }
}

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

function corDoValor(v: number): string | undefined {
  if (v === 0) return undefined
  return v > 0 ? 'var(--sucesso)' : 'var(--danger)'
}

/**
 * Fluxo de Caixa (docs/telas/fluxo-caixa.md) — duas abas na mesma tela: **Realizado** (o que
 * aconteceu, por atividade, lendo só movimento de dinheiro) e **Projeção** (o que vem, a partir de
 * contas a receber/pagar em aberto). Aberto a qualquer papel; OPERADOR fica restrito à empresa
 * ativa da sessão pelo próprio serviço.
 *
 * A conciliação do Realizado (saldo calculado × saldo real de hoje) é o que dá confiança no
 * número — é a primeira coisa que o lojista confere, e por isso fica no rodapé fixo, sempre
 * visível, não escondida no fim da tabela.
 */
export default function FluxoCaixa() {
  const navigate = useNavigate()
  const [aba, setAba] = useState<Aba>('REALIZADO')
  const [aplicado, setAplicado] = useState<FiltrosTexto>(() => filtrosIniciais('REALIZADO'))
  const [rascunho, setRascunho] = useState<FiltrosTexto>(() => filtrosIniciais('REALIZADO'))
  const [modalAberto, setModalAberto] = useState(true)
  const [gerado, setGerado] = useState(false)
  const [erroFiltros, setErroFiltros] = useState<string | null>(null)
  const [toast, setToast] = useState<string | null>(null)
  const [gerandoPdf, setGerandoPdf] = useState(false)
  const conteudoRef = useRef<HTMLDivElement>(null)

  const { data: empresas } = useQuery({ queryKey: ['empresas'], queryFn: listarEmpresas })
  const { data: eu } = useEu()

  const dataInicialIso = dataParaIso(aplicado.dataInicial) ?? ''
  const dataFinalIso = dataParaIso(aplicado.dataFinal) ?? ''

  const realizado = useQuery({
    queryKey: ['fluxo-caixa-realizado', dataInicialIso, dataFinalIso, aplicado.idsEmpresa, aplicado.origem],
    queryFn: () =>
      gerarFluxoRealizado({
        dataInicial: dataInicialIso,
        dataFinal: dataFinalIso,
        idsEmpresa: aplicado.idsEmpresa,
        origem: aplicado.origem,
      }),
    enabled: gerado && aba === 'REALIZADO',
    placeholderData: (anterior) => anterior,
  })

  const projecao = useQuery({
    queryKey: ['fluxo-caixa-projecao', dataInicialIso, dataFinalIso, aplicado.idsEmpresa, aplicado.agrupamento],
    queryFn: () =>
      gerarFluxoProjecao({
        dataInicial: dataInicialIso,
        dataFinal: dataFinalIso,
        idsEmpresa: aplicado.idsEmpresa,
        agrupamento: aplicado.agrupamento,
      }),
    enabled: gerado && aba === 'PROJECAO',
    placeholderData: (anterior) => anterior,
  })

  const consulta = aba === 'REALIZADO' ? realizado : projecao
  const temDados = aba === 'REALIZADO' ? Boolean(realizado.data) : Boolean(projecao.data)

  /** Trocar de aba troca o período padrão (mês corrente × próximos 90 dias) — mas só quando o
   *  usuário ainda não mexeu nos filtros, pra não jogar fora uma escolha dele. */
  function trocarAba(nova: Aba) {
    setAba(nova)
    if (!gerado) {
      setAplicado(filtrosIniciais(nova))
      setRascunho(filtrosIniciais(nova))
    }
  }

  function gerar() {
    if (!dataValida(rascunho.dataInicial) || !dataValida(rascunho.dataFinal)) {
      setErroFiltros('Informe a data inicial e a data final.')
      return
    }
    setErroFiltros(null)
    setAplicado(rascunho)
    setGerado(true)
    setModalAberto(false)
  }

  function aguardarPintura(): Promise<void> {
    return new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
  }

  const handleGerarPdf = async () => {
    if (!conteudoRef.current) return
    setGerandoPdf(true)
    try {
      await aguardarPintura()
      const subtitulo = `${aba === 'REALIZADO' ? 'Realizado' : 'Projeção'} · ${aplicado.dataInicial} a ${aplicado.dataFinal}`
      await gerarPdfCapturaFluxoCaixa(conteudoRef.current, subtitulo, eu?.empresa?.nome ?? '—')
    } catch (e) {
      setToast(e instanceof Error ? e.message : 'Não foi possível gerar o PDF.')
    } finally {
      setGerandoPdf(false)
    }
  }

  const rotuloFiltro =
    `${aplicado.dataInicial} a ${aplicado.dataFinal}` +
    (aba === 'REALIZADO' ? ` · ${ROTULO_ORIGEM[aplicado.origem]}` : '')

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeContaCorrente size={34} />
            <h1>Fluxo de Caixa</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="relatorios.fluxocaixa" />
            {gerado && (
              <button type="button" className="btn ghost" onClick={() => { setRascunho(aplicado); setModalAberto(true) }}>
                Filtros
              </button>
            )}
            {temDados && (
              <button type="button" className="btn ghost" disabled={gerandoPdf} onClick={handleGerarPdf}>
                {gerandoPdf ? 'Gerando PDF…' : 'Gerar PDF'}
              </button>
            )}
            <BotaoFecharTela />
          </div>
        </div>

        {toast && <Toast mensagem={toast} tipo="erro" aoFechar={() => setToast(null)} />}

        <div className="abas-fluxo">
          <button
            type="button"
            className={`aba-fluxo${aba === 'REALIZADO' ? ' aba-fluxo-ativa' : ''}`}
            onClick={() => trocarAba('REALIZADO')}
          >
            Realizado
          </button>
          <button
            type="button"
            className={`aba-fluxo${aba === 'PROJECAO' ? ' aba-fluxo-ativa' : ''}`}
            onClick={() => trocarAba('PROJECAO')}
          >
            Projeção
          </button>
        </div>

        {!modalAberto && (
          <div className="card filtros-bar">
            <span className="muted">{rotuloFiltro}</span>
            <button type="button" className="btn ghost" onClick={() => { setRascunho(aplicado); setModalAberto(true) }}>
              Alterar Filtros
            </button>
          </div>
        )}
      </div>

      <div className="lista-corpo relatorio-corpo-fixo">
        {consulta.error && (
          <p className="erro-campo">
            {consulta.error instanceof ApiError ? consulta.error.message : 'Não foi possível gerar o fluxo de caixa.'}
          </p>
        )}
        {!gerado && !modalAberto && <p className="muted">Use o botão Filtros para gerar o fluxo de caixa.</p>}

        {temDados && (
          <div ref={conteudoRef} className={`relatorio-conteudo${gerandoPdf ? ' pdf-expandido' : ''}`}>
            {gerandoPdf && (
              <>
                <p className="section-label">Filtros Aplicados</p>
                <div className="card relatorio-filtros-aplicados">
                  <span>
                    <span className="muted">Visão: </span>
                    <strong>{aba === 'REALIZADO' ? 'Realizado' : 'Projeção'}</strong>
                  </span>
                  <span>
                    <span className="muted">Período: </span>
                    <strong>{aplicado.dataInicial} a {aplicado.dataFinal}</strong>
                  </span>
                  {aba === 'REALIZADO' && (
                    <span>
                      <span className="muted">Origem: </span>
                      <strong>{ROTULO_ORIGEM[aplicado.origem]}</strong>
                    </span>
                  )}
                </div>
              </>
            )}

            {aba === 'REALIZADO' && realizado.data && (
              <>
                <p className="section-label" style={{ marginTop: gerandoPdf ? 24 : 0 }}>Demonstração do Fluxo de Caixa</p>
                <div className={`card table-wrap${gerandoPdf ? ' pdf-expandido' : ''}`}>
                  <table className="table table-compacta dre-tabela">
                    <thead>
                      <tr>
                        <th>Atividade / Conta</th>
                        <th style={{ textAlign: 'right' }}>Valor</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr className="dre-linha dre-linha-subtotal">
                        <td>SALDO INICIAL DO PERÍODO</td>
                        <td style={{ textAlign: 'right' }}>{moeda(realizado.data.saldoInicial)}</td>
                      </tr>
                      {/* `Fragment` explícito com key: o atalho `<>` não aceita key, e sem ela o
                          React reclama de lista sem identidade — o `tsc` não pega isso. */}
                      {realizado.data.atividades.map((a) => (
                        <Fragment key={a.grupo}>
                          <tr className="dre-linha dre-linha-grupo">
                            <td>{a.rotulo}</td>
                            <td style={{ textAlign: 'right', color: corDoValor(a.total) }}>{moeda(a.total)}</td>
                          </tr>
                          {a.linhas.map((l) => (
                            <tr key={`${a.grupo}-${l.chave}`} className="dre-linha dre-linha-conta">
                              <td style={{ paddingLeft: 28 }}>{l.rotulo}</td>
                              <td style={{ textAlign: 'right' }}>{moeda(l.valor)}</td>
                            </tr>
                          ))}
                        </Fragment>
                      ))}
                      <tr className="dre-linha">
                        <td>Total de entradas</td>
                        <td style={{ textAlign: 'right', color: 'var(--sucesso)' }}>{moeda(realizado.data.totalEntradas)}</td>
                      </tr>
                      <tr className="dre-linha">
                        <td>Total de saídas</td>
                        <td style={{ textAlign: 'right', color: 'var(--danger)' }}>{moeda(realizado.data.totalSaidas)}</td>
                      </tr>
                      <tr className="dre-linha dre-linha-subtotal">
                        <td>= SALDO FINAL DO PERÍODO</td>
                        <td style={{ textAlign: 'right' }}>{moeda(realizado.data.saldoFinal)}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </>
            )}

            {aba === 'PROJECAO' && projecao.data && (
              <>
                {projecao.data.primeiraDataNegativa && (
                  <div className="card" style={{ borderColor: 'var(--danger)', marginBottom: 12 }}>
                    <strong style={{ color: 'var(--danger)' }}>
                      Atenção: o saldo fica negativo em{' '}
                      {projecao.data.linhas.find((l) => l.data === projecao.data!.primeiraDataNegativa)?.rotulo}
                      {projecao.data.valorFaltante != null && ` — faltam ${moeda(projecao.data.valorFaltante)}`}.
                    </strong>
                  </div>
                )}
                <p className="section-label" style={{ marginTop: gerandoPdf ? 24 : 0 }}>
                  Projeção a partir do saldo de hoje ({moeda(projecao.data.saldoAtual)})
                </p>
                <div className={`card table-wrap${gerandoPdf ? ' pdf-expandido' : ''}`}>
                  <table className="table table-compacta">
                    <thead>
                      <tr>
                        <th>Período</th>
                        <th style={{ textAlign: 'right' }}>Entradas</th>
                        <th style={{ textAlign: 'right' }}>Saídas</th>
                        <th style={{ textAlign: 'right' }}>Do período</th>
                        <th style={{ textAlign: 'right' }}>Saldo acumulado</th>
                      </tr>
                    </thead>
                    <tbody>
                      {projecao.data.linhas.length === 0 && (
                        <tr>
                          <td colSpan={5} className="muted">
                            Nenhuma conta a receber ou a pagar em aberto no período.
                          </td>
                        </tr>
                      )}
                      {projecao.data.linhas.map((l) => (
                        <tr key={l.data}>
                          <td>
                            {l.rotulo}
                            {l.emAtraso && (
                              <span className="muted" style={{ marginLeft: 6, fontSize: 12 }}>
                                (inclui vencidos)
                              </span>
                            )}
                          </td>
                          <td style={{ textAlign: 'right' }}>{moeda(l.entradas)}</td>
                          <td style={{ textAlign: 'right' }}>{moeda(l.saidas)}</td>
                          <td style={{ textAlign: 'right', color: corDoValor(l.saldoPeriodo) }}>{moeda(l.saldoPeriodo)}</td>
                          <td style={{ textAlign: 'right', color: corDoValor(l.saldoAcumulado), fontWeight: 600 }}>
                            {moeda(l.saldoAcumulado)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </>
            )}
          </div>
        )}
      </div>

      {/* Conciliação no rodapé fixo: é a informação que decide se o lojista confia no relatório. */}
      {aba === 'REALIZADO' && realizado.data && (
        <div className="lista-rodape">
          <span className="muted">
            Saldo real hoje (caixa + conta corrente): <strong>{moeda(realizado.data.saldoRealAtual)}</strong>
            {realizado.data.diferencaConciliacao != null && (
              <>
                {' · '}
                {realizado.data.diferencaConciliacao === 0 ? (
                  <strong style={{ color: 'var(--sucesso)' }}>confere com o saldo calculado</strong>
                ) : (
                  <strong style={{ color: 'var(--danger)' }}>
                    diferença de {moeda(realizado.data.diferencaConciliacao)}
                  </strong>
                )}
              </>
            )}
          </span>
        </div>
      )}
      {aba === 'PROJECAO' && projecao.data && (
        <div className="lista-rodape">
          <span className="muted">
            Entradas previstas {moeda(projecao.data.totalEntradasPrevistas)} · Saídas previstas{' '}
            {moeda(projecao.data.totalSaidasPrevistas)} · Saldo projetado ao fim:{' '}
            <strong style={{ color: corDoValor(projecao.data.saldoProjetadoFinal) }}>
              {moeda(projecao.data.saldoProjetadoFinal)}
            </strong>
          </span>
        </div>
      )}

      {modalAberto && (
        <div className="modal-overlay">
          <div className="modal" role="dialog" aria-label="Filtros do Fluxo de Caixa" onClick={(e) => e.stopPropagation()}>
            <CabecalhoModal titulo=<>Fluxo de Caixa — {aba === 'REALIZADO' ? 'Realizado' : 'Projeção'}</> aoFechar={() => (gerado ? setModalAberto(false) : navigate(-1))} />
            <p className="muted" style={{ marginTop: 4 }}>
              {aba === 'REALIZADO'
                ? 'Entradas e saídas de dinheiro que já aconteceram, por atividade.'
                : 'O que ainda vai entrar e sair, a partir das contas a receber e a pagar em aberto.'}
            </p>

            <div className="form-grid" style={{ marginTop: 12 }}>
              <div className="col-6">
                <label htmlFor="fc-data-inicial">Data Inicial *</label>
                <input
                  id="fc-data-inicial"
                  className="mono"
                  autoFocus
                  placeholder="dd/mm/aaaa"
                  value={rascunho.dataInicial}
                  onChange={(e) => setRascunho((f) => ({ ...f, dataInicial: mascararData(e.target.value) }))}
                  onFocus={(e) => e.target.select()}
                />
              </div>
              <div className="col-6">
                <label htmlFor="fc-data-final">Data Final *</label>
                <input
                  id="fc-data-final"
                  className="mono"
                  placeholder="dd/mm/aaaa"
                  value={rascunho.dataFinal}
                  onChange={(e) => setRascunho((f) => ({ ...f, dataFinal: mascararData(e.target.value) }))}
                  onFocus={(e) => e.target.select()}
                />
              </div>

              {aba === 'REALIZADO' ? (
                <div className="col-6">
                  <label htmlFor="fc-origem">Origem do dinheiro</label>
                  <select
                    id="fc-origem"
                    value={rascunho.origem}
                    onChange={(e) => setRascunho((f) => ({ ...f, origem: e.target.value as OrigemDinheiro }))}
                  >
                    <option value="TODAS">Caixa e conta corrente</option>
                    <option value="CAIXA">Somente caixa</option>
                    <option value="CONTA_CORRENTE">Somente conta corrente</option>
                  </select>
                </div>
              ) : (
                <div className="col-6">
                  <label htmlFor="fc-agrupamento">Agrupar por</label>
                  <select
                    id="fc-agrupamento"
                    value={rascunho.agrupamento}
                    onChange={(e) => setRascunho((f) => ({ ...f, agrupamento: e.target.value as AgrupamentoProjecao }))}
                  >
                    <option value="DIA">Dia</option>
                    <option value="SEMANA">Semana</option>
                    <option value="MES">Mês</option>
                  </select>
                </div>
              )}

              <div className="col-12">
                <label>Empresas</label>
                <EmpresaMultiSelect
                  empresas={empresas ?? []}
                  selecionadas={rascunho.idsEmpresa}
                  aoAlterar={(idsEmpresa) => setRascunho((f) => ({ ...f, idsEmpresa }))}
                />
              </div>
            </div>

            {erroFiltros && <p className="erro-campo">{erroFiltros}</p>}

            <div className="ajuda-rodape">

              <button type="button" className="btn" onClick={gerar}>
                Gerar
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
