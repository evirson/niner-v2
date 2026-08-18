import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { gerarBlobComprovanteVenda, gerarQrCodeDataUrl, montarLinhasComprovanteVenda } from '../../lib/comprovante'
import { ApiError } from '../../lib/api'
import { buscarEmiteFiscalAposVenda } from '../../lib/configuracaoGeral'
import { buscarComprovanteVenda, emitirNfce, type ResultadoEmissaoNfce } from '../../lib/pdv'
import { compartilharArquivo } from '../../lib/compartilhamento'
import { montarLinkWhatsApp } from '../../lib/whatsapp'
import { IconeFechar, IconeWhatsapp } from '../../components/Icones'
import EnviarWhatsAppModal from '../../components/EnviarWhatsAppModal'
import Toast from '../../components/Toast'

/** Situações de emissão que valem como "sucesso" pro operador — a venda saiu bem das duas
 *  (AUTORIZADO) ou vai sair (CONTINGENCIA/EM_PROCESSAMENTO); as outras precisam de atenção. */
const SITUACOES_SUCESSO = new Set(['AUTORIZADO', 'CONTINGENCIA', 'EM_PROCESSAMENTO'])

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
 * jamais reemite nada): assim que o modal abre, dispara `POST .../nfce` em paralelo à papeleta já
 * visível (F3: a venda não espera a SEFAZ pra o operador ver o cupom). `null` de volta (204, F12)
 * não muda nada na tela — é como se o módulo fiscal não existisse. Quando volta um resultado, um
 * Toast avisa o operador e a query do comprovante é invalidada: como a emissão gravou
 * `documento_fiscal`, o próximo fetch já traz `dadosFiscais` preenchido e a papeleta vira DANFCE
 * na hora (chave, QR Code, protocolo) sem o operador precisar fechar/reabrir o popup.
 *
 * **Emissão automática × manual (2026-08-19, `cfg_geral.cfg_emite_fiscal_apos_venda`)** — o
 * disparo automático acima só acontece quando o parâmetro está ligado (Parâmetros do Sistema >
 * Fiscal). Desligado: nenhum `POST .../nfce` automático — a papeleta fica como sempre foi, e um
 * botão "Emitir Nota Fiscal" no rodapé deixa o operador acionar na hora que quiser (mesma função
 * `emitirNfce`, mesmo tratamento de resultado). O botão nunca aparece em `reimpressao` — reimprimir
 * não é (e não deve virar) um jeito de reemitir documento fiscal.
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
  const { data: configFiscal } = useQuery({
    queryKey: ['emite-fiscal-apos-venda'],
    queryFn: buscarEmiteFiscalAposVenda,
    enabled: !reimpressao,
  })

  const [modalWhatsAppAberto, setModalWhatsAppAberto] = useState(false)
  const [enviandoWhatsApp, setEnviandoWhatsApp] = useState(false)
  const [erroWhatsApp, setErroWhatsApp] = useState<string | null>(null)
  const [resultadoFiscal, setResultadoFiscal] = useState<ResultadoEmissaoNfce | null>(null)
  const [emitindoManualmente, setEmitindoManualmente] = useState(false)
  const [qrDataUrl, setQrDataUrl] = useState<string | null>(null)
  const emissaoDisparadaRef = useRef(false)

  function processarResultadoEmissao(resultado: ResultadoEmissaoNfce | null) {
    if (!resultado) return // F12: fiscal desligado, nada a mostrar
    setResultadoFiscal(resultado)
    queryClient.invalidateQueries({ queryKey: ['comprovante-venda', idVenda] })
  }

  function erroDeComunicacao(): ResultadoEmissaoNfce {
    // Falha ao CHAMAR o endpoint (rede, 5xx) — diferente de uma emissão que terminou
    // rejeitada/em contingência (essas voltam 200 com o resultado, não erro HTTP). A venda já
    // está gravada de qualquer forma; só avisa que a tentativa de emitir não completou.
    return {
      situacao: 'FALHA_COMUNICACAO',
      idDocumentoFiscal: 0,
      chaveAcesso: null,
      protocolo: null,
      cStat: null,
      mensagem: 'Não foi possível falar com o serviço fiscal. A venda está registrada.',
    }
  }

  // Dispara uma vez só, nunca em reimpressão e só quando "emitir automaticamente" está ligado
  // (Parâmetros do Sistema > Fiscal, 2026-08-19) — aguarda `configFiscal` carregar antes de
  // decidir, pra não vazar uma emissão automática enquanto o parâmetro ainda está desconhecido.
  // Efeito próprio (não dentro do useQuery) porque a papeleta tem que abrir e ficar utilizável
  // ANTES da SEFAZ responder — a emissão é só mais uma coisa acontecendo em paralelo, não uma
  // condição pra mostrar o comprovante.
  useEffect(() => {
    if (reimpressao || emissaoDisparadaRef.current) return
    if (!configFiscal?.cfgEmiteFiscalAposVenda) return
    emissaoDisparadaRef.current = true
    emitirNfce(idVenda).then(processarResultadoEmissao).catch(() => setResultadoFiscal(erroDeComunicacao()))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [idVenda, reimpressao, configFiscal])

  async function emitirManualmente() {
    setEmitindoManualmente(true)
    try {
      processarResultadoEmissao(await emitirNfce(idVenda))
    } catch {
      setResultadoFiscal(erroDeComunicacao())
    } finally {
      setEmitindoManualmente(false)
    }
  }

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
  const dadosFiscais = comprovante?.dadosFiscais ?? null

  async function confirmarEnvioWhatsApp(telefone: string) {
    if (!comprovante) return
    setEnviandoWhatsApp(true)
    setErroWhatsApp(null)
    try {
      const blob = gerarBlobComprovanteVenda(linhas, qrDataUrl)
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
          aria-label={reimpressao ? 'Reimpressão de papeleta de venda' : 'Papeleta de venda'}
          onClick={(e) => e.stopPropagation()}
          style={{ display: 'flex', flexDirection: 'column', overflow: 'hidden' }}
        >
          <div className="lightbox-topo" style={{ flexShrink: 0 }}>
            <h2 style={{ margin: 0 }}>{reimpressao ? 'Reimpressão de Papeleta de Venda' : 'Papeleta de Venda'}</h2>
            <button type="button" className="btn ghost btn-fechar-tela" onClick={aoFechar} aria-label="Fechar" title="Fechar">
              <IconeFechar />
            </button>
          </div>

          {!reimpressao && configFiscal && !configFiscal.cfgEmiteFiscalAposVenda && !dadosFiscais && (
            <div style={{ display: 'flex', justifyContent: 'flex-end', flexShrink: 0, marginBottom: 8 }}>
              <button type="button" className="btn ghost" disabled={!comprovante || emitindoManualmente} onClick={emitirManualmente}>
                {emitindoManualmente ? 'Emitindo…' : 'Emitir Nota Fiscal'}
              </button>
            </div>
          )}

          <div style={{ overflowY: 'auto', flex: 1, minHeight: 0 }}>
            {isLoading ? (
              <p className="muted">Carregando…</p>
            ) : error ? (
              <p className="erro">{error instanceof ApiError ? error.message : 'Não foi possível carregar a papeleta.'}</p>
            ) : dadosFiscais ? (
              <div className="papeleta-fiscal-imprimir">
                <pre className="papeleta-preview">{linhas.join('\n')}</pre>
                {qrDataUrl && (
                  <div className="papeleta-qrcode">
                    <img src={qrDataUrl} alt={`QR Code da NFC-e ${dadosFiscais.chaveAcesso}`} />
                  </div>
                )}
              </div>
            ) : (
              <pre className="papeleta-preview papeleta-imprimir">{linhas.join('\n')}</pre>
            )}
          </div>

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
