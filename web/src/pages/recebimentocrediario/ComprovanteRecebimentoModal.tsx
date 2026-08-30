import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { gerarBlobComprovante, montarLinhasComprovante } from '../../lib/comprovante'
import { ApiError } from '../../lib/api'
import { buscarComprovanteRecebimento } from '../../lib/recebimentoCrediario'
import { compartilharArquivo } from '../../lib/compartilhamento'
import { montarLinkWhatsApp } from '../../lib/whatsapp'
import { IconeFechar, IconeWhatsapp } from '../../components/Icones'
import EnviarWhatsAppModal from '../../components/EnviarWhatsAppModal'
import { fecharAoClicarNoFundo } from '../../lib/modais'

/**
 * Comprovante de pagamento de crediário, formatado pra bobina térmica de 80mm (2026-07-30).
 * Abre automaticamente logo após um recebimento efetivado com sucesso (`RecebimentoCrediario.
 * tsx`). "Imprimir" usa o diálogo nativo do navegador (só `.comprovante-imprimir` fica visível
 * na impressão, via CSS — ver `styles.css`) — o próprio diálogo já oferece "Salvar como PDF",
 * então o botão dedicado saiu (2026-08-19, pedido do dono do produto). Fechar virou o "✕" no
 * cabeçalho, não mais um botão "Fechar" no rodapé — o rodapé agora só tem "Enviar por WhatsApp"
 * (esquerda) e "Imprimir" (direita).
 *
 * `reimpressao` (2026-08-06) — usado pela tela de Reimpressão de Papeleta de Recebimento de
 * Crediário (`ReimpressaoRecebimentoCrediario.tsx`): mesmo componente, muda só o título do popup
 * e troca o título do papel ("COMPROVANTE DE PAGAMENTO DE CREDIARIO" → "REIMPRESSÃO DE PAPELETA
 * DE RECEBIMENTO DE CREDIARIO") + acrescenta "Impresso em: <data/hora atual>" no final
 * (`montarLinhasComprovante`) — nunca no fluxo normal pós-efetivar.
 *
 * "Enviar por WhatsApp" (2026-08-07, estudo de caso aprovado) — clicar no botão SEMPRE abre
 * `EnviarWhatsAppModal` primeiro (pré-preenchido com `cliente.telefone`, mas editável — nunca
 * dispara o envio sem essa confirmação visual, pedido explícito). Só ao confirmar nesse popup é
 * que o PDF é gerado (mesmo motor de "Salvar PDF", como Blob em vez de baixar), sobe pro cache
 * temporário da API (`lib/compartilhamento.ts`, link válido por 24h, sem custo de object storage
 * — fica só em memória do lado da API) e abre um link `wa.me` (`lib/whatsapp.ts`) já com a
 * mensagem e o link de download preenchidos. Quem de fato envia é o operador, clicando em
 * "Enviar" na conversa do WhatsApp que abre — não é uma integração automática.
 */
export default function ComprovanteRecebimentoModal({
  idLoteRecebimento,
  reimpressao = false,
  aoFechar,
}: {
  idLoteRecebimento: number
  reimpressao?: boolean
  aoFechar: () => void
}) {
  const {
    data: comprovante,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['comprovante-recebimento', idLoteRecebimento],
    queryFn: () => buscarComprovanteRecebimento(idLoteRecebimento),
    retry: 1,
  })

  const [modalWhatsAppAberto, setModalWhatsAppAberto] = useState(false)
  const [enviandoWhatsApp, setEnviandoWhatsApp] = useState(false)
  const [erroWhatsApp, setErroWhatsApp] = useState<string | null>(null)

  const linhas = comprovante ? montarLinhasComprovante(comprovante, reimpressao) : []

  async function confirmarEnvioWhatsApp(telefone: string) {
    if (!comprovante) return
    setEnviandoWhatsApp(true)
    setErroWhatsApp(null)
    try {
      const blob = gerarBlobComprovante(linhas)
      const link = await compartilharArquivo(blob, `comprovante-crediario-${comprovante.idLoteRecebimento}.pdf`)
      const mensagem =
        `Olá, ${comprovante.nomeCliente}! Segue o comprovante do seu pagamento:\n${link}\n\n` +
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
      <div className="modal-overlay" onClick={fecharAoClicarNoFundo(aoFechar)}>
        <div
          className="modal modal-medio"
          role="dialog"
          aria-label={reimpressao ? 'Reimpressão de papeleta de recebimento de crediário' : 'Comprovante de pagamento'}
          onClick={(e) => e.stopPropagation()}
          style={{ display: 'flex', flexDirection: 'column', overflow: 'hidden' }}
        >
          <div className="lightbox-topo" style={{ flexShrink: 0 }}>
            <h2 style={{ margin: 0 }}>
              {reimpressao ? 'Reimpressão de Papeleta de Recebimento de Crediário' : 'Comprovante de Pagamento'}
            </h2>
            <button type="button" className="btn ghost btn-fechar-tela" onClick={aoFechar} aria-label="Fechar" title="Fechar">
              <IconeFechar />
            </button>
          </div>

          <div style={{ overflowY: 'auto', flex: 1, minHeight: 0 }}>
            {isLoading ? (
              <p className="muted">Carregando…</p>
            ) : error ? (
              <p className="erro">{error instanceof ApiError ? error.message : 'Não foi possível carregar o comprovante.'}</p>
            ) : (
              <pre className="comprovante-preview comprovante-imprimir">{linhas.join('\n')}</pre>
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
    </>
  )
}
