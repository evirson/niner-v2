import CabecalhoModal from '../../components/CabecalhoModal'
import { useQuery } from '@tanstack/react-query'
import { aguardarPintura } from '../../lib/temaClaroParaCaptura'
import { useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import EmpresaMultiSelect from '../../components/EmpresaMultiSelect'
import { IconeRelatorio } from '../../components/Icones'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { listarEmpresas } from '../../lib/empresas'
import { useEu } from '../../lib/eu'
import { gerarLucratividade, type FiltrosLucratividade } from '../../lib/lucratividade'
import { dataParaIso, dataValida, formatarMoeda, mascararData } from '../../lib/masks'
import { gerarPdfCapturaLucratividade } from '../../lib/relatorioLucratividadeCaptura'

interface FiltrosTexto {
  dataInicial: string
  dataFinal: string
  idsEmpresa: number[]
}

/** Mês corrente — o período que o lojista quer ver em 9 de cada 10 aberturas da tela. */
function filtrosIniciais(): FiltrosTexto {
  const hoje = new Date()
  const primeiro = new Date(hoje.getFullYear(), hoje.getMonth(), 1)
  const paraTexto = (d: Date) =>
    `${String(d.getDate()).padStart(2, '0')}/${String(d.getMonth() + 1).padStart(2, '0')}/${d.getFullYear()}`
  return { dataInicial: paraTexto(primeiro), dataFinal: paraTexto(hoje), idsEmpresa: [] }
}

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

/**
 * ⚠️ Percentual `null` imprime `—`, **nunca `0%`**. Um zero ali afirmaria "margem zero" onde na
 * verdade não houve venda nenhuma — e o lojista leria isso como resultado.
 */
function percentual(v: number | null): string {
  return v == null ? '—' : `${formatarMoeda(v)}%`
}

function corDoResultado(v: number): string {
  return v >= 0 ? 'var(--sucesso)' : 'var(--danger)'
}

/**
 * Relatório de Lucratividade (docs/telas/relatorio-lucratividade.md). **ADMIN-only** (a API devolve
 * 403 para os demais papéis; o item também não aparece no menu).
 *
 * <p>Segue o padrão de tela de relatório do projeto — popup de filtros obrigatório ao entrar,
 * cabeçalho/rodapé fixos com só o corpo rolando. Diferente da DRE, **não** tem regime nem
 * comparação: a leitura é uma página só, do faturamento ao lucro líquido.
 */
export default function RelatorioLucratividade() {
  const navigate = useNavigate()
  const [aplicado, setAplicado] = useState<FiltrosTexto>(filtrosIniciais)
  const [rascunho, setRascunho] = useState<FiltrosTexto>(filtrosIniciais)
  const [modalAberto, setModalAberto] = useState(true)
  const [relatorioGerado, setRelatorioGerado] = useState(false)
  const [erroFiltros, setErroFiltros] = useState<string | null>(null)
  const [toast, setToast] = useState<string | null>(null)
  const [gerandoPdf, setGerandoPdf] = useState(false)
  const conteudoRef = useRef<HTMLDivElement>(null)

  // A tela é ADMIN-only (não aparece no menu pros demais papéis), então lista todas as empresas.
  const { data: empresas } = useQuery({ queryKey: ['empresas'], queryFn: listarEmpresas })
  const { data: eu } = useEu()

  const filtros: FiltrosLucratividade = {
    dataInicial: dataParaIso(aplicado.dataInicial) ?? '',
    dataFinal: dataParaIso(aplicado.dataFinal) ?? '',
    idsEmpresa: aplicado.idsEmpresa,
  }

  const { data, isFetching, error } = useQuery({
    queryKey: ['relatorio-lucratividade', filtros],
    queryFn: () => gerarLucratividade(filtros),
    enabled: relatorioGerado,
    placeholderData: (anterior) => anterior,
  })

  function gerar() {
    if (!dataValida(rascunho.dataInicial) || !dataValida(rascunho.dataFinal)) {
      setErroFiltros('Informe a data inicial e a data final.')
      return
    }
    setErroFiltros(null)
    setAplicado(rascunho)
    setRelatorioGerado(true)
    setModalAberto(false)
  }

  function textoEmpresasFiltradas(): string {
    if (aplicado.idsEmpresa.length === 0) return 'Todas as empresas'
    return (empresas ?? [])
      .filter((e) => aplicado.idsEmpresa.includes(e.idEmpresa))
      .map((e) => e.nomeFantasia ?? e.razaoSocial)
      .join(', ') || 'Todas as empresas'
  }

  /** Dois frames antes de capturar: o React precisa ter pintado os blocos que só existem no PDF
   *  (filtros aplicados) antes do html2canvas ler o DOM. */

  const handleGerarPdf = async () => {
    if (!conteudoRef.current) return
    setGerandoPdf(true)
    try {
      await aguardarPintura()
      await gerarPdfCapturaLucratividade(
        conteudoRef.current,
        `${aplicado.dataInicial} a ${aplicado.dataFinal}`,
        eu?.empresa?.nome ?? '—')
    } catch (e) {
      setToast(e instanceof Error ? e.message : 'Não foi possível gerar o PDF.')
    } finally {
      setGerandoPdf(false)
    }
  }

  const rotuloFiltro = `${aplicado.dataInicial} a ${aplicado.dataFinal} · ${textoEmpresasFiltradas()}`

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeRelatorio size={34} />
            <h1>Relatório de Lucratividade</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="relatorios.lucratividade" />
            {relatorioGerado && (
              <button type="button" className="btn ghost" onClick={() => { setRascunho(aplicado); setModalAberto(true) }}>
                Filtros
              </button>
            )}
            {data && (
              <button type="button" className="btn ghost" disabled={gerandoPdf} onClick={handleGerarPdf}>
                {gerandoPdf ? 'Gerando PDF…' : 'Gerar PDF'}
              </button>
            )}
            <BotaoFecharTela />
          </div>
        </div>

        {toast && <Toast mensagem={toast} tipo="erro" aoFechar={() => setToast(null)} />}

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
        {error && (
          <p className="erro-campo">
            {error instanceof ApiError ? error.message : 'Não foi possível gerar o relatório.'}
          </p>
        )}
        {isFetching && !data && <p className="muted">Apurando…</p>}
        {!relatorioGerado && !modalAberto && <p className="muted">Use o botão Filtros para gerar o relatório.</p>}
        {data && (
          <div ref={conteudoRef} className={`relatorio-conteudo${gerandoPdf ? ' pdf-expandido' : ''}`}>
            {/* Só no PDF: na tela esses dados já estão na barra de filtros do topo, que não é
                capturada (fica fora do `.relatorio-conteudo`). */}
            {gerandoPdf && (
              <>
                <p className="section-label">Filtros Aplicados</p>
                <div className="card relatorio-filtros-aplicados">
                  <span>
                    <span className="muted">Período: </span>
                    <strong>{aplicado.dataInicial} a {aplicado.dataFinal}</strong>
                  </span>
                  <span>
                    <span className="muted">Empresa(s): </span>
                    <strong>{textoEmpresasFiltradas()}</strong>
                  </span>
                </div>
              </>
            )}

            <p className="section-label" style={{ marginTop: gerandoPdf ? 24 : 0 }}>Resultado do Período</p>
            {/* ⚠️ `secao-fixa` (2026-08-31): esta tabela tem SEMPRE 5 linhas, e sem ela a regra
                `.relatorio-corpo-fixo .table-wrap { flex: 1 }` repartia a altura igualmente entre as
                três seções — a primeira ficava com 134px para 159px de conteúdo e **escondia a linha
                do Lucro Bruto**, a que dá nome ao relatório, atrás de uma barra de rolagem de 25px
                que ninguém procura. ⭐ Só a seção de Despesas cresce/rola, porque só ela tem número
                variável de linhas (uma por conta do plano).
                ⚠️ E o PDF saía CERTO (`pdf-expandido` desfaz o flex), o que é justamente o motivo de
                isto ter sobrevivido: quem conferiu pelo PDF viu o relatório inteiro. */}
            <div className={`card table-wrap secao-fixa${gerandoPdf ? ' pdf-expandido' : ''}`}>
              <table className="table table-compacta dre-tabela">
                <tbody>
                  <tr className="dre-linha dre-linha-grupo">
                    <td>Valor total da venda</td>
                    <td style={{ textAlign: 'right' }}>{moeda(data.vendaLiquida)}</td>
                    <td style={{ textAlign: 'right' }} className="muted">—</td>
                  </tr>
                  {/* A composição só aparece quando houve devolução — senão é uma linha de zero
                      ocupando espaço e sugerindo problema onde não há. */}
                  {data.devolucoes !== 0 && (
                    <>
                      <tr className="dre-linha dre-linha-conta">
                        <td style={{ paddingLeft: 28 }} className="muted">Vendas do período</td>
                        <td style={{ textAlign: 'right' }} className="muted">{moeda(data.vendaBruta)}</td>
                        <td />
                      </tr>
                      <tr className="dre-linha dre-linha-conta">
                        <td style={{ paddingLeft: 28 }} className="muted">(−) Devoluções</td>
                        <td style={{ textAlign: 'right' }} className="muted">{moeda(-data.devolucoes)}</td>
                        <td />
                      </tr>
                    </>
                  )}
                  <tr className="dre-linha dre-linha-grupo">
                    <td>(−) Custo das mercadorias vendidas</td>
                    <td style={{ textAlign: 'right' }}>{moeda(-data.custoMercadoriaVendida)}</td>
                    <td style={{ textAlign: 'right' }} className="muted">—</td>
                  </tr>
                  <tr className="dre-linha dre-linha-subtotal">
                    <td>= Lucro bruto</td>
                    <td style={{ textAlign: 'right', color: corDoResultado(data.lucroBruto) }}>
                      {moeda(data.lucroBruto)}
                    </td>
                    <td style={{ textAlign: 'right' }}>{percentual(data.percentualLucroBruto)}</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <p className="section-label" style={{ marginTop: 24 }}>Despesas do Período</p>
            <div className={`card table-wrap${gerandoPdf ? ' pdf-expandido' : ''}`}>
              <table className="table table-compacta dre-tabela">
                <thead>
                  <tr>
                    <th>Plano de contas</th>
                    <th style={{ textAlign: 'right' }}>Valor</th>
                    <th style={{ textAlign: 'right' }} title="Percentual sobre o valor total da venda">
                      % s/ venda
                    </th>
                    <th style={{ textAlign: 'right' }} title="Percentual sobre o lucro bruto">
                      % s/ lucro bruto
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {data.despesas.length === 0 && (
                    <tr>
                      <td colSpan={4} className="muted">Nenhuma despesa no período.</td>
                    </tr>
                  )}
                  {data.despesas.map((conta) => (
                    <tr key={conta.idPlanoContas} className="dre-linha dre-linha-conta">
                      <td>
                        {conta.idPlanoContas} — {conta.descricao}
                        {/* ⚠️ A marca não é enfeite: linha derivada conta pela data da VENDA, e as
                            demais pela data de PAGAMENTO. Sem dizer qual é qual, a tabela mistura
                            duas bases de data em silêncio. */}
                        {conta.derivada && (
                          <span className="muted" title="Calculada do movimento das vendas — não existe conta a pagar para ela, então conta pela data da venda">
                            {' '}(calculado)
                          </span>
                        )}
                      </td>
                      <td style={{ textAlign: 'right' }}>{moeda(conta.valor)}</td>
                      <td style={{ textAlign: 'right' }} className="muted">
                        {percentual(conta.percentualSobreVenda)}
                      </td>
                      <td style={{ textAlign: 'right' }} className="muted">
                        {percentual(conta.percentualSobreLucroBruto)}
                      </td>
                    </tr>
                  ))}
                  <tr className="dre-linha dre-linha-subtotal">
                    <td>Total das despesas</td>
                    <td style={{ textAlign: 'right' }}>{moeda(data.totalDespesas)}</td>
                    <td colSpan={2} />
                  </tr>
                </tbody>
              </table>
            </div>

            <p className="section-label" style={{ marginTop: 24 }}>Lucro Líquido</p>
            {/* Fixa pelo mesmo motivo: 3 linhas, sempre. */}
            <div className={`card table-wrap secao-fixa${gerandoPdf ? ' pdf-expandido' : ''}`}>
              <table className="table table-compacta dre-tabela">
                <tbody>
                  <tr className="dre-linha dre-linha-subtotal">
                    <td>= Lucro líquido</td>
                    <td style={{ textAlign: 'right', color: corDoResultado(data.lucroLiquido) }}>
                      <strong>{moeda(data.lucroLiquido)}</strong>
                    </td>
                  </tr>
                  <tr className="dre-linha dre-linha-conta">
                    <td style={{ paddingLeft: 28 }} className="muted">
                      % sobre a venda bruta ({moeda(data.vendaBruta)})
                    </td>
                    <td style={{ textAlign: 'right' }}>{percentual(data.percentualSobreVendaBruta)}</td>
                  </tr>
                  <tr className="dre-linha dre-linha-conta">
                    <td style={{ paddingLeft: 28 }} className="muted">
                      % sobre a venda líquida ({moeda(data.vendaLiquida)})
                    </td>
                    <td style={{ textAlign: 'right' }}>{percentual(data.percentualSobreVendaLiquida)}</td>
                  </tr>
                </tbody>
              </table>
            </div>

            {/* ⚠️ O aviso do regime misto fica no CORPO, não só na ajuda: é a primeira coisa que
                explica um número que parece errado, e precisa viajar junto no PDF impresso. */}
            <p className="muted" style={{ marginTop: 16, fontSize: '0.85em' }}>
              A venda e o custo contam pela <strong>data da venda</strong>; as contas pagas, pela{' '}
              <strong>data de pagamento</strong> — uma conta de um mês paga no mês seguinte pesa no mês
              em que foi paga. As linhas marcadas <strong>(calculado)</strong> — comissão e taxa de
              cartão — não têm conta a pagar: saem do movimento das vendas e contam pela data da venda.
              Compra de mercadoria não aparece aqui: ela já está no custo das mercadorias vendidas.
              {!gerandoPdf && (
                <> Para o resultado pelo fato gerador, use a <Link to="/relatorio-dre">DRE em competência</Link>.</>
              )}
            </p>
          </div>
        )}
      </div>

      {data && (
        <div className="lista-rodape">
          <span className="muted">
            Venda {moeda(data.vendaLiquida)} · Lucro bruto {moeda(data.lucroBruto)} · Lucro líquido{' '}
            <strong style={{ color: corDoResultado(data.lucroLiquido) }}>{moeda(data.lucroLiquido)}</strong>
          </span>
        </div>
      )}

      {modalAberto && (
        <div className="modal-overlay">
          <div className="modal" role="dialog" aria-label="Filtros da Lucratividade" onClick={(e) => e.stopPropagation()}>
            <CabecalhoModal titulo="Relatório de Lucratividade" aoFechar={() => (relatorioGerado ? setModalAberto(false) : navigate(-1))} />
            <p className="muted" style={{ marginTop: 4 }}>
              A venda conta pela <strong>data da venda</strong>; as contas pagas, pela{' '}
              <strong>data de pagamento</strong>.
            </p>

            <div className="form-grid" style={{ marginTop: 12 }}>
              <div className="col-6">
                <label htmlFor="lucro-data-inicial">Data Inicial *</label>
                <input
                  id="lucro-data-inicial"
                  className="mono"
                  autoFocus
                  placeholder="dd/mm/aaaa"
                  value={rascunho.dataInicial}
                  onChange={(e) => setRascunho((f) => ({ ...f, dataInicial: mascararData(e.target.value) }))}
                  onFocus={(e) => e.target.select()}
                />
              </div>
              <div className="col-6">
                <label htmlFor="lucro-data-final">Data Final *</label>
                <input
                  id="lucro-data-final"
                  className="mono"
                  placeholder="dd/mm/aaaa"
                  value={rascunho.dataFinal}
                  onChange={(e) => setRascunho((f) => ({ ...f, dataFinal: mascararData(e.target.value) }))}
                  onFocus={(e) => e.target.select()}
                />
              </div>

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
                Gerar Relatório
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
