import { useQuery } from '@tanstack/react-query'
import { Fragment, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeRelatorio } from '../../components/Icones'
import { ApiError } from '../../lib/api'
import { useEu } from '../../lib/eu'
import { listarEmpresas } from '../../lib/empresas'
import { dataParaIso, dataValida, formatarMoeda } from '../../lib/masks'
import {
  gerarRelatorioOrdensServico,
  type ColunaOrdenacaoOrdens,
  type DirecaoOrdenacao,
  type FiltrosRelatorioOrdensServico,
} from '../../lib/relatorioOrdensServico'
import { gerarPdfCapturaRelatorioOrdensServico } from '../../lib/relatorioOrdensServicoCaptura'
import { aguardarPintura } from '../../lib/temaClaroParaCaptura'
import FiltrosComissoesModal, { FILTROS_VAZIOS, type FiltrosTexto } from './FiltrosComissoesModal'

const COLUNAS: Array<{ chave: ColunaOrdenacaoOrdens; rotulo: string; alinhamento?: 'right' }> = [
  { chave: 'nomeEmpresa', rotulo: 'Empresa' },
  { chave: 'nomeFuncionario', rotulo: 'Executor' },
  { chave: 'qtdOrdens', rotulo: 'Nº OS', alinhamento: 'right' },
  { chave: 'valorServicos', rotulo: 'Serviços', alinhamento: 'right' },
  { chave: 'valorPecas', rotulo: 'Peças', alinhamento: 'right' },
  { chave: 'valorTotal', rotulo: 'Total', alinhamento: 'right' },
  { chave: 'tempoMedioHoras', rotulo: 'Tempo Médio', alinhamento: 'right' },
]

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

/**
 * Escolhe a unidade que a pessoa usa para falar daquela duração — minutos para o banho e tosa,
 * horas para a mecânica do dia, dias para o carro que dormiu na oficina.
 *
 * ⚠️ **`—` significa "não há o que medir", e SÓ isso** (nenhuma OS concluída). A primeira versão
 * escrevia `—` para qualquer valor `<= 0` e o backend arredondava o tempo para uma casa: as OS
 * reais levaram de 0,0047 h a 0,0594 h, viraram 0,0 e a coluna inteira saiu vazia — a tela dizia
 * "não medi" sobre um dado que existia. Achado abrindo a tela, não lendo o código.
 */
function tempo(horas: number | null): string {
  if (horas === null || horas === undefined) return '—'
  const minutos = horas * 60
  if (minutos < 1) return 'menos de 1 min'
  if (minutos < 60) return `${Math.round(minutos)} min`
  if (horas < 48) return `${formatarMoeda(horas)}h`
  return `${formatarMoeda(horas)}h (${Math.round(horas / 24)}d)`
}

function periodoPreenchido(inicial: string, final: string): boolean {
  return dataValida(inicial) && dataValida(final)
}

/**
 * Relatório de Ordens de Serviço (docs/telas/relatorio-ordem-servico.md) — fecha a pendência #56.
 * Mesmo padrão do Relatório de Comissões: filtros em popup, PDF por captura visual, grid com
 * subtotal por empresa e cabeçalho/rodapé fixos. Reusa `FiltrosComissoesModal`, que já é genérico
 * (período + empresas) — duplicá-lo só criaria dois lugares para corrigir o mesmo campo.
 *
 * ⚠️ Duas coisas que a tela precisa DIZER, porque o número sozinho engana:
 * - o **Movimento** tem quatro contadores em quatro eixos de data diferentes, e somá-los não
 *   significa nada;
 * - o **Nº OS** do total geral não é a soma da coluna: uma OS com dois executores conta uma vez
 *   para cada um. Sem o aviso, parece erro de cálculo.
 */
export default function RelatorioOrdensServico() {
  const navigate = useNavigate()
  const { data: eu } = useEu()
  const ehAdmin = eu?.usuario.papel === 'ADMIN'

  const [aplicado, setAplicado] = useState<FiltrosTexto>(FILTROS_VAZIOS)
  const [rascunho, setRascunho] = useState<FiltrosTexto>(FILTROS_VAZIOS)
  const [modalAberto, setModalAberto] = useState(true)
  const [relatorioGerado, setRelatorioGerado] = useState(false)
  const [ordenarPor, setOrdenarPor] = useState<ColunaOrdenacaoOrdens>('valorTotal')
  const [direcao, setDirecao] = useState<DirecaoOrdenacao>('DESC')
  const [gerandoPdf, setGerandoPdf] = useState(false)
  const conteudoRef = useRef<HTMLDivElement>(null)

  const { data: empresas } = useQuery({ queryKey: ['empresas'], queryFn: listarEmpresas, enabled: ehAdmin })

  const dataInicialIso = dataValida(aplicado.dataInicial) ? dataParaIso(aplicado.dataInicial) : null
  const dataFinalIso = dataValida(aplicado.dataFinal) ? dataParaIso(aplicado.dataFinal) : null

  const filtros: FiltrosRelatorioOrdensServico = {
    dataInicial: dataInicialIso ?? '',
    dataFinal: dataFinalIso ?? '',
    idsEmpresa: ehAdmin ? aplicado.idsEmpresa : undefined,
    ordenarPor,
    direcao,
  }

  const podeGerarRascunho = periodoPreenchido(rascunho.dataInicial, rascunho.dataFinal)

  const { data, isLoading, isFetching, error } = useQuery({
    queryKey: ['relatorio-ordens-servico', filtros],
    queryFn: () => gerarRelatorioOrdensServico(filtros),
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

  function ordenarPorColuna(coluna: ColunaOrdenacaoOrdens) {
    if (coluna === ordenarPor) {
      setDirecao((d) => (d === 'ASC' ? 'DESC' : 'ASC'))
    } else {
      setOrdenarPor(coluna)
      setDirecao('ASC')
    }
  }

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

  const handleGerarPdf = async () => {
    if (!conteudoRef.current) return
    setGerandoPdf(true)
    try {
      await aguardarPintura()
      await gerarPdfCapturaRelatorioOrdensServico(conteudoRef.current, eu?.empresa.nome ?? '—')
    } finally {
      setGerandoPdf(false)
    }
  }

  const mostrarSubtotais = (data?.subtotaisPorEmpresa.length ?? 0) > 1

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeRelatorio size={34} />
            <h1>Relatório de Ordens de Serviço</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="relatorios.ordens-servico.tela" />
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

      <div className="lista-corpo relatorio-corpo-fixo">
        {!relatorioGerado ? (
          <p className="muted">Use o botão Filtros para gerar o relatório.</p>
        ) : isLoading ? (
          <p className="muted">Carregando relatório…</p>
        ) : error || !data ? (
          <p className="erro">{error instanceof ApiError ? error.message : 'Não foi possível gerar o relatório.'}</p>
        ) : (
          <div ref={conteudoRef} className={`relatorio-conteudo${gerandoPdf ? ' pdf-expandido' : ''}`}>
            {isFetching && (
              <p className="muted" data-sem-impressao>
                Atualizando…
              </p>
            )}

            {gerandoPdf && (
              <>
                <p className="section-label">Filtros Aplicados</p>
                <div className="card relatorio-filtros-aplicados">
                  <span>
                    <span className="muted">Período: </span>
                    <strong>
                      {aplicado.dataInicial} a {aplicado.dataFinal}
                    </strong>
                  </span>
                  <span>
                    <span className="muted">Empresa(s): </span>
                    <strong>{textoEmpresasFiltradas()}</strong>
                  </span>
                </div>
              </>
            )}

            <p className="section-label" style={{ marginTop: gerandoPdf ? 24 : 0 }}>
              Movimento do Período
            </p>
            <div className="relatorio-kpis-grid">
              <div className="card relatorio-kpi-card">
                <p className="muted">Abertas</p>
                <p className="relatorio-kpi-valor">{data.movimento.qtdAbertas}</p>
                <p className="muted">pela data de abertura</p>
              </div>
              <div className="card relatorio-kpi-card">
                <p className="muted">Concluídas</p>
                <p className="relatorio-kpi-valor cor-accent">{data.movimento.qtdConcluidas}</p>
                <p className="muted">pela data de conclusão</p>
              </div>
              <div className="card relatorio-kpi-card">
                <p className="muted">Faturadas</p>
                <p className="relatorio-kpi-valor cor-sucesso">{data.movimento.qtdFaturadas}</p>
                <p className="muted">pela data de faturamento</p>
              </div>
              <div className="card relatorio-kpi-card">
                <p className="muted">Canceladas</p>
                <p className="relatorio-kpi-valor cor-danger">{data.movimento.qtdCanceladas}</p>
                <p className="muted">pela data de cancelamento</p>
              </div>
              <div className="card relatorio-kpi-card">
                <p className="muted">Valor Faturado</p>
                <p className="relatorio-kpi-valor cor-sucesso">{moeda(data.movimento.valorFaturado)}</p>
                <p className="muted">já com o desconto</p>
              </div>
              <div className="card relatorio-kpi-card">
                <p className="muted">Desconto Concedido</p>
                <p className="relatorio-kpi-valor cor-danger">{moeda(data.movimento.valorDesconto)}</p>
                <p className="muted">nas OS faturadas</p>
              </div>
              <div className="card relatorio-kpi-card">
                <p className="muted">Ticket Médio</p>
                <p className="relatorio-kpi-valor">{moeda(data.movimento.ticketMedio)}</p>
                <p className="muted">por OS faturada</p>
              </div>
              <div className="card relatorio-kpi-card">
                <p className="muted">Tempo Médio</p>
                <p className="relatorio-kpi-valor">{tempo(data.movimento.tempoMedioHoras)}</p>
                <p className="muted">da abertura à conclusão</p>
              </div>
            </div>
            <p className="muted" style={{ marginTop: 8 }}>
              ⚠️ Cada contador acima conta pela <strong>sua própria data</strong> — uma OS aberta no mês
              passado e concluída neste aparece só em "Concluídas". Por isso os quatro não somam. O tempo
              médio é de <strong>calendário</strong> (inclui a espera pela aprovação do cliente e pela peça),
              não de horas de bancada.
            </p>

            <p className="section-label" style={{ marginTop: 24 }}>
              Produtividade por Executor
            </p>
            <div className={`card table-wrap${gerandoPdf ? ' pdf-expandido' : ''}`}>
              {data.linhas.length === 0 ? (
                <p className="muted">Nenhuma ordem de serviço concluída no período informado.</p>
              ) : (
                <table className="table table-compacta tabela-adensada">
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
                    </tr>
                  </thead>
                  <tbody>
                    {data.linhas.map((linha, indice) => {
                      const proxima = data.linhas[indice + 1]
                      const fimDoGrupo = mostrarSubtotais && (!proxima || proxima.idEmpresa !== linha.idEmpresa)
                      const subtotal = fimDoGrupo
                        ? data.subtotaisPorEmpresa.find((s) => s.idEmpresa === linha.idEmpresa)
                        : undefined
                      return (
                        <Fragment key={`${linha.idEmpresa}-${linha.idFuncionario}`}>
                          <tr>
                            <td>{linha.nomeEmpresa}</td>
                            <td>{linha.nomeFuncionario}</td>
                            <td className="mono" style={{ textAlign: 'right' }}>{linha.qtdOrdens}</td>
                            <td className="mono" style={{ textAlign: 'right' }}>{moeda(linha.valorServicos)}</td>
                            <td className="mono" style={{ textAlign: 'right' }}>{moeda(linha.valorPecas)}</td>
                            <td className="mono" style={{ textAlign: 'right' }}>{moeda(linha.valorTotal)}</td>
                            <td className="mono" style={{ textAlign: 'right' }}>{tempo(linha.tempoMedioHoras)}</td>
                          </tr>
                          {subtotal && (
                            <tr className="linha-subtotal">
                              <td colSpan={2}>
                                <strong>Subtotal — {subtotal.nomeEmpresa}</strong>
                              </td>
                              <td className="mono" style={{ textAlign: 'right' }}><strong>{subtotal.qtdOrdens}</strong></td>
                              <td className="mono" style={{ textAlign: 'right' }}><strong>{moeda(subtotal.valorServicos)}</strong></td>
                              <td className="mono" style={{ textAlign: 'right' }}><strong>{moeda(subtotal.valorPecas)}</strong></td>
                              <td className="mono" style={{ textAlign: 'right' }}><strong>{moeda(subtotal.valorTotal)}</strong></td>
                              <td />
                            </tr>
                          )}
                        </Fragment>
                      )
                    })}
                  </tbody>
                  <tfoot>
                    <tr>
                      <td colSpan={2}>
                        <strong>Total Geral</strong>
                      </td>
                      <td className="mono" style={{ textAlign: 'right' }}><strong>{data.totalGeral.qtdOrdens}</strong></td>
                      <td className="mono" style={{ textAlign: 'right' }}><strong>{moeda(data.totalGeral.valorServicos)}</strong></td>
                      <td className="mono" style={{ textAlign: 'right' }}><strong>{moeda(data.totalGeral.valorPecas)}</strong></td>
                      <td className="mono" style={{ textAlign: 'right' }}><strong>{moeda(data.totalGeral.valorTotal)}</strong></td>
                      <td />
                    </tr>
                  </tfoot>
                </table>
              )}
            </div>
            {data.linhas.length > 0 && (
              <p className="muted" style={{ marginTop: 8 }}>
                O <strong>Nº OS</strong> do Total Geral conta ordens <strong>distintas</strong> — uma OS com
                dois executores aparece para cada um deles, então a coluna não soma. Os valores são
                <strong> brutos</strong>: o desconto do cabeçalho não é rateado por executor (é concessão de
                quem fechou o negócio, não de quem trabalhou) e aparece no Movimento, acima. A linha
                <strong> (SEM EXECUTOR)</strong> é o que ninguém assumiu — normalmente as peças.
              </p>
            )}
          </div>
        )}
      </div>

      {modalAberto && (
        <FiltrosComissoesModal
          valores={rascunho}
          aoMudar={(parcial) => setRascunho((r) => ({ ...r, ...parcial }))}
          ehAdmin={!!ehAdmin}
          empresas={empresas ?? []}
          podeGerar={podeGerarRascunho}
          primeiraVez={!relatorioGerado}
          aoGerar={handleGerar}
          aoFechar={relatorioGerado ? () => setModalAberto(false) : () => navigate(-1)}
        />
      )}
    </div>
  )
}
