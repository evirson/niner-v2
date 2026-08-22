import { useQuery } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Bar,
  BarChart,
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeRelatorio } from '../../components/Icones'
import { ApiError } from '../../lib/api'
import { useEu } from '../../lib/eu'
import { listarEmpresas } from '../../lib/empresas'
import { dataParaIso, dataValida, formatarMoeda, isoParaData } from '../../lib/masks'
import {
  gerarRelatorioVendas,
  rotulosPorCarteira,
  type FiltrosRelatorioVendas,
  type LinhaAgrupadaTotalizador,
  type LinhaAnaliticaTotalizador,
  type PontoGrafico,
} from '../../lib/relatorioVendas'
import { gerarPdfCapturaRelatorioVendas } from '../../lib/relatorioVendasCaptura'
import PesquisaVendedorModal from '../pdv/PesquisaVendedorModal'
import DrilldownTotalizadorModal from './DrilldownTotalizadorModal'
import FiltrosVendasModal, { filtrosIniciais, PERIODOS, TOTALIZADORES, type FiltrosTexto } from './FiltrosVendasModal'

type Direcao = 'ASC' | 'DESC'
type ColunaAnalitico =
  | 'nomeEmpresa'
  | 'idVenda'
  | 'dataHoraVenda'
  | 'nomeCliente'
  | 'nomeVendedor'
  | 'nomeOperador'
  | 'qtdProdutos'
  | 'valorVenda'
  | 'acrescimos'
  | 'descontos'
  | 'valorLiquido'
type ColunaAgrupado = 'nome' | 'nVendas' | 'valorVenda'

const COLUNAS_ANALITICO: Array<{ chave: ColunaAnalitico; rotulo: string; alinhamento?: 'right' }> = [
  { chave: 'nomeEmpresa', rotulo: 'Empresa' },
  { chave: 'idVenda', rotulo: 'Nº Venda' },
  { chave: 'dataHoraVenda', rotulo: 'Data/Hora' },
  { chave: 'nomeCliente', rotulo: 'Cliente' },
  { chave: 'nomeVendedor', rotulo: 'Vendedor' },
  { chave: 'nomeOperador', rotulo: 'Operador' },
  { chave: 'qtdProdutos', rotulo: 'Qtd Produtos', alinhamento: 'right' },
  { chave: 'valorVenda', rotulo: 'Valor Venda', alinhamento: 'right' },
  { chave: 'acrescimos', rotulo: 'Acréscimos', alinhamento: 'right' },
  { chave: 'descontos', rotulo: 'Descontos', alinhamento: 'right' },
  { chave: 'valorLiquido', rotulo: 'Valor Líquido', alinhamento: 'right' },
]

const COLUNAS_AGRUPADO: Array<{ chave: ColunaAgrupado; rotulo: string; alinhamento?: 'right' }> = [
  { chave: 'nome', rotulo: 'Nome do Totalizador' },
  { chave: 'nVendas', rotulo: 'Nº de Vendas', alinhamento: 'right' },
  { chave: 'valorVenda', rotulo: 'Valor da Venda', alinhamento: 'right' },
]

function compararValores(a: unknown, b: unknown): number {
  if (typeof a === 'string' && typeof b === 'string') return a.localeCompare(b)
  if (typeof a === 'number' && typeof b === 'number') return a - b
  return 0
}

/** Ordenação 100% no cliente — a lista inteira já vem completa do backend numa única resposta
 *  (KPIs + gráficos + grid), sem paginação e sem agrupamento por linha que precise ficar
 *  adjacente (diferente de Contas a Receber/Comissões, que ordenam no servidor pra preservar o
 *  subtotal por empresa) — reordenar em memória é suficiente e não exige nova ida ao servidor. */
function ordenarLinhas<T extends Record<string, unknown>>(linhas: T[], coluna: string, direcao: Direcao): T[] {
  const copia = [...linhas]
  copia.sort((a, b) => {
    const cmp = compararValores(a[coluna] ?? '', b[coluna] ?? '')
    return direcao === 'ASC' ? cmp : -cmp
  })
  return copia
}

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

/**
 * Quantidade **não é dinheiro** (auditoria 2026-08-21, item 11).
 *
 * ⚠️ O KPI "Itens Vendidos" e o rodapé da grade usavam `formatarMoeda`, que força duas casas: o KPI
 * mostrava **"1.234,00"** itens, e o rodapé somava "12,00" embaixo de uma coluna que exibia "3",
 * "5", "4". Fração só aparece quando existe de verdade — o tenant que permite quantidade decimal
 * (`cfg_permite_qtd_decimal`) vende 2,5 kg, e aí as três casas do peso importam.
 */
function quantidade(v: number): string {
  return Number.isInteger(v)
    ? v.toLocaleString('pt-BR')
    : v.toLocaleString('pt-BR', { minimumFractionDigits: 3, maximumFractionDigits: 3 })
}

/** Média por venda é sempre fracionária — 2 casas, mas sem "R$" nem cara de dinheiro. */
function media(v: number): string {
  return v.toLocaleString('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function percentual(v: number): string {
  return `${formatarMoeda(v)}%`
}

function formatarDataHora(iso: string): string {
  return new Date(iso).toLocaleString('pt-BR')
}

function formatarRotuloDia(rotulo: string): string {
  const [, m, d] = rotulo.split('-')
  return `${d}/${m}`
}

const estiloTooltip = { background: 'var(--surface)', border: '1px solid var(--line)', borderRadius: 8, fontSize: 13 }
const estiloEixo = { fontSize: 12, fill: 'var(--ink-muted)' }

function GraficoLinha({ dados, cor = 'var(--accent)' }: { dados: PontoGrafico[]; cor?: string }) {
  return (
    <ResponsiveContainer width="100%" height={220}>
      <LineChart data={dados} margin={{ top: 8, right: 12, left: 0, bottom: 0 }}>
        <CartesianGrid strokeDasharray="0" stroke="var(--line)" vertical={false} />
        <XAxis
          dataKey="rotulo"
          tickFormatter={formatarRotuloDia}
          interval={Math.max(0, Math.ceil(dados.length / 8) - 1)}
          tick={estiloEixo}
          axisLine={{ stroke: 'var(--line)' }}
          tickLine={false}
        />
        <YAxis tick={estiloEixo} axisLine={false} tickLine={false} width={70} tickFormatter={(v) => moeda(v)} />
        <Tooltip
          contentStyle={estiloTooltip}
          labelFormatter={(rotulo) => formatarRotuloDia(String(rotulo ?? ''))}
          formatter={(valor) => [moeda(Number(valor)), 'Valor vendido']}
        />
        <Line type="monotone" dataKey="valor" stroke={cor} strokeWidth={2} dot={false} activeDot={{ r: 4 }} />
      </LineChart>
    </ResponsiveContainer>
  )
}

function GraficoBarraHorizontal({ dados, cor = 'var(--accent)' }: { dados: PontoGrafico[]; cor?: string }) {
  return (
    <ResponsiveContainer width="100%" height={Math.max(160, dados.length * 32)}>
      <BarChart data={dados} layout="vertical" margin={{ top: 4, right: 24, left: 0, bottom: 4 }}>
        <CartesianGrid strokeDasharray="0" stroke="var(--line)" horizontal={false} />
        <XAxis type="number" tick={estiloEixo} axisLine={{ stroke: 'var(--line)' }} tickLine={false} tickFormatter={(v) => moeda(v)} />
        <YAxis type="category" dataKey="rotulo" tick={estiloEixo} axisLine={false} tickLine={false} width={140} />
        <Tooltip contentStyle={estiloTooltip} formatter={(valor) => [moeda(Number(valor)), 'Valor']} />
        <Bar dataKey="valor" fill={cor} radius={[0, 4, 4, 0]} maxBarSize={22} />
      </BarChart>
    </ResponsiveContainer>
  )
}

function GraficoBarraColuna({ dados, cor = 'var(--accent)' }: { dados: PontoGrafico[]; cor?: string }) {
  return (
    <ResponsiveContainer width="100%" height={220}>
      <BarChart data={dados} margin={{ top: 8, right: 12, left: 0, bottom: 0 }}>
        <CartesianGrid strokeDasharray="0" stroke="var(--line)" vertical={false} />
        <XAxis dataKey="rotulo" tick={estiloEixo} axisLine={{ stroke: 'var(--line)' }} tickLine={false} />
        <YAxis tick={estiloEixo} axisLine={false} tickLine={false} width={70} tickFormatter={(v) => moeda(v)} />
        <Tooltip contentStyle={estiloTooltip} formatter={(valor) => [moeda(Number(valor)), 'Valor vendido']} />
        <Bar dataKey="valor" fill={cor} radius={[4, 4, 0, 0]} maxBarSize={24} />
      </BarChart>
    </ResponsiveContainer>
  )
}

/**
 * Relatório de Vendas (docs/telas/relatorio-vendas.md) — 1ª tela do grupo Relatórios, define o
 * padrão de tela de filtro de relatório: filtros → KPIs → composição do faturamento → gráficos
 * → grid totalizável com drill-down. Qualquer papel; empresa segue o mesmo padrão de Pesquisa
 * de Vendas (ADMIN filtra livremente — aqui em multi-select —, OPERADOR fixo na própria empresa).
 *
 * Filtros em popup (2026-08-03): mesmo padrão do Relatório de Contas a Receber — abre direto o
 * popup `FiltrosVendasModal` (nada é buscado antes disso, incluindo "Totalizar por", que agora
 * só troca a grid depois de "Gerar Relatório") e o botão "Filtros" no topo reabre o popup. A grid
 * final (não os KPIs/gráficos, que continuam soltos na página — ela é que tem `.grid-altura-fixa`
 * própria) ganha cabeçalho/rodapé fixos com ordenação client-side por coluna (a resposta inteira
 * já vem completa do backend numa tacada só, sem paginação).
 */
export default function RelatorioVendas() {
  const navigate = useNavigate()
  const { data: eu } = useEu()
  const ehAdmin = eu?.usuario.papel === 'ADMIN'

  const [aplicado, setAplicado] = useState<FiltrosTexto>(() => filtrosIniciais(isoParaData))
  const [rascunho, setRascunho] = useState<FiltrosTexto>(() => filtrosIniciais(isoParaData))
  const [modalAberto, setModalAberto] = useState(true)
  const [relatorioGerado, setRelatorioGerado] = useState(false)
  const [mostrarBuscaVendedor, setMostrarBuscaVendedor] = useState(false)
  const [grupoSelecionado, setGrupoSelecionado] = useState<LinhaAgrupadaTotalizador | null>(null)
  const [gerandoPdf, setGerandoPdf] = useState(false)
  const [ordenarPorAnalitico, setOrdenarPorAnalitico] = useState<ColunaAnalitico>('dataHoraVenda')
  const [direcaoAnalitico, setDirecaoAnalitico] = useState<Direcao>('DESC')
  const [ordenarPorAgrupado, setOrdenarPorAgrupado] = useState<ColunaAgrupado>('valorVenda')
  const [direcaoAgrupado, setDirecaoAgrupado] = useState<Direcao>('DESC')
  const topoRef = useRef<HTMLDivElement>(null)
  const gridRef = useRef<HTMLDivElement>(null)

  const { data: empresas } = useQuery({ queryKey: ['empresas'], queryFn: listarEmpresas, enabled: ehAdmin })

  const dataInicialIso = dataValida(aplicado.dataInicial) ? dataParaIso(aplicado.dataInicial) : null
  const dataFinalIso = dataValida(aplicado.dataFinal) ? dataParaIso(aplicado.dataFinal) : null

  const filtros: FiltrosRelatorioVendas = {
    dataInicial: dataInicialIso ?? '',
    dataFinal: dataFinalIso ?? '',
    idsEmpresa: ehAdmin ? aplicado.idsEmpresa : undefined,
    idFuncionario: aplicado.vendedor?.idFuncionario,
    totalizarPor: aplicado.totalizarPor,
  }

  const podeGerarRascunho = dataValida(rascunho.dataInicial) && dataValida(rascunho.dataFinal)

  const { data, isLoading, isFetching, error } = useQuery({
    queryKey: ['relatorio-vendas', filtros],
    queryFn: () => gerarRelatorioVendas(filtros),
    enabled: relatorioGerado,
    placeholderData: (anterior) => anterior,
  })

  function abrirFiltros() {
    setRascunho(aplicado)
    setModalAberto(true)
  }

  function handleGerar() {
    setAplicado(rascunho)
    setRelatorioGerado(true)
    setModalAberto(false)
  }

  function fecharModal() {
    setModalAberto(false)
  }

  function ordenarPorColunaAnalitico(coluna: ColunaAnalitico) {
    if (coluna === ordenarPorAnalitico) {
      setDirecaoAnalitico((d) => (d === 'ASC' ? 'DESC' : 'ASC'))
    } else {
      setOrdenarPorAnalitico(coluna)
      setDirecaoAnalitico('ASC')
    }
  }

  function ordenarPorColunaAgrupado(coluna: ColunaAgrupado) {
    if (coluna === ordenarPorAgrupado) {
      setDirecaoAgrupado((d) => (d === 'ASC' ? 'DESC' : 'ASC'))
    } else {
      setOrdenarPorAgrupado(coluna)
      setDirecaoAgrupado('ASC')
    }
  }

  /** Mesmo texto usado na linha "Empresa(s)" dos Filtros Aplicados e no rodapé de cada página
   *  do PDF (pedido explícito: rodapé mostra a empresa filtrada, igual ao modelo de referência). */
  function textoEmpresasFiltradas(): string {
    if (ehAdmin) {
      return aplicado.idsEmpresa.length === 0
        ? 'Todas as empresas'
        : (empresas ?? [])
            .filter((e) => aplicado.idsEmpresa.includes(e.idEmpresa))
            .map((e) => e.nomeFantasia ?? e.razaoSocial)
            .join(', ') || 'Todas as empresas'
    }
    return eu?.empresa.nome ?? '—'
  }

  /** "Gerado em" não entra mais aqui — subiu pro cabeçalho de cada página do PDF (junto do
   *  título e da paginação), conforme o modelo de referência (RELATORIO_COMISSOES.PDF). */
  function montarDescricaoFiltros(): Array<{ rotulo: string; valor: string }> {
    const rotuloPeriodo = PERIODOS.find((p) => p.chave === aplicado.periodoPreset)?.rotulo
    const periodo = `${aplicado.dataInicial} a ${aplicado.dataFinal}` + (aplicado.periodoPreset !== 'PERSONALIZADO' && rotuloPeriodo ? ` (${rotuloPeriodo})` : '')

    return [
      { rotulo: 'Período', valor: periodo },
      { rotulo: 'Empresa(s)', valor: textoEmpresasFiltradas() },
      { rotulo: 'Vendedor', valor: aplicado.vendedor ? aplicado.vendedor.nome : 'Todos os vendedores' },
      { rotulo: 'Totalizar por', valor: TOTALIZADORES.find((t) => t.chave === aplicado.totalizarPor)?.rotulo ?? '—' },
    ]
  }

  /** A seção "Filtros Aplicados" só existe no PDF, nunca na tela — ela só entra no DOM
   *  enquanto `gerandoPdf` está true (renderizada dentro de `topoRef`, então a própria captura
   *  do html2canvas a pega junto). Precisa de 2 `requestAnimationFrame` depois do `setState`
   *  pra garantir que o React já commitou e o navegador já pintou o novo nó antes da captura —
   *  senão o html2canvas roda contra o DOM antigo (sem a seção). */
  function aguardarPintura(): Promise<void> {
    return new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
  }

  const handleGerarPdf = async () => {
    if (!topoRef.current || !gridRef.current) return
    setGerandoPdf(true)
    try {
      await aguardarPintura()
      // Rodapé mostra a empresa logada na sessão (fixa), não a lista de empresas do filtro —
      // essa já aparece em "Filtros Aplicados" e pode ser "Todas as empresas" (ADMIN). O PDF
      // sai sempre em tema claro (forçado dentro de `onclone` do html2canvas, só no clone que
      // vira a captura — a tela real nunca muda de tema).
      await gerarPdfCapturaRelatorioVendas(topoRef.current, gridRef.current, eu?.empresa.nome ?? '—')
    } finally {
      setGerandoPdf(false)
    }
  }

  const linhasAnaliticasOrdenadas = ordenarLinhas(
    (data?.totalizador.linhasAnaliticas ?? []) as unknown as Array<Record<string, unknown>>,
    ordenarPorAnalitico,
    direcaoAnalitico,
  ) as unknown as LinhaAnaliticaTotalizador[]

  const linhasAgrupadasOrdenadas = ordenarLinhas(
    (data?.totalizador.linhasAgrupadas ?? []) as unknown as Array<Record<string, unknown>>,
    ordenarPorAgrupado,
    direcaoAgrupado,
  ) as unknown as LinhaAgrupadaTotalizador[]

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeRelatorio size={34} />
            <h1>Relatório de Vendas</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="relatorios.vendas.tela" />
            {relatorioGerado && (
              <button type="button" className="btn ghost" onClick={abrirFiltros}>
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
      </div>

      <div className="lista-corpo">
        {!relatorioGerado ? (
          <p className="muted">Use o botão Filtros para gerar o relatório.</p>
        ) : isLoading ? (
          <p className="muted">Carregando relatório…</p>
        ) : error || !data ? (
          <p className="erro">{error instanceof ApiError ? error.message : 'Não foi possível gerar o relatório.'}</p>
        ) : (
          <div>
          <div ref={topoRef}>
            {isFetching && <p className="muted">Atualizando…</p>}

            {gerandoPdf && (
              <>
                <p className="section-label">Filtros Aplicados</p>
                <div className="card relatorio-filtros-aplicados">
                  {montarDescricaoFiltros().map((f) => (
                    <span key={f.rotulo}>
                      <span className="muted">{f.rotulo}: </span>
                      <strong>{f.valor}</strong>
                    </span>
                  ))}
                </div>
              </>
            )}

            <p className="section-label" style={{ marginTop: gerandoPdf ? 24 : 0 }}>
              Resumo do Período
            </p>
            <div className="relatorio-kpis-grid">
              <div className="relatorio-kpi-card">
                <p className="card-title">Ticket Médio</p>
                <p className="relatorio-kpi-valor cor-accent">{moeda(data.kpis.ticketMedioValor)}</p>
                <p className="muted">Nº Vendas: {data.kpis.ticketMedioNVendas}</p>
              </div>
              <div className="relatorio-kpi-card">
                <p className="card-title">% Médio de Desconto</p>
                <p className="relatorio-kpi-valor cor-danger">{percentual(data.kpis.percentualMedioDesconto)}</p>
                <p className="muted">Valor Desconto: {moeda(data.kpis.valorDesconto)}</p>
              </div>
              <div className="relatorio-kpi-card">
                <p className="card-title">Devoluções</p>
                <p className="relatorio-kpi-valor cor-danger">{percentual(data.kpis.percentualDevolucao)}</p>
                <p className="muted">Valor Devolução: {moeda(data.kpis.valorDevolucao)}</p>
              </div>
              <div className="relatorio-kpi-card">
                <p className="card-title">Itens Vendidos / Média</p>
                <p className="relatorio-kpi-valor cor-accent">{quantidade(data.kpis.itensVendidos)}</p>
                <p className="muted">Média por venda: {media(data.kpis.mediaItensPorVenda)}</p>
              </div>
            </div>

            <p className="section-label" style={{ marginTop: 24 }}>
              Composição do Faturamento
            </p>
            <div className="relatorio-composicao-grid">
              <div className="relatorio-kpi-card relatorio-composicao-card">
                <p className="card-title">Valor Bruto</p>
                <p className="relatorio-kpi-valor">{moeda(data.composicaoFaturamento.valorBruto)}</p>
              </div>
              <div className="relatorio-kpi-card relatorio-composicao-card">
                <p className="card-title">Descontos</p>
                <p className="relatorio-kpi-valor cor-danger">{moeda(data.composicaoFaturamento.descontos)}</p>
              </div>
              <div className="relatorio-kpi-card relatorio-composicao-card">
                <p className="card-title">Acréscimos</p>
                <p className="relatorio-kpi-valor cor-sucesso">{moeda(data.composicaoFaturamento.acrescimos)}</p>
              </div>
              <div className="relatorio-kpi-card relatorio-composicao-card">
                <p className="card-title">Devoluções</p>
                <p className="relatorio-kpi-valor cor-danger">{moeda(data.composicaoFaturamento.devolucoes)}</p>
              </div>
              <div className="relatorio-kpi-card relatorio-composicao-card destaque">
                <p className="card-title">Venda Líquida</p>
                <p className="relatorio-kpi-valor">{moeda(data.composicaoFaturamento.vendaLiquida)}</p>
              </div>
            </div>

            <p className="section-label" style={{ marginTop: 24 }}>
              Gráficos
            </p>
            <div className="relatorio-graficos-grid">
              <div className="card relatorio-grafico-card">
                <p className="section-label">Valor Vendido por Dia</p>
                <GraficoLinha dados={data.graficos.porDia} cor="var(--accent)" />
              </div>
              <div className="card relatorio-grafico-card">
                <p className="section-label">Top 10 Marcas</p>
                <GraficoBarraHorizontal dados={data.graficos.topMarcas} cor="var(--info)" />
              </div>
              <div className="card relatorio-grafico-card">
                <p className="section-label">Top 10 Vendedores</p>
                <GraficoBarraHorizontal dados={data.graficos.topVendedores} cor="var(--accent)" />
              </div>
              <div className="card relatorio-grafico-card">
                <p className="section-label">Top 10 Clientes</p>
                <GraficoBarraHorizontal dados={data.graficos.topClientes} cor="var(--info)" />
              </div>
              <div className="card relatorio-grafico-card">
                <p className="section-label">Recebimentos por Tipo de Carteira</p>
                <GraficoBarraHorizontal dados={rotulosPorCarteira(data.graficos.porCarteira)} cor="var(--accent)" />
              </div>
              <div className="card relatorio-grafico-card">
                <p className="section-label">Por Hora da Venda</p>
                <GraficoBarraColuna dados={data.graficos.porHora} cor="var(--info)" />
              </div>
              <div className="card relatorio-grafico-card" style={{ gridColumn: '1 / -1' }}>
                <p className="section-label">Por Dia da Semana</p>
                <GraficoBarraColuna dados={data.graficos.porDiaSemana} cor="var(--accent)" />
              </div>
            </div>
          </div>

          <div ref={gridRef} className="relatorio-grid-conteudo">
            <p className="section-label" style={{ marginTop: 24 }}>
              {data.totalizador.tipo === 'ANALITICO' ? 'Vendas do Período' : 'Totalizado por ' + TOTALIZADORES.find((t) => t.chave === aplicado.totalizarPor)?.rotulo}
            </p>
            <div className="card table-wrap grid-altura-fixa">
              {data.totalizador.tipo === 'ANALITICO' ? (
                linhasAnaliticasOrdenadas.length === 0 ? (
                  <p className="muted">Nenhuma venda encontrada para os filtros informados.</p>
                ) : (
                  <table className="table table-compacta tabela-adensada">
                    <thead>
                      <tr>
                        {COLUNAS_ANALITICO.map((c) => {
                          const ativa = ordenarPorAnalitico === c.chave
                          return (
                            <th
                              key={c.chave}
                              className="th-ordenavel"
                              style={c.alinhamento === 'right' ? { textAlign: 'right' } : undefined}
                              onClick={() => ordenarPorColunaAnalitico(c.chave)}
                              title="Clique para ordenar"
                              aria-sort={ativa ? (direcaoAnalitico === 'ASC' ? 'ascending' : 'descending') : 'none'}
                            >
                              {c.rotulo}
                              <span className={`th-seta ${ativa ? 'th-seta-ativa' : ''}`}>
                                {ativa ? (direcaoAnalitico === 'ASC' ? '▲' : '▼') : '⇅'}
                              </span>
                            </th>
                          )
                        })}
                      </tr>
                    </thead>
                    <tbody>
                      {linhasAnaliticasOrdenadas.map((i) => (
                        <tr key={i.idVenda}>
                          <td>{i.nomeEmpresa}</td>
                          <td className="mono">{i.idVenda}</td>
                          <td>{formatarDataHora(i.dataHoraVenda)}</td>
                          <td>{i.nomeCliente ?? '—'}</td>
                          <td>{i.nomeVendedor ?? '—'}</td>
                          <td>{i.nomeOperador ?? '—'}</td>
                          <td className="mono" style={{ textAlign: 'right' }}>{quantidade(i.qtdProdutos)}</td>
                          <td className="mono" style={{ textAlign: 'right' }}>{moeda(i.valorVenda)}</td>
                          <td className="mono" style={{ textAlign: 'right' }}>{moeda(i.acrescimos)}</td>
                          <td className="mono" style={{ textAlign: 'right' }}>{moeda(i.descontos)}</td>
                          <td className="mono" style={{ textAlign: 'right' }}>{moeda(i.valorLiquido)}</td>
                        </tr>
                      ))}
                    </tbody>
                    <tfoot>
                      <tr>
                        <td colSpan={6}>
                          <strong>Total ({linhasAnaliticasOrdenadas.length} vendas)</strong>
                        </td>
                        <td className="mono" style={{ textAlign: 'right' }}>
                          {quantidade(linhasAnaliticasOrdenadas.reduce((s, i) => s + i.qtdProdutos, 0))}
                        </td>
                        <td className="mono" style={{ textAlign: 'right' }}>{moeda(linhasAnaliticasOrdenadas.reduce((s, i) => s + i.valorVenda, 0))}</td>
                        <td className="mono" style={{ textAlign: 'right' }}>{moeda(linhasAnaliticasOrdenadas.reduce((s, i) => s + i.acrescimos, 0))}</td>
                        <td className="mono" style={{ textAlign: 'right' }}>{moeda(linhasAnaliticasOrdenadas.reduce((s, i) => s + i.descontos, 0))}</td>
                        <td className="mono" style={{ textAlign: 'right' }}>{moeda(linhasAnaliticasOrdenadas.reduce((s, i) => s + i.valorLiquido, 0))}</td>
                      </tr>
                    </tfoot>
                  </table>
                )
              ) : linhasAgrupadasOrdenadas.length === 0 ? (
                <p className="muted">Nenhuma venda encontrada para os filtros informados.</p>
              ) : (
                <table className="table table-compacta">
                  <thead>
                    <tr>
                      {COLUNAS_AGRUPADO.map((c) => {
                        const ativa = ordenarPorAgrupado === c.chave
                        return (
                          <th
                            key={c.chave}
                            className="th-ordenavel"
                            style={c.alinhamento === 'right' ? { textAlign: 'right' } : undefined}
                            onClick={() => ordenarPorColunaAgrupado(c.chave)}
                            title="Clique para ordenar"
                            aria-sort={ativa ? (direcaoAgrupado === 'ASC' ? 'ascending' : 'descending') : 'none'}
                          >
                            {c.rotulo}
                            <span className={`th-seta ${ativa ? 'th-seta-ativa' : ''}`}>
                              {ativa ? (direcaoAgrupado === 'ASC' ? '▲' : '▼') : '⇅'}
                            </span>
                          </th>
                        )
                      })}
                    </tr>
                  </thead>
                  <tbody>
                    {linhasAgrupadasOrdenadas.map((g) => (
                      <tr key={g.chave} tabIndex={0} onClick={() => setGrupoSelecionado(g)}
                          onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setGrupoSelecionado(g) } }}>
                        <td>{g.nome}</td>
                        <td className="mono" style={{ textAlign: 'right' }}>{g.nVendas}</td>
                        <td className="mono" style={{ textAlign: 'right' }}>{moeda(g.valorVenda)}</td>
                      </tr>
                    ))}
                  </tbody>
                  <tfoot>
                    <tr>
                      <td>
                        <strong>Total ({linhasAgrupadasOrdenadas.length})</strong>
                      </td>
                      <td className="mono" style={{ textAlign: 'right' }}>{linhasAgrupadasOrdenadas.reduce((s, g) => s + g.nVendas, 0)}</td>
                      <td className="mono" style={{ textAlign: 'right' }}>{moeda(linhasAgrupadasOrdenadas.reduce((s, g) => s + g.valorVenda, 0))}</td>
                    </tr>
                  </tfoot>
                </table>
              )}
            </div>
          </div>
          </div>
        )}
      </div>

      {modalAberto && (
        <FiltrosVendasModal
          valores={rascunho}
          aoMudar={(parcial) => setRascunho((r) => ({ ...r, ...parcial }))}
          ehAdmin={!!ehAdmin}
          empresas={empresas ?? []}
          podeGerar={podeGerarRascunho}
          primeiraVez={!relatorioGerado}
          aoGerar={handleGerar}
          aoFechar={relatorioGerado ? fecharModal : () => navigate(-1)}
          isoParaData={isoParaData}
          aoAbrirBuscaVendedor={() => setMostrarBuscaVendedor(true)}
        />
      )}
      {mostrarBuscaVendedor && (
        <PesquisaVendedorModal
          aoFechar={() => setMostrarBuscaVendedor(false)}
          aoSelecionar={(f) => {
            setRascunho((r) => ({ ...r, vendedor: f }))
            setMostrarBuscaVendedor(false)
          }}
        />
      )}
      {grupoSelecionado && (
        <DrilldownTotalizadorModal
          filtros={filtros}
          chave={grupoSelecionado.chave}
          nomeGrupo={grupoSelecionado.nome}
          aoFechar={() => setGrupoSelecionado(null)}
        />
      )}
    </div>
  )
}
