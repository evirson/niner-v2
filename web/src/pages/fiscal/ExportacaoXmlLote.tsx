import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import GaugeProgresso from '../../components/GaugeProgresso'
import { IconeDocumentoFiscal } from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { hojeISO } from '../../lib/datas'
import { dataParaIso, dataValida, isoParaData, mascararData } from '../../lib/masks'
import { listarEmpresasFiscal } from '../../lib/fiscalConfiguracao'
import {
  DownloadCancelado,
  baixarTodasAsPartes,
  resumirExportacaoXml,
} from '../../lib/exportacaoXmlLote'

const CHAVE_TELA = 'fiscal.exportacao-xml.tela'

/** Período padrão: mês corrente (1º dia até hoje) — é o recorte que o contador pede quase sempre. */
function primeiroDiaDoMesISO(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`
}

/**
 * Exportação de XML em Lote (`docs/telas/exportacao-xml-lote.md`, tela `fiscal.download` do §11.2
 * do estudo fiscal) — baixa num único ZIP o XML autorizado de todas as NFC-e/NF-e emitidas pela
 * empresa num período, para entregar ao contador. ADMIN-only.
 *
 * <p>A tela faz uma **pré-conferência** antes de deixar baixar: quantas notas o período tem,
 * quantas já têm o XML arquivado, quantos eventos de cancelamento entram e como o arquivo vai se
 * chamar. É isso que impede o clique que geraria um pacote vazio — e que torna visível a nota
 * autorizada cujo XML ainda não subiu ao bucket, que sumiria do pacote sem nenhum aviso.
 */
export default function ExportacaoXmlLote() {
  const [idEmpresa, setIdEmpresa] = useState<number | null>(null)
  const [dataInicialTexto, setDataInicialTexto] = useState(isoParaData(primeiroDiaDoMesISO()))
  const [dataFinalTexto, setDataFinalTexto] = useState(isoParaData(hojeISO()))
  const [modelo, setModelo] = useState<'' | '65' | '55'>('')
  const [baixando, setBaixando] = useState(false)
  /** Progresso quando o período sai em mais de um ZIP — `null` fora de um download. */
  const [parteAtual, setParteAtual] = useState<{ feita: number; total: number } | null>(null)
  const [toast, setToast] = useState('')
  const [toastTipo, setToastTipo] = useState<TipoToast>('erro')
  /**
   * ⭐ A conferência só roda depois do clique em **Gerar Dados** (2026-08-26, pedido do dono do
   * produto: *"coloque um botão gerar dados, para facilitar o entendimento do usuário"*). Antes ela
   * disparava sozinha a cada tecla numa data, e a tela ficava piscando números sem que o lojista
   * soubesse por quê — ou o que ele tinha feito para provocá-los.
   *
   * ⚠️ Trocar um filtro **apaga** o resultado (ver `aoTrocarFiltro`): número na tela que não
   * corresponde aos filtros visíveis é pior que tela vazia — ele parece atual e não é.
   */
  const [gerado, setGerado] = useState(false)

  const { data: empresas } = useQuery({ queryKey: ['fiscal-empresas'], queryFn: listarEmpresasFiscal })

  useEffect(() => {
    if (idEmpresa === null && empresas && empresas.length > 0) setIdEmpresa(empresas[0].idEmpresa)
  }, [idEmpresa, empresas])

  const dataInicialIso = dataValida(dataInicialTexto) ? dataParaIso(dataInicialTexto) : null
  const dataFinalIso = dataValida(dataFinalTexto) ? dataParaIso(dataFinalTexto) : null
  const filtrosValidos = idEmpresa !== null && !!dataInicialIso && !!dataFinalIso

  const filtros = filtrosValidos
    ? {
        idEmpresa: idEmpresa as number,
        dataInicial: dataInicialIso as string,
        dataFinal: dataFinalIso as string,
        modelo: modelo ? Number(modelo) : undefined,
      }
    : null

  const {
    data: resumo,
    isFetching: buscandoResumo,
    error: erroResumo,
    refetch: refazerResumo,
  } = useQuery({
    queryKey: ['exportacao-xml-resumo', idEmpresa, dataInicialIso, dataFinalIso, modelo],
    queryFn: () => resumirExportacaoXml(filtros!),
    enabled: filtrosValidos && gerado,
    // ⚠️ Sem `placeholderData`: manter o resultado anterior enquanto a próxima consulta corre faria
    // a tela mostrar a contagem de OUTRO período durante a espera, com cara de resultado atual.
  })

  /** Qualquer mexida num filtro invalida o que está na tela — ver o comentário de `gerado`. */
  function aoTrocarFiltro<T>(setter: (v: T) => void) {
    return (valor: T) => {
      setGerado(false)
      setter(valor)
    }
  }

  const podeGerar = filtrosValidos && !buscandoResumo && !baixando
  const podeBaixar = !!resumo && resumo.documentosComXml > 0 && !baixando && !buscandoResumo

  async function baixar() {
    if (!filtros || !resumo) return
    setBaixando(true)
    try {
      await baixarTodasAsPartes(filtros, resumo, (feita, total) => setParteAtual({ feita, total }))
      setToastTipo('sucesso')
      setToast(
        resumo.totalPartes > 1
          ? `${resumo.documentosComXml} XML exportado(s) em ${resumo.totalPartes} arquivos ZIP.`
          : `${resumo.documentosComXml} XML exportado(s) em "${resumo.nomeArquivo}".`,
      )
    } catch (e) {
      if (e instanceof DownloadCancelado) return // desistir não é erro
      setToastTipo('erro')
      setToast(e instanceof ApiError ? e.message : 'Não foi possível gerar o arquivo ZIP.')
    } finally {
      setBaixando(false)
      setParteAtual(null)
    }
  }

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeDocumentoFiscal size={34} />
            <h1>Exportação de XML em Lote</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela={CHAVE_TELA} />
            <BotaoFecharTela />
          </div>
        </div>

        <div className="card filtros-bar">
          <select
            autoFocus
            value={idEmpresa ?? ''}
            onChange={(e) => aoTrocarFiltro(setIdEmpresa)(Number(e.target.value))}
            aria-label="Empresa"
          >
            {(empresas ?? []).map((emp) => (
              <option key={emp.idEmpresa} value={emp.idEmpresa}>
                {emp.razaoSocial}
              </option>
            ))}
          </select>
          {/* Data é sempre texto mascarado (dd/mm/aaaa) — nunca <input type="date">: o widget
              nativo não sabe "selecionar tudo e sobrescrever ao digitar". */}
          <input
            className="mono"
            placeholder="dd/mm/aaaa"
            value={dataInicialTexto}
            onChange={(e) => aoTrocarFiltro(setDataInicialTexto)(mascararData(e.target.value))}
            onFocus={(e) => e.target.select()}
            aria-label="Emissão inicial"
            style={{ maxWidth: 130 }}
          />
          <input
            className="mono"
            placeholder="dd/mm/aaaa"
            value={dataFinalTexto}
            onChange={(e) => aoTrocarFiltro(setDataFinalTexto)(mascararData(e.target.value))}
            onFocus={(e) => e.target.select()}
            aria-label="Emissão final"
            style={{ maxWidth: 130 }}
          />
          <select value={modelo} onChange={(e) => aoTrocarFiltro(setModelo)(e.target.value as '' | '65' | '55')} aria-label="Modelo">
            <option value="">NFC-e e NF-e</option>
            <option value="65">Só NFC-e (65)</option>
            <option value="55">Só NF-e (55)</option>
          </select>
          {/* ⭐ A conferência não roda mais sozinha: o lojista escolhe os filtros e clica aqui. */}
          {/* ⚠️ `refetch` quando JÁ gerou: com os mesmos filtros, `setGerado(true)` sobre um
              estado que já é `true` é descartado pelo React e NADA acontece. Depois de um 500
              transitório, o card mostrava o erro, o botão continuava habilitado, e clicar de novo
              não reagia — a única saída era mexer num filtro e desfazer, para mudar a queryKey. */}
          <button
            type="button"
            className="btn"
            disabled={!podeGerar}
            onClick={() => {
              if (gerado) refazerResumo()
              else setGerado(true)
            }}
          >
            {buscandoResumo ? 'Consultando…' : 'Gerar Dados'}
          </button>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card" style={{ padding: 24 }}>
          <p className="section-label">O que vai no arquivo</p>

          {!filtrosValidos ? (
            <p className="muted">Escolha a empresa e informe a data inicial e final de emissão.</p>
          ) : buscandoResumo ? (
            /* ⭐ Consultar o período varre a tabela de documentos fiscais e pode demorar num
               período largo. Sem o anel girando, uma tela parada por alguns segundos parece
               travada, e o lojista clica de novo (2026-08-26, pedido do dono do produto). */
            <div style={{ display: 'flex', justifyContent: 'center', padding: '12px 0' }}>
              <GaugeProgresso rotulo="Consultando as notas do período…" />
            </div>
          ) : erroResumo ? (
            /* ⚠️ Toast (convenção do projeto: erro nunca em banner inline) E o texto no card. Aqui
               as duas coisas se justificam: a mensagem do servidor é acionável ("período inicial
               maior que o final", "período acima do máximo") e a atenção do lojista está na barra
               de filtros, no TOPO — o banner sozinho ficava onde ele já parou de olhar. O texto no
               card permanece porque é estado persistente da área ("não consegui conferir"), que um
               toast de 6 s perderia. */
            <ErroDoResumo erro={erroResumo} aoAvisar={(m) => { setToastTipo('erro'); setToast(m) }} />
          ) : !resumo ? (
            <p className="muted">
              Confira os filtros acima e clique em <strong>Gerar Dados</strong> para ver o que vai no arquivo.
            </p>
          ) : (
            <>
              <p style={{ margin: '4px 0 0' }}>
                <strong>{resumo.documentosComXml}</strong> XML de nota fiscal
                {resumo.totalEventos > 0 && (
                  <>
                    {' '}e <strong>{resumo.totalEventos}</strong> XML de evento de cancelamento
                  </>
                )}{' '}
                — de {resumo.totalDocumentos} nota(s) emitida(s) no período.
              </p>
              <p className="muted" style={{ margin: '4px 0 0' }}>
                Arquivo: <span className="mono">{resumo.nomeArquivo}</span>
              </p>

              {/* ⚠️ Nota AUTORIZADA cujo XML ainda não subiu ao bucket tem valor fiscal e ficaria
                  de fora do pacote sem nada avisar. Este é o aviso — e SÓ para esse caso: esperar
                  resolve. */}
              {resumo.documentosPendentesArquivamento > 0 && (
                <p className="muted" style={{ margin: '10px 0 0' }}>
                  ⚠️ {resumo.documentosPendentesArquivamento} nota(s) com valor fiscal ainda não tiveram o XML
                  arquivado e vão ficar de fora. Elas aparecem no <span className="mono">relatorio.csv</span> marcadas
                  como “(nao arquivado)”. O arquivamento é automático e tenta de novo a cada 10 minutos — aguarde e
                  repita a exportação.
                </p>
              )}

              {/* ⛔ Mensagem OPOSTA à de cima, e é por isso que as duas contagens são separadas
                  (2026-08-26): rejeitada/denegada/não emitida NUNCA vai ter XML. Dizer "aguarde o
                  arquivamento" aqui mandaria o lojista esperar por um arquivo que não vem — e num
                  período real deste banco eram 36 rejeitadas contra 1 pendência de verdade. */}
              {resumo.documentosSemValorFiscal > 0 && (
                <p className="muted" style={{ margin: '10px 0 0' }}>
                  {resumo.documentosSemValorFiscal} nota(s) do período foram rejeitadas, denegadas ou não chegaram a
                  ser emitidas: não existem como documento fiscal e <strong>não geram XML</strong>. Ficam no{' '}
                  <span className="mono">relatorio.csv</span> para conferência, mas não há o que esperar nem o que
                  exportar delas.
                </p>
              )}

              {/* ⭐ Período grande é particionado, não recusado (2026-08-26). O aviso é informativo
                  (não bloqueia), e diz as duas coisas que mudam a expectativa do lojista: virão N
                  arquivos, e o navegador vai pedir permissão para baixar vários. Sem esse segundo
                  aviso, um "permitir" negado por engano faria as partes seguintes sumirem sem erro. */}
              {resumo.totalPartes > 1 && (
                <p className="muted" style={{ margin: '10px 0 0' }}>
                  ⚠️ O período tem {resumo.totalDocumentos} notas, acima de {resumo.limiteDocumentos} por arquivo:
                  será dividido em <strong>{resumo.totalPartes} arquivos ZIP</strong>, baixados em sequência. O
                  navegador vai pedir permissão para baixar vários arquivos — é preciso permitir, senão só o
                  primeiro chega.
                </p>
              )}

              {resumo.documentosComXml === 0 && (
                <p className="erro" style={{ margin: '10px 0 0' }}>
                  {resumo.totalDocumentos === 0
                    ? 'Nenhuma nota fiscal foi emitida por esta empresa no período selecionado.'
                    : 'Nenhuma das notas do período tem XML arquivado ainda — não há o que exportar.'}
                </p>
              )}

              <div style={{ marginTop: 20 }}>
                <button type="button" className="btn" disabled={!podeBaixar} onClick={baixar}>
                  {baixando
                    ? parteAtual
                      ? `Baixando… ${parteAtual.feita} de ${parteAtual.total}`
                      : 'Gerando ZIP…'
                    : resumo.totalPartes > 1
                      ? `Baixar ${resumo.totalPartes} arquivos ZIP`
                      : 'Baixar ZIP'}
                </button>
                <p className="muted" style={{ margin: '8px 0 0' }}>
                  O navegador vai perguntar onde salvar — ou baixar direto para a pasta de downloads, conforme a
                  configuração dele.
                </p>
              </div>
            </>
          )}

          {baixando && (
            <div style={{ marginTop: 24 }}>
              <GaugeProgresso rotulo="Lendo os XMLs e montando o arquivo ZIP…" />
            </div>
          )}
        </div>
      </div>

      {toast && <Toast mensagem={toast} tipo={toastTipo} aoFechar={() => setToast('')} />}
    </div>
  )
}

/**
 * Mostra a falha de "Gerar Dados" no card **e** avisa por Toast uma vez.
 *
 * ⚠️ Componente próprio para o `useEffect` do aviso: chamar `setToast` no meio do render do pai
 * entraria em laço. O `aoAvisar` roda só quando a mensagem muda, então clicar de novo com o mesmo
 * erro não repete o toast, e um erro diferente avisa de novo.
 */
function ErroDoResumo({ erro, aoAvisar }: { erro: unknown; aoAvisar: (mensagem: string) => void }) {
  const mensagem = erro instanceof ApiError ? erro.message : 'Não foi possível conferir o período.'
  useEffect(() => {
    aoAvisar(mensagem)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mensagem])
  return <p className="erro">{mensagem}</p>
}
