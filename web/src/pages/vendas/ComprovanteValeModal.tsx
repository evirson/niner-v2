import CabecalhoModal from '../../components/CabecalhoModal'
import { useState } from 'react'
import EnviarWhatsAppModal from '../../components/EnviarWhatsAppModal'
import { IconeWhatsapp } from '../../components/Icones'
import { ApiError } from '../../lib/api'
import { compartilharArquivo } from '../../lib/compartilhamento'
import { gerarBlobComprovanteVale, gerarPdfComprovanteVale, montarLinhasComprovanteVale } from '../../lib/comprovante'
import type { DevolucaoEfetivada } from '../../lib/devolucaoProduto'
import { formatarMoeda } from '../../lib/masks'
import { montarLinkWhatsApp } from '../../lib/whatsapp'
import DanfeModal from './DanfeModal'
import { fecharAoClicarNoFundo } from '../../lib/modais'

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
  const [danfeAberto, setDanfeAberto] = useState(false)

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
      <div className="modal-overlay" onClick={fecharAoClicarNoFundo(aoFechar)}>
        <div
          className="modal modal-medio"
          role="dialog"
          aria-label="Vale-mercadoria"
          onClick={(e) => e.stopPropagation()}
          style={{ display: 'flex', flexDirection: 'column', overflow: 'hidden' }}
        >
          <CabecalhoModal titulo="Vale-Mercadoria Gerado" aoFechar={aoFechar} />

          {/* Desfecho da NF-e de devolução (2026-08-19, B9) — `notaFiscal` vem nulo quando não
              havia nota a emitir (fiscal desligado, devolução sem venda de origem, ou venda sem
              NFC-e). Autorizada, o operador pode abrir o DANFE aqui mesmo; rejeitada, o aviso
              deixa claro que a DEVOLUÇÃO continua valendo — só a nota precisa de atenção (F3). */}
          {devolucao.notaFiscal && (
            <div
              className={devolucao.notaFiscal.situacao === 'AUTORIZADO' ? 'tarja-sucesso' : 'tarja-aviso'}
              style={{ flexShrink: 0, fontSize: 13, alignItems: 'flex-start' }}
            >
              <div style={{ flex: 1 }}>
                {devolucao.notaFiscal.mensagem}
                {/* ⚠️ As duas frases são DIFERENTES de propósito (2026-08-30). "A nota fica em
                    Documentos Fiscais para ser reprocessada" só é verdade quando o documento
                    chegou a existir no banco — REJEITADO e FALHA_COMUNICACAO nascem depois do
                    `gravarDevolucaoAssinada`. `FALHA_NA_EMISSAO` é o caso em que a emissão parou
                    ANTES disso (cadastro incompleto, certificado, XSD): não há linha em
                    `documento_fiscal`, e mandar o operador procurar lá faz com que ele não ache
                    nada e conclua que o sistema perdeu a nota. */}
                {/* ⚠️ São TRÊS populações com conselhos diferentes, e a primeira versão desta
                    correção separou uma de três — reabrindo, na porta ao lado, o mesmo defeito que
                    ela veio fechar. O botão "Reprocessar" da tela de Documentos Fiscais só existe
                    para TRANSMITINDO/ASSINADO (`SITUACOES_REPROCESSAVEIS`), e o servidor recusa o
                    resto com 409. Mandar quem levou REJEITADO para lá é mandá-lo procurar um botão
                    que não vai encontrar. */}
                {devolucao.notaFiscal.situacao === 'FALHA_NA_EMISSAO' ? (
                  <>
                    <br />
                    O vale-mercadoria abaixo continua válido e a mercadoria já voltou ao estoque —
                    mas a nota <strong>não chegou a ser emitida</strong> e não ficou registrada em
                    Documentos Fiscais. Corrija o que a mensagem acima aponta; a nota desta devolução
                    precisará ser emitida à parte.
                  </>
                ) : devolucao.notaFiscal.situacao === 'REJEITADO' || devolucao.notaFiscal.situacao === 'DENEGADO' ? (
                  <>
                    <br />
                    O vale-mercadoria abaixo continua válido e a mercadoria já voltou ao estoque. A
                    nota foi <strong>recusada pela SEFAZ</strong> e está registrada em Documentos
                    Fiscais — <strong>recusa não se reprocessa</strong>: corrija a causa apontada
                    acima antes de emitir de novo.
                  </>
                ) : (
                  devolucao.notaFiscal.situacao !== 'AUTORIZADO' && (
                    <>
                      <br />
                      O vale-mercadoria abaixo continua válido — a nota fica em Documentos Fiscais para ser
                      reprocessada.
                    </>
                  )
                )}
              </div>
              {devolucao.notaFiscal.situacao === 'AUTORIZADO' && (
                <button type="button" className="btn ghost" onClick={() => setDanfeAberto(true)}>
                  Ver DANFE
                </button>
              )}
            </div>
          )}

          <div style={{ overflowY: 'auto', flex: 1, minHeight: 0 }}>
            <pre className="papeleta-preview papeleta-imprimir">{linhas.join('\n')}</pre>
          </div>

          <div className="ajuda-rodape" style={{ flexShrink: 0 }}>

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

      {/* Irmão do overlay do vale (Fragment), não aninhado — senão clicar no fundo do DANFE
          fecharia os dois popups por bubbling, mesma armadilha já documentada na Pesquisa de
          Vendas quando o popup de reimpressão foi empilhado sobre o de detalhe. */}
      {danfeAberto && devolucao.notaFiscal && (
        <DanfeModal
          idDocumentoFiscal={devolucao.notaFiscal.idDocumentoFiscal}
          aoFechar={() => setDanfeAberto(false)}
        />
      )}
    </>
  )
}
