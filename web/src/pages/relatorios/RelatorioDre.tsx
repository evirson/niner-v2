import CabecalhoModal from '../../components/CabecalhoModal'
import { useQuery } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import EmpresaMultiSelect from '../../components/EmpresaMultiSelect'
import { IconeRelatorio } from '../../components/Icones'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { gerarDre, type ComparacaoDre, type FiltrosDre, type LinhaDre, type RegimeDre } from '../../lib/dre'
import { listarEmpresas } from '../../lib/empresas'
import { useEu } from '../../lib/eu'
import { dataParaIso, dataValida, formatarMoeda, mascararData } from '../../lib/masks'
import { gerarPdfCapturaDre } from '../../lib/relatorioDreCaptura'

const ROTULO_REGIME: Record<RegimeDre, string> = {
  COMPETENCIA: 'Competência',
  CAIXA: 'Caixa',
}

const ROTULO_COMPARACAO: Record<ComparacaoDre, string> = {
  NENHUM: 'Sem comparação',
  PERIODO_ANTERIOR: 'Período anterior',
  ANO_ANTERIOR: 'Mesmo período do ano anterior',
}

interface FiltrosTexto {
  dataInicial: string
  dataFinal: string
  regime: RegimeDre
  idsEmpresa: number[]
  comparar: ComparacaoDre
}

/** Mês corrente — o período que o lojista quer ver em 9 de cada 10 aberturas da tela. */
function filtrosIniciais(): FiltrosTexto {
  const hoje = new Date()
  const primeiro = new Date(hoje.getFullYear(), hoje.getMonth(), 1)
  const paraTexto = (d: Date) =>
    `${String(d.getDate()).padStart(2, '0')}/${String(d.getMonth() + 1).padStart(2, '0')}/${d.getFullYear()}`
  return {
    dataInicial: paraTexto(primeiro),
    dataFinal: paraTexto(hoje),
    regime: 'COMPETENCIA',
    idsEmpresa: [],
    comparar: 'PERIODO_ANTERIOR',
  }
}

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

/**
 * Cor da variação segue o **efeito no resultado**, não o sinal aritmético: despesa que cresce é
 * vermelha mesmo com a variação sendo "maior". Como todo valor já chega com o sinal do efeito
 * (dedução/custo/despesa negativos), variação positiva sempre significa "melhorou".
 */
function corDaVariacao(variacao: number | null): string | undefined {
  if (variacao == null || variacao === 0) return undefined
  return variacao > 0 ? 'var(--sucesso)' : 'var(--danger)'
}

function classeDaLinha(linha: LinhaDre): string {
  if (linha.tipo === 'SUBTOTAL') return 'dre-linha dre-linha-subtotal'
  if (linha.tipo === 'GRUPO') return 'dre-linha dre-linha-grupo'
  return 'dre-linha dre-linha-conta'
}

/**
 * DRE — Demonstração do Resultado (docs/telas/relatorio-dre.md). **ADMIN-only** (a API devolve 403
 * para os demais papéis; o item também não aparece no menu).
 *
 * Segue o padrão de tela de relatório do projeto — popup de filtros obrigatório ao entrar,
 * cabeçalho/rodapé fixos com só a grade rolando —, mas sem KPIs e sem gráficos: a DRE **é** a
 * grade, e cada linha extra tira espaço do que importa. Ponto de equilíbrio, visão de 12 meses e
 * drill-down são Non-goals desta versão (a resposta da API já é compatível com os três).
 */
export default function RelatorioDre() {
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

  const filtros: FiltrosDre = {
    dataInicial: dataParaIso(aplicado.dataInicial) ?? '',
    dataFinal: dataParaIso(aplicado.dataFinal) ?? '',
    regime: aplicado.regime,
    idsEmpresa: aplicado.idsEmpresa,
    comparar: aplicado.comparar,
  }

  const { data, isFetching, error } = useQuery({
    queryKey: ['relatorio-dre', filtros],
    queryFn: () => gerarDre(filtros),
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
   *  (filtros aplicados, resumo) antes do html2canvas ler o DOM. */
  function aguardarPintura(): Promise<void> {
    return new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
  }

  const handleGerarPdf = async () => {
    if (!conteudoRef.current) return
    setGerandoPdf(true)
    try {
      await aguardarPintura()
      const subtitulo = `${ROTULO_REGIME[aplicado.regime]} · ${aplicado.dataInicial} a ${aplicado.dataFinal}`
      await gerarPdfCapturaDre(conteudoRef.current, subtitulo, eu?.empresa?.nome ?? '—')
    } catch (e) {
      setToast(e instanceof Error ? e.message : 'Não foi possível gerar o PDF.')
    } finally {
      setGerandoPdf(false)
    }
  }

  const temComparacao = Boolean(data?.periodoComparado)
  const rotuloFiltro = `${ROTULO_REGIME[aplicado.regime]} · ${aplicado.dataInicial} a ${aplicado.dataFinal}`
    + (aplicado.comparar === 'NENHUM' ? '' : ` · vs ${ROTULO_COMPARACAO[aplicado.comparar].toLowerCase()}`)

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeRelatorio size={34} />
            <h1>DRE — Demonstração do Resultado</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="relatorios.dre" />
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
            {error instanceof ApiError ? error.message : 'Não foi possível gerar a DRE.'}
          </p>
        )}
        {isFetching && !data && <p className="muted">Apurando…</p>}
        {!relatorioGerado && !modalAberto && <p className="muted">Use o botão Filtros para gerar a DRE.</p>}
        {data && (
          <div ref={conteudoRef} className={`relatorio-conteudo${gerandoPdf ? ' pdf-expandido' : ''}`}>
            {/* Só no PDF: na tela esses dados já estão na barra de filtros do topo, que não é
                capturada (fica fora do `.relatorio-conteudo`). */}
            {gerandoPdf && (
              <>
                <p className="section-label">Filtros Aplicados</p>
                <div className="card relatorio-filtros-aplicados">
                  <span>
                    <span className="muted">Regime: </span>
                    <strong>{ROTULO_REGIME[aplicado.regime]}</strong>
                  </span>
                  <span>
                    <span className="muted">Período: </span>
                    <strong>{aplicado.dataInicial} a {aplicado.dataFinal}</strong>
                  </span>
                  <span>
                    <span className="muted">Empresa(s): </span>
                    <strong>{textoEmpresasFiltradas()}</strong>
                  </span>
                  <span>
                    <span className="muted">Comparação: </span>
                    <strong>{ROTULO_COMPARACAO[aplicado.comparar]}</strong>
                  </span>
                </div>
              </>
            )}

            <p className="section-label" style={{ marginTop: gerandoPdf ? 24 : 0 }}>
              Demonstração do Resultado
            </p>
            <div className={`card table-wrap${gerandoPdf ? ' pdf-expandido' : ''}`}>
          <table className="table table-compacta dre-tabela">
            <thead>
              <tr>
                <th>Conta</th>
                <th style={{ textAlign: 'right' }}>Valor</th>
                <th style={{ textAlign: 'right' }} title="Percentual sobre a receita líquida">AV %</th>
                {temComparacao && <th style={{ textAlign: 'right' }}>Período anterior</th>}
                {temComparacao && <th style={{ textAlign: 'right' }}>Variação</th>}
                {temComparacao && <th style={{ textAlign: 'right' }}>AH %</th>}
              </tr>
            </thead>
            <tbody>
              {data.linhas.map((linha) => (
                <tr key={linha.chave} className={classeDaLinha(linha)}>
                  <td style={{ paddingLeft: linha.nivel === 2 ? 28 : undefined }}>
                    {linha.tipo === 'CONTA' ? `${linha.chave} — ${linha.rotulo}` : linha.rotulo}
                  </td>
                  <td style={{ textAlign: 'right' }}>{moeda(linha.valor)}</td>
                  <td style={{ textAlign: 'right' }} className="muted">
                    {linha.percentualAv == null ? '—' : `${formatarMoeda(linha.percentualAv)}%`}
                  </td>
                  {temComparacao && (
                    <td style={{ textAlign: 'right' }} className="muted">
                      {linha.valorComparado == null ? '—' : moeda(linha.valorComparado)}
                    </td>
                  )}
                  {temComparacao && (
                    <td style={{ textAlign: 'right', color: corDaVariacao(linha.variacaoAbsoluta) }}>
                      {linha.variacaoAbsoluta == null ? '—' : moeda(linha.variacaoAbsoluta)}
                    </td>
                  )}
                  {temComparacao && (
                    <td style={{ textAlign: 'right', color: corDaVariacao(linha.variacaoAbsoluta) }}>
                      {linha.variacaoPercentual == null ? '—' : `${formatarMoeda(linha.variacaoPercentual)}%`}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
            </div>

            {/* No PDF o rodapé fixo da tela não é capturado, então o resumo entra no conteúdo. */}
            {gerandoPdf && (
              <p style={{ marginTop: 16 }}>
                <strong>Receita líquida:</strong> {moeda(data.receitaLiquida)} ·{' '}
                <strong>Resultado líquido:</strong> {moeda(data.resultadoLiquido)}
              </p>
            )}
          </div>
        )}
      </div>

      {data && (
        <div className="lista-rodape">
          <span className="muted">
            Receita líquida {moeda(data.receitaLiquida)} · Resultado líquido{' '}
            <strong style={{ color: data.resultadoLiquido >= 0 ? 'var(--sucesso)' : 'var(--danger)' }}>
              {moeda(data.resultadoLiquido)}
            </strong>
          </span>
        </div>
      )}

      {modalAberto && (
        <div className="modal-overlay">
          <div className="modal" role="dialog" aria-label="Filtros da DRE" onClick={(e) => e.stopPropagation()}>
            <CabecalhoModal titulo="DRE — Demonstração do Resultado" aoFechar={() => (relatorioGerado ? setModalAberto(false) : navigate(-1))} />
            <p className="muted" style={{ marginTop: 4 }}>
              Escolha o período e o regime. <strong>Competência</strong> mostra o lucro do que foi
              vendido no período; <strong>Caixa</strong>, o que entrou e saiu de dinheiro.
            </p>

            <div className="form-grid" style={{ marginTop: 12 }}>
              <div className="col-6">
                <label htmlFor="dre-data-inicial">Data Inicial *</label>
                <input
                  id="dre-data-inicial"
                  className="mono"
                  autoFocus
                  placeholder="dd/mm/aaaa"
                  value={rascunho.dataInicial}
                  onChange={(e) => setRascunho((f) => ({ ...f, dataInicial: mascararData(e.target.value) }))}
                  onFocus={(e) => e.target.select()}
                />
              </div>
              <div className="col-6">
                <label htmlFor="dre-data-final">Data Final *</label>
                <input
                  id="dre-data-final"
                  className="mono"
                  placeholder="dd/mm/aaaa"
                  value={rascunho.dataFinal}
                  onChange={(e) => setRascunho((f) => ({ ...f, dataFinal: mascararData(e.target.value) }))}
                  onFocus={(e) => e.target.select()}
                />
              </div>

              <div className="col-6">
                <label htmlFor="dre-regime">Regime</label>
                <select
                  id="dre-regime"
                  value={rascunho.regime}
                  onChange={(e) => setRascunho((f) => ({ ...f, regime: e.target.value as RegimeDre }))}
                >
                  <option value="COMPETENCIA">Competência (padrão oficial)</option>
                  <option value="CAIXA">Caixa (gerencial)</option>
                </select>
              </div>
              <div className="col-6">
                <label htmlFor="dre-comparar">Comparar com</label>
                <select
                  id="dre-comparar"
                  value={rascunho.comparar}
                  onChange={(e) => setRascunho((f) => ({ ...f, comparar: e.target.value as ComparacaoDre }))}
                >
                  <option value="PERIODO_ANTERIOR">Período anterior</option>
                  <option value="ANO_ANTERIOR">Mesmo período do ano anterior</option>
                  <option value="NENHUM">Sem comparação</option>
                </select>
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
                Gerar DRE
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
