import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { gerarBlobComprovanteVenda, gerarPdfComprovanteVenda, montarLinhasComprovanteVenda } from '../../lib/comprovante'
import { ApiError } from '../../lib/api'
import { buscarComprovanteVenda } from '../../lib/pdv'
import { compartilharArquivo } from '../../lib/compartilhamento'
import { montarLinkWhatsApp } from '../../lib/whatsapp'
import { IconeWhatsapp } from '../../components/Icones'
import EnviarWhatsAppModal from '../../components/EnviarWhatsAppModal'

/**
 * Papeleta de venda, formatada pra bobina térmica de 80mm (2026-08-06). Abre automaticamente
 * logo após o F5 efetivar a venda com sucesso (`Pdv.tsx`). Mesmo mecanismo do Comprovante de
 * Pagamento de Crediário: "Imprimir" usa o diálogo nativo do navegador (só `.papeleta-imprimir`
 * fica visível na impressão, via CSS — ver `styles.css`); "Salvar PDF" gera o arquivo direto com
 * `jsPDF`, sem passar pelo diálogo.
 *
 * `reimpressao` (2026-08-06) — usado pela tela de Reimpressão de Papeleta de Venda
 * (`ReimpressaoPapeletaVenda.tsx`): mesmo componente, muda só o título do popup e injeta uma
 * linha "REIMPRESSÃO DE PAPELETA DE VENDA" + "Impresso em: <data/hora atual>" no final da
 * papeleta (`montarLinhasComprovanteVenda`) — nunca no fluxo normal pós-F5.
 *
 * "Enviar por WhatsApp" (2026-08-07) — mesmo mecanismo do Comprovante de Pagamento de Crediário:
 * abre `EnviarWhatsAppModal` pra confirmar/editar o celular (pré-preenchido com `cliente.
 * telefone`) antes de gerar qualquer coisa; só ao confirmar ali é que a papeleta vira Blob
 * (mesmo motor de "Salvar PDF"), sobe pro cache temporário da API e abre o link `wa.me`.
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
  const {
    data: comprovante,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['comprovante-venda', idVenda],
    queryFn: () => buscarComprovanteVenda(idVenda),
    retry: 1,
  })

  const [modalWhatsAppAberto, setModalWhatsAppAberto] = useState(false)
  const [enviandoWhatsApp, setEnviandoWhatsApp] = useState(false)
  const [erroWhatsApp, setErroWhatsApp] = useState<string | null>(null)

  const linhas = comprovante ? montarLinhasComprovanteVenda(comprovante, reimpressao) : []

  async function confirmarEnvioWhatsApp(telefone: string) {
    if (!comprovante) return
    setEnviandoWhatsApp(true)
    setErroWhatsApp(null)
    try {
      const blob = gerarBlobComprovanteVenda(linhas)
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
          <h2 style={{ marginTop: 0, flexShrink: 0 }}>{reimpressao ? 'Reimpressão de Papeleta de Venda' : 'Papeleta de Venda'}</h2>

          <div style={{ overflowY: 'auto', flex: 1, minHeight: 0 }}>
            {isLoading ? (
              <p className="muted">Carregando…</p>
            ) : error ? (
              <p className="erro">{error instanceof ApiError ? error.message : 'Não foi possível carregar a papeleta.'}</p>
            ) : (
              <pre className="papeleta-preview papeleta-imprimir">{linhas.join('\n')}</pre>
            )}
          </div>

          <div className="ajuda-rodape" style={{ flexShrink: 0 }}>
            <button type="button" className="btn ghost" onClick={aoFechar}>
              Fechar
            </button>
            <div style={{ display: 'flex', gap: 8 }}>
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
              <button
                type="button"
                className="btn ghost"
                disabled={!comprovante}
                onClick={() => comprovante && gerarPdfComprovanteVenda(linhas, comprovante.idVenda)}
              >
                Salvar PDF
              </button>
              <button type="button" className="btn" disabled={!comprovante} onClick={() => window.print()}>
                Imprimir
              </button>
            </div>
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
