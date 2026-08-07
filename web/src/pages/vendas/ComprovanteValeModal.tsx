import { useState } from 'react'
import EnviarWhatsAppModal from '../../components/EnviarWhatsAppModal'
import { IconeWhatsapp } from '../../components/Icones'
import { ApiError } from '../../lib/api'
import { compartilharArquivo } from '../../lib/compartilhamento'
import { gerarBlobComprovanteVale, gerarPdfComprovanteVale, montarLinhasComprovanteVale } from '../../lib/comprovante'
import type { DevolucaoEfetivada } from '../../lib/devolucaoProduto'
import { formatarMoeda } from '../../lib/masks'
import { montarLinkWhatsApp } from '../../lib/whatsapp'

/**
 * Comprovante do vale-mercadoria gerado por uma devolução, formatado pra bobina térmica de
 * 80mm — desde 2026-08-07 no MESMO padrão visual da Papeleta de Venda
 * (`ComprovantePapeletaModal.tsx`): tabela de itens de 64 colunas, fonte Lucida Console na
 * pré-visualização/impressão (`.papeleta-preview`/`.papeleta-imprimir`), cabeçalho/rodapé fixos
 * (só a pré-visualização rola) e o botão **"Enviar por WhatsApp"** (mesmo mecanismo do
 * Comprovante de Pagamento de Crediário/Papeleta de Venda). Antes usava um layout de 42 colunas
 * próprio (courier, `.comprovante-preview`) — pedido explícito do dono do produto pra padronizar
 * a impressão dos itens entre os dois comprovantes que saem na mesma bobina física. Abre
 * automaticamente logo após gravar a devolução com sucesso (`DevolucaoProduto.tsx`). Recebe a
 * resposta da efetivação direto via props (já tem tudo — sem precisar de uma segunda consulta).
 *
 * Diferença de propósito da papeleta de venda: `venda_devolucao` não tem vínculo com cliente
 * (devolução é anônima, ver docs/telas/devolucao-produtos.md) — não há telefone pra pré-preencher,
 * o operador digita na hora em `EnviarWhatsAppModal` (`telefoneInicial={null}`, campo continua
 * editável do mesmo jeito).
 */
export default function ComprovanteValeModal({
  devolucao,
  nomeEmpresa,
  aoFechar,
}: {
  devolucao: DevolucaoEfetivada
  nomeEmpresa: string
  aoFechar: () => void
}) {
  const linhas = montarLinhasComprovanteVale(devolucao, nomeEmpresa)

  const [modalWhatsAppAberto, setModalWhatsAppAberto] = useState(false)
  const [enviandoWhatsApp, setEnviandoWhatsApp] = useState(false)
  const [erroWhatsApp, setErroWhatsApp] = useState<string | null>(null)

  async function confirmarEnvioWhatsApp(telefone: string) {
    setEnviandoWhatsApp(true)
    setErroWhatsApp(null)
    try {
      const blob = gerarBlobComprovanteVale(linhas)
      const link = await compartilharArquivo(blob, `vale-mercadoria-${devolucao.idDevolucao}.pdf`)
      const mensagem =
        `Olá! Segue o seu vale-mercadoria nº ${devolucao.idDevolucao}, no valor de R$ ${formatarMoeda(devolucao.valorVale)}:\n${link}\n\n` +
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
          aria-label="Vale-mercadoria"
          onClick={(e) => e.stopPropagation()}
          style={{ display: 'flex', flexDirection: 'column', overflow: 'hidden' }}
        >
          <h2 style={{ marginTop: 0, flexShrink: 0 }}>Vale-Mercadoria Gerado</h2>

          <div style={{ overflowY: 'auto', flex: 1, minHeight: 0 }}>
            <pre className="papeleta-preview papeleta-imprimir">{linhas.join('\n')}</pre>
          </div>

          <div className="ajuda-rodape" style={{ flexShrink: 0 }}>
            <button type="button" className="btn ghost" onClick={aoFechar}>
              Fechar
            </button>
            <div style={{ display: 'flex', gap: 8 }}>
              <button
                type="button"
                className="btn ghost"
                onClick={() => setModalWhatsAppAberto(true)}
                style={{ display: 'flex', alignItems: 'center', gap: 6 }}
              >
                <IconeWhatsapp size={18} />
                Enviar por WhatsApp
              </button>
              <button type="button" className="btn ghost" onClick={() => gerarPdfComprovanteVale(linhas, devolucao.idDevolucao)}>
                Salvar PDF
              </button>
              <button type="button" className="btn" onClick={() => window.print()}>
                Imprimir
              </button>
            </div>
          </div>
        </div>
      </div>

      {modalWhatsAppAberto && (
        <EnviarWhatsAppModal
          telefoneInicial={null}
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
