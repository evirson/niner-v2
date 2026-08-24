import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { gerarBlobComprovanteVenda, gerarQrCodeDataUrl, montarLinhasComprovanteVenda } from '../../lib/comprovante'
import { gerarBlobDanfce } from '../../lib/danfce'
import DanfeModal from '../vendas/DanfeModal'
import { ApiError } from '../../lib/api'
import { buscarEmiteFiscalAposVenda, buscarPermiteQtdDecimal } from '../../lib/configuracaoGeral'
import { buscarComprovanteVenda, emitirNfce, type ResultadoEmissaoNfce } from '../../lib/pdv'
import { compartilharArquivo } from '../../lib/compartilhamento'
import { montarLinkWhatsApp } from '../../lib/whatsapp'
import { formatarMoeda, mascararCpfCnpj } from '../../lib/masks'
import { IconeFechar, IconeWhatsapp } from '../../components/Icones'
import EnviarWhatsAppModal from '../../components/EnviarWhatsAppModal'
import Toast from '../../components/Toast'
import DanfceImprimir from './DanfceImprimir'

/** Situações de emissão que valem como "sucesso" pro operador — a venda saiu bem das duas
 *  (AUTORIZADO) ou vai sair (CONTINGENCIA/EM_PROCESSAMENTO); as outras precisam de atenção. */
const SITUACOES_SUCESSO = new Set(['AUTORIZADO', 'CONTINGENCIA', 'EM_PROCESSAMENTO'])

/** NF-e — o documento dela e o DANFE em A4, nao o DANFCE termico. Venda a pessoa juridica sai
 *  neste modelo desde 2026-08-24 (a fisica continua em NFC-e, modelo 65). */
const MODELO_NFE = 55

/**
 * Papeleta de venda, formatada pra bobina térmica de 80mm (2026-08-06). Abre automaticamente
 * logo após o F5 efetivar a venda com sucesso (`Pdv.tsx`). Mesmo mecanismo do Comprovante de
 * Pagamento de Crediário: "Imprimir" usa o diálogo nativo do navegador (só `.papeleta-imprimir`
 * fica visível na impressão, via CSS — ver `styles.css`) — o próprio diálogo de impressão já
 * oferece "Salvar como PDF", então o botão dedicado saiu (2026-08-19, pedido do dono do
 * produto). Fechar virou o "✕" no cabeçalho (`.lightbox-topo`), não mais um botão "Fechar" no
 * rodapé — o rodapé agora só tem "Enviar por WhatsApp" (esquerda) e "Imprimir" (direita).
 *
 * `reimpressao` (2026-08-06) — usado pelo botão "Reimprimir papeleta" do popup de detalhe da
 * Pesquisa de Vendas (`DetalheVendaModal.tsx`; a tela dedicada `ReimpressaoPapeletaVenda.tsx`
 * que usava isso antes saiu do projeto em 2026-08-18 por ficar redundante com esse botão): mesmo
 * componente, muda só o título do popup e injeta uma linha "REIMPRESSÃO DE PAPELETA DE VENDA" +
 * "Impresso em: <data/hora atual>" no final da papeleta (`montarLinhasComprovanteVenda`) — nunca
 * no fluxo normal pós-F5.
 *
 * "Enviar por WhatsApp" (2026-08-07) — mesmo mecanismo do Comprovante de Pagamento de Crediário:
 * abre `EnviarWhatsAppModal` pra confirmar/editar o celular (pré-preenchido com `cliente.
 * telefone`) antes de gerar qualquer coisa; só ao confirmar ali é que a papeleta vira Blob
 * (mesmo motor de "Salvar PDF"), sobe pro cache temporário da API e abre o link `wa.me`.
 *
 * **DANFCE (§9.6, bloco B7, 2026-08-17)** — só no fluxo normal (nunca em `reimpressao`, que
 * jamais reemite nada). Quando volta um resultado, um Toast avisa o operador e a query do
 * comprovante é invalidada: como a emissão gravou `documento_fiscal`, o próximo fetch já traz
 * `dadosFiscais` preenchido e a papeleta vira DANFCE na hora (chave, QR Code, protocolo).
 *
 * **Pergunta de CPF antes de emitir (2026-08-19)** — quando `cfg_geral.cfg_emite_fiscal_apos_venda`
 * está ligado (Parâmetros do Sistema > Fiscal), o modal NÃO mostra a papeleta com valor não fiscal
 * nem emite nada sozinho: primeiro aparece uma tela de confirmação com cliente/valor/formas de
 * pagamento perguntando se o CPF do cliente da venda entra na nota. "Sim" emite com o documento do
 * cliente (`comprovante.documentoCliente`); "não" (ou cliente sem documento cadastrado) emite pra
 * consumidor não identificado — nunca decidido sozinho a partir do cliente vinculado à venda. Só
 * depois da resposta (e da SEFAZ responder) é que a papeleta/DANFCE aparece com os botões de
 * Imprimir/WhatsApp liberados. Desligado: nenhuma pergunta, nenhum disparo automático — a papeleta
 * aparece direto como sempre foi, e um botão "Emitir Nota Fiscal" no rodapé abre a mesma tela de
 * confirmação pra o operador acionar na hora que quiser. Nunca em `reimpressao` — reimprimir não é
 * (e não deve virar) um jeito de reemitir documento fiscal.
 */
export default function ComprovantePapeletaModal({
  idVenda,
  reimpressao = false,
  aoFechar,
}: {
  idVenda: number
  reimpressao?: boolean
  aoFechar: () => void
}) {
  const queryClient = useQueryClient()
  const {
    data: comprovante,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['comprovante-venda', idVenda],
    queryFn: () => buscarComprovanteVenda(idVenda),
    retry: 1,
  })

  // Nunca buscado em reimpressão — o parâmetro só decide o comportamento do fluxo normal
  // pós-venda, e reimpressão nunca emite nada, automático ou manual (ver Obs1 do pedido).
  const { data: configFiscal, isFetching: buscandoConfigFiscal } = useQuery({
    queryKey: ['emite-fiscal-apos-venda'],
    queryFn: buscarEmiteFiscalAposVenda,
    enabled: !reimpressao,
  })

  const { data: cfgQtdDecimal } = useQuery({ queryKey: ['permite-qtd-decimal'], queryFn: buscarPermiteQtdDecimal })
  const permiteQtdDecimal = cfgQtdDecimal?.cfgPermiteQtdDecimal ?? true

  const [modalWhatsAppAberto, setModalWhatsAppAberto] = useState(false)
  const [enviandoWhatsApp, setEnviandoWhatsApp] = useState(false)
  const [erroWhatsApp, setErroWhatsApp] = useState<string | null>(null)
  const [resultadoFiscal, setResultadoFiscal] = useState<ResultadoEmissaoNfce | null>(null)
  /** Id do documento fiscal cujo DANFE A4 esta aberto — so NF-e 55 chega aqui. */
  const [danfeAberto, setDanfeAberto] = useState<number | null>(null)
  const [respondendoCpf, setRespondendoCpf] = useState(false)
  const [pedirCpfManual, setPedirCpfManual] = useState(false)
  const [qrDataUrl, setQrDataUrl] = useState<string | null>(null)
  const danfceRef = useRef<HTMLDivElement>(null)

  function processarResultadoEmissao(resultado: ResultadoEmissaoNfce | null) {
    if (!resultado) return // F12: fiscal desligado, nada a mostrar
    setResultadoFiscal(resultado)
    queryClient.invalidateQueries({ queryKey: ['comprovante-venda', idVenda] })
    // ⚠️ Venda a pessoa jurídica sai em NF-e 55, e o documento dela é o DANFE em A4 — na
    // impressora comum, não na térmica (2026-08-24, decisão do dono do produto). A papeleta
    // continua saindo, mas ela nunca substituiu a nota.
    //
    // Só abre quando a nota EXISTE de verdade: rejeitada, não emitida ou falha de comunicação não
    // têm DANFE para imprimir, e abrir o popup ali daria erro de "documento não encontrado" em
    // cima de uma mensagem que já explicava o problema.
    if (resultado.modelo === MODELO_NFE && SITUACOES_SUCESSO.has(resultado.situacao)
        && resultado.idDocumentoFiscal > 0) {
      setDanfeAberto(resultado.idDocumentoFiscal)
    }
  }

  function erroDeComunicacao(): ResultadoEmissaoNfce {
    // Falha ao CHAMAR o endpoint (rede, 5xx) — diferente de uma emissão que terminou
    // rejeitada/em contingência (essas voltam 200 com o resultado, não erro HTTP). A venda já
    // está gravada de qualquer forma; só avisa que a tentativa de emitir não completou.
    return {
      situacao: 'FALHA_COMUNICACAO',
      idDocumentoFiscal: 0,
      // 0 = não chegou a haver documento, então não há DANFE nem DANFCE para imprimir.
      modelo: 0,
      chaveAcesso: null,
      protocolo: null,
      cStat: null,
      mensagem: 'Não foi possível falar com o serviço fiscal. A venda está registrada.',
    }
  }

  async function confirmarEmissao(incluirCpf: boolean) {
    setRespondendoCpf(true)
    try {
      processarResultadoEmissao(await emitirNfce(idVenda, incluirCpf))
    } catch (e) {
      // ⚠️ O catch mudo ENGOLIA a mensagem do servidor (2026-08-24). Um 409 dizendo "cadastre a
      // regra em Perfil Fiscal" virava "não foi possível falar com o serviço fiscal" — e o
      // operador ia procurar problema de rede enquanto faltava um cadastro. A mensagem genérica
      // só vale quando o erro NÃO tem explicação (rede caiu, 5xx sem corpo).
      setResultadoFiscal(e instanceof ApiError && e.message
        ? { ...erroDeComunicacao(), situacao: 'NAO_EMITIDO', mensagem: e.message }
        : erroDeComunicacao())
    } finally {
      setRespondendoCpf(false)
      setPedirCpfManual(false)
    }
  }

  // Aguarda `configFiscal` carregar antes de decidir qualquer coisa (nunca em reimpressão), pra
  // não vazar a papeleta não fiscal enquanto o parâmetro ainda está desconhecido.
  //
  // ⚠️ `!== undefined` sozinho não bastava (achado em 2026-08-20): navegando pelo menu (SPA, sem
  // recarga), o valor em CACHE chega na primeira renderização mesmo desatualizado — o ADMIN
  // desligava "Emitir fiscal após a venda" em Parâmetros e o popup de CPF ainda abria sozinho na
  // venda seguinte, na janela do refetch. `isFetching` fecha essa janela. Mesmo tratamento e mesmo
  // motivo de `DevolucaoProduto.tsx` (`buscandoCfgExigeVenda`).
  const configFiscalCarregado = reimpressao || (configFiscal !== undefined && !buscandoConfigFiscal)
  const dadosFiscais = comprovante?.dadosFiscais ?? null
  const perguntaAutomatica =
    !reimpressao && configFiscalCarregado && configFiscal?.cfgEmiteFiscalAposVenda === true && !resultadoFiscal && !dadosFiscais
  const mostrarPerguntaCpf = perguntaAutomatica || pedirCpfManual
  const documentoCliente = comprovante?.documentoCliente ?? null
  const clienteFisicaJuridica = (documentoCliente?.length ?? 0) <= 11

  useEffect(() => {
    if (!comprovante?.dadosFiscais) {
      setQrDataUrl(null)
      return
    }
    let cancelado = false
    gerarQrCodeDataUrl(comprovante.dadosFiscais.qrCodeUrl).then((url) => {
      if (!cancelado) setQrDataUrl(url)
    })
    return () => {
      cancelado = true
    }
  }, [comprovante?.dadosFiscais])

  const linhas = comprovante ? montarLinhasComprovanteVenda(comprovante, reimpressao) : []

  // O popup se chamava sempre "Papeleta de Venda", mesmo quando o conteúdo já era o DANFCE — em
  // homologação (a maioria dos ambientes de teste) o cupom nem escreve "DANFCE" em lugar nenhum
  // (mostra a tarja "SEM VALOR FISCAL"), então nada no popup deixava claro que aquilo já era a
  // nota fiscal. 2026-08-19: título muda quando há dado fiscal.
  const titulo = reimpressao
    ? dadosFiscais
      ? 'Reimpressão de Nota Fiscal — NFC-e'
      : 'Reimpressão de Papeleta de Venda'
    : dadosFiscais
      ? 'Nota Fiscal — NFC-e'
      : 'Papeleta de Venda'

  async function confirmarEnvioWhatsApp(telefone: string) {
    if (!comprovante) return
    setEnviandoWhatsApp(true)
    setErroWhatsApp(null)
    try {
      // DANFE (2026-08-19): o PDF do envio por WhatsApp é uma CAPTURA do próprio DOM já
      // renderizado (`danfceRef`), não mais texto monoespaçado — mesmo motor html2canvas+jsPDF
      // dos relatórios, garantindo que o PDF sai idêntico ao que o operador está vendo na tela.
      // Papeleta comum (sem fiscal) continua no PDF de texto de sempre.
      const blob = dadosFiscais && danfceRef.current
        ? await gerarBlobDanfce(danfceRef.current)
        : gerarBlobComprovanteVenda(linhas, qrDataUrl)
      const link = await compartilharArquivo(blob, `papeleta-venda-${comprovante.idVenda}.pdf`)
      const mensagem =
        `Olá${comprovante.nomeCliente ? `, ${comprovante.nomeCliente}` : ''}! Segue a papeleta da sua compra:\n${link}\n\n` +
        'O link expira em 24 horas.'
      window.open(montarLinkWhatsApp(telefone, mensagem), '_blank')
      setModalWhatsAppAberto(false)
    } catch (e) {
      setErroWhatsApp(e instanceof ApiError ? e.message : 'Não foi possível preparar o envio.')
    } finally {
      setEnviandoWhatsApp(false)
    }
  }

  return (
    <>
      <div className="modal-overlay" onClick={aoFechar}>
        <div
          className="modal modal-medio"
          role="dialog"
          aria-label={titulo}
          onClick={(e) => e.stopPropagation()}
          style={{ display: 'flex', flexDirection: 'column', overflow: 'hidden' }}
        >
          <div className="lightbox-topo" style={{ flexShrink: 0 }}>
            <h2 style={{ margin: 0 }}>{titulo}</h2>
            <button type="button" className="btn ghost btn-fechar-tela" onClick={aoFechar} aria-label="Fechar" title="Fechar">
              <IconeFechar />
            </button>
          </div>

          {!reimpressao && configFiscal && !configFiscal.cfgEmiteFiscalAposVenda && !dadosFiscais && !mostrarPerguntaCpf && (
            <div style={{ display: 'flex', justifyContent: 'flex-end', flexShrink: 0, marginBottom: 8 }}>
              <button type="button" className="btn ghost" disabled={!comprovante} onClick={() => setPedirCpfManual(true)}>
                Emitir Nota Fiscal
              </button>
            </div>
          )}

          <div style={{ overflowY: 'auto', flex: 1, minHeight: 0 }}>
            {isLoading || !configFiscalCarregado ? (
              <p className="muted">Carregando…</p>
            ) : error ? (
              <p className="erro">{error instanceof ApiError ? error.message : 'Não foi possível carregar a papeleta.'}</p>
            ) : mostrarPerguntaCpf && comprovante ? (
              <div style={{ padding: '4px 4px 8px' }}>
                <h3 style={{ marginTop: 0 }}>Confirmar Emissão da Nota Fiscal</h3>
                <p>
                  <strong>Cliente:</strong> {comprovante.nomeCliente ?? 'Consumidor não identificado'}
                </p>
                <p>
                  <strong>Valor total:</strong> {formatarMoeda(comprovante.totalAPagar)}
                </p>
                <p>
                  <strong>Formas de pagamento:</strong>
                </p>
                <ul style={{ marginTop: 0 }}>
                  {comprovante.pagamentos.map((p, i) => (
                    <li key={i}>
                      {p.nomeCarteira}: {formatarMoeda(p.valorPago)}
                    </li>
                  ))}
                </ul>
                {documentoCliente ? (
                  <>
                    <p>
                      <strong>CPF/CNPJ do cliente:</strong> {mascararCpfCnpj(documentoCliente, clienteFisicaJuridica)}
                    </p>
                    <p>Deseja incluir o CPF do cliente nesta nota fiscal?</p>
                    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                      <button type="button" className="btn" disabled={respondendoCpf} onClick={() => confirmarEmissao(true)}>
                        {respondendoCpf ? 'Emitindo…' : 'Sim, incluir CPF'}
                      </button>
                      <button type="button" className="btn ghost" disabled={respondendoCpf} onClick={() => confirmarEmissao(false)}>
                        Não, emitir para consumidor
                      </button>
                      {pedirCpfManual && (
                        <button type="button" className="btn ghost" disabled={respondendoCpf} onClick={() => setPedirCpfManual(false)}>
                          Cancelar
                        </button>
                      )}
                    </div>
                  </>
                ) : (
                  <>
                    <p>Este cliente não tem CPF/CNPJ cadastrado — a nota vai sair para consumidor não identificado.</p>
                    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                      <button type="button" className="btn" disabled={respondendoCpf} onClick={() => confirmarEmissao(false)}>
                        {respondendoCpf ? 'Emitindo…' : 'Confirmar e emitir'}
                      </button>
                      {pedirCpfManual && (
                        <button type="button" className="btn ghost" disabled={respondendoCpf} onClick={() => setPedirCpfManual(false)}>
                          Cancelar
                        </button>
                      )}
                    </div>
                  </>
                )}
              </div>
            ) : dadosFiscais && comprovante ? (
              <DanfceImprimir
                ref={danfceRef}
                comprovante={comprovante}
                qrDataUrl={qrDataUrl}
                permiteQtdDecimal={permiteQtdDecimal}
              />
            ) : (
              <pre className="papeleta-preview papeleta-imprimir">{linhas.join('\n')}</pre>
            )}
          </div>

          {!mostrarPerguntaCpf && (
            <div className="ajuda-rodape" style={{ flexShrink: 0 }}>
              <button
                type="button"
                className="btn ghost"
                disabled={!comprovante}
                onClick={() => setModalWhatsAppAberto(true)}
                style={{ display: 'flex', alignItems: 'center', gap: 6 }}
              >
                <IconeWhatsapp size={18} />
                Enviar por WhatsApp
              </button>
              <button type="button" className="btn" disabled={!comprovante} onClick={() => window.print()}>
                Imprimir
              </button>
            </div>
          )}
        </div>
      </div>

      {modalWhatsAppAberto && (
        <EnviarWhatsAppModal
          telefoneInicial={comprovante?.telefoneCliente ?? null}
          enviando={enviandoWhatsApp}
          erro={erroWhatsApp}
          aoConfirmar={confirmarEnvioWhatsApp}
          aoFechar={() => {
            setModalWhatsAppAberto(false)
            setErroWhatsApp(null)
          }}
        />
      )}

      {danfeAberto !== null && (
        <DanfeModal idDocumentoFiscal={danfeAberto} aoFechar={() => setDanfeAberto(null)} />
      )}

      {resultadoFiscal && (
        <Toast
          mensagem={resultadoFiscal.mensagem}
          tipo={SITUACOES_SUCESSO.has(resultadoFiscal.situacao) ? 'sucesso' : 'erro'}
          aoFechar={() => setResultadoFiscal(null)}
        />
      )}
    </>
  )
}
