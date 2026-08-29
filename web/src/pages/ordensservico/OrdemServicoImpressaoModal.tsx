import { useEffect, useRef, useState } from 'react'
import CabecalhoModal from '../../components/CabecalhoModal'
import EnviarWhatsAppModal from '../../components/EnviarWhatsAppModal'
import { ApiError } from '../../lib/api'
import { compartilharArquivo } from '../../lib/compartilhamento'
import { formatarMoeda } from '../../lib/masks'
import { gerarBlobDocumentoA4 } from '../../lib/orcamentoPdf'
import { imprimirDocumentoA4 } from '../../lib/impressaoDocumento'
import { montarLinhasOrdemServicoBobina } from '../../lib/ordemServicoImpressao'
import { SITUACAO_OS, type ItemOs, type OrdemServico } from '../../lib/ordensServico'
import { montarLinkWhatsApp } from '../../lib/whatsapp'

type Formato = 'BOBINA' | 'A4'

function formatarData(iso: string): string {
  return new Date(iso).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' })
}

/**
 * Via impressa da Ordem de Serviço — **bobina** (o papel do balcão) e **A4**, mais o envio por
 * WhatsApp, no mesmo desenho do orçamento e da papeleta.
 *
 * <h2>⭐ Por que isto não era opcional</h2>
 *
 * <p>A OS nasceu (2026-08-28) sem nenhuma forma de imprimir, e isso a deixava inutilizável no
 * balcão que ela existe para atender: o cliente deixa o carro e **não leva nada na mão**. Toda
 * oficina trabalha com a via do cliente, e no petshop ela é o comprovante de entrega do animal.
 *
 * <p>⚠️ Diferente do orçamento, aqui os itens saem em **dois blocos** — Serviços e Peças —, com
 * subtotal cada um. É a pergunta que o dono da oficina faz ("quanto foi mão de obra, quanto foi
 * material") e a mesma divisão que a NFS-e vai precisar.
 */
export default function OrdemServicoImpressaoModal({
  os,
  aoFechar,
}: {
  os: OrdemServico
  aoFechar: () => void
}) {
  const [formato, setFormato] = useState<Formato>('A4')
  const [whatsappAberto, setWhatsappAberto] = useState(false)
  const [enviando, setEnviando] = useState(false)
  const [erroWhatsapp, setErroWhatsapp] = useState<string | null>(null)
  const folhaRef = useRef<HTMLDivElement>(null)

  /**
   * ⚠️ `@page` nomeado só vale com um seletor que o AMARRE — sem a segunda regra ele é injetado e
   * nada o referencia, e o documento cai no `@page` global do projeto (80mm) por acaso. Foi um
   * achado de auditoria no orçamento, e o mesmo par se repete aqui de propósito.
   */
  useEffect(() => {
    const estilo = document.createElement('style')
    estilo.textContent =
      formato === 'A4'
        ? '@page os-a4 { size: A4 portrait; margin: 0; }' + '.os-imprimir-a4 { page: os-a4; }'
        : '@page os-bobina { size: 80mm auto; margin: 0; }' + '.os-imprimir-bobina { page: os-bobina; }'
    document.head.appendChild(estilo)
    return () => estilo.remove()
  }, [formato])

  const enviarWhatsApp = async (telefone: string) => {
    if (!folhaRef.current) return
    setEnviando(true)
    setErroWhatsapp(null)
    try {
      const blob = await gerarBlobDocumentoA4(folhaRef.current)
      const link = await compartilharArquivo(blob, `ordem-servico-${os.idOrdemServico}.pdf`)
      const mensagem =
        `Olá, ${os.nomeCliente}! Segue a sua ordem de serviço nº ${os.idOrdemServico} ` +
        `(${os.objetoServico}):\n${link}\n\nO link expira em 24 horas.`
      window.open(montarLinkWhatsApp(telefone, mensagem), '_blank')
      setWhatsappAberto(false)
    } catch (e) {
      setErroWhatsapp(e instanceof ApiError ? e.message : 'Não foi possível preparar o envio.')
    } finally {
      setEnviando(false)
    }
  }

  const linhasBobina = montarLinhasOrdemServicoBobina(os)

  return (
    <>
      <div className="modal-overlay">
        <div className="modal modal-largo" role="dialog" aria-label={`Ordem de serviço nº ${os.idOrdemServico}`}>
          <div className="lightbox-topo" style={{ marginBottom: 12 }}>
            <CabecalhoModal titulo=<>Ordem de Serviço nº {os.idOrdemServico}</> aoFechar={aoFechar} />
            <div style={{ display: 'flex', gap: 8 }}>
              <button type="button" className={`btn ${formato === 'A4' ? '' : 'ghost'}`} onClick={() => setFormato('A4')}>
                A4
              </button>
              <button
                type="button"
                className={`btn ${formato === 'BOBINA' ? '' : 'ghost'}`}
                onClick={() => setFormato('BOBINA')}
              >
                Bobina
              </button>
            </div>
          </div>

          <div className="table-wrap" style={{ maxHeight: '58vh' }}>
            {formato === 'BOBINA' ? (
              <pre className="papeleta-preview">{linhasBobina.join('\n')}</pre>
            ) : (
              <div className="orcamento-a4-preview">{corpoA4(os)}</div>
            )}
          </div>

          <div className="ajuda-rodape">
            <button type="button" className="btn ghost" onClick={() => setWhatsappAberto(true)}>
              Enviar por WhatsApp
            </button>
            <button type="button" className="btn" onClick={imprimirDocumentoA4}>
              Imprimir
            </button>
          </div>
        </div>
      </div>

      {/* ⚠️ As duas versões ficam SEMPRE no DOM: o CSS decide qual sai na impressora, e o envio por
          WhatsApp captura a folha A4 mesmo com a tela mostrando a bobina — o PDF do cliente é
          sempre o A4, porque bobina é papel de balcão, não anexo de mensagem. */}
      <div className={formato === 'A4' ? 'os-imprimir-a4 documento-a4-imprimir' : 'orcamento-imprimir-oculto'}>
        <div className="orcamento-a4-folha" ref={folhaRef}>{corpoA4(os)}</div>
      </div>
      <div className={formato === 'BOBINA' ? 'etiqueta-imprimir os-imprimir-bobina' : 'orcamento-imprimir-oculto'}>
        <pre className="papeleta-imprimir">{linhasBobina.join('\n')}</pre>
      </div>

      {whatsappAberto && (
        <EnviarWhatsAppModal
          telefoneInicial={os.telefoneCliente}
          enviando={enviando}
          erro={erroWhatsapp}
          aoConfirmar={enviarWhatsApp}
          aoFechar={() => setWhatsappAberto(false)}
        />
      )}
    </>
  )
}

/** Bloco de itens do A4 — o mesmo markup serve a Serviços e a Peças. */
function blocoItens(titulo: string, itens: ItemOs[], subtotal: number, mostrarExecutor: boolean) {
  if (itens.length === 0) return null
  return (
    <>
      <p className="section-label" style={{ marginBottom: 4, marginTop: 12 }}>{titulo}</p>
      <table className="orcamento-a4-itens">
        <thead>
          <tr>
            <th>Código</th>
            <th>Descrição</th>
            {mostrarExecutor && <th>Executado por</th>}
            <th style={{ textAlign: 'right' }}>Qtde</th>
            <th style={{ textAlign: 'right' }}>Unitário</th>
            <th style={{ textAlign: 'right' }}>Total</th>
          </tr>
        </thead>
        <tbody>
          {itens.map((i) => (
            <tr key={i.idOrdemServicoItem}>
              <td className="mono">{i.sku}</td>
              <td>
                {i.descricaoProduto}
                {(i.variacaoCor || i.variacaoTamanho) &&
                  ` ${[i.variacaoCor, i.variacaoTamanho].filter(Boolean).join(' · ')}`}
              </td>
              {mostrarExecutor && <td>{i.nomeFuncionario ?? '—'}</td>}
              <td style={{ textAlign: 'right' }}>{i.qtdProduto}</td>
              <td style={{ textAlign: 'right' }}>R$ {formatarMoeda(i.precoVenda)}</td>
              <td style={{ textAlign: 'right' }}>R$ {formatarMoeda(i.total)}</td>
            </tr>
          ))}
          <tr>
            <td colSpan={mostrarExecutor ? 5 : 4} style={{ textAlign: 'right' }}>
              <strong>Subtotal {titulo.toLowerCase()}</strong>
            </td>
            <td style={{ textAlign: 'right' }}><strong>R$ {formatarMoeda(subtotal)}</strong></td>
          </tr>
        </tbody>
      </table>
    </>
  )
}

/** Corpo visual do A4 — o mesmo markup serve a pré-visualização e à impressão. */
function corpoA4(os: OrdemServico) {
  const servicos = os.itens.filter((i) => i.tipoItem === 'SERVICO')
  const pecas = os.itens.filter((i) => i.tipoItem === 'MERCADORIA')

  return (
    <>
      <div className="orcamento-a4-cabecalho">
        <div>
          <strong style={{ fontSize: '1.2em' }}>{os.nomeEmpresa}</strong>
        </div>
        <div style={{ textAlign: 'right' }}>
          <strong>ORDEM DE SERVIÇO Nº {os.idOrdemServico}</strong>
          <br />
          Abertura: {formatarData(os.dataAbertura)}
        </div>
      </div>

      <div className="orcamento-a4-validade">
        {os.objetoServico} · <strong>{SITUACAO_OS[os.situacao].rotulo}</strong>
      </div>

      <table className="orcamento-a4-dados">
        <tbody>
          <tr>
            <td><strong>Cliente:</strong> {os.nomeCliente}</td>
            <td><strong>Documento:</strong> {os.documentoCliente ?? '—'}</td>
          </tr>
          <tr>
            <td><strong>Telefone:</strong> {os.telefoneCliente ?? '—'}</td>
            <td><strong>Responsável:</strong> {os.nomeFuncionario}</td>
          </tr>
        </tbody>
      </table>

      {blocoItens('Serviços', servicos, os.totalServicos, true)}
      {blocoItens('Peças', pecas, os.totalPecas, false)}

      <div className="orcamento-a4-totais">
        {os.valorDesconto > 0 && <div>Desconto: R$ {formatarMoeda(os.valorDesconto)}</div>}
        <div style={{ fontSize: '1.3em' }}>
          <strong>Total: R$ {formatarMoeda(os.total)}</strong>
        </div>
      </div>

      {os.observacao && (
        <div className="orcamento-a4-observacao">
          <strong>Observações:</strong> {os.observacao}
        </div>
      )}

      <div className="orcamento-a4-rodape">
        <div className="orcamento-a4-assinatura">Assinatura do cliente</div>
        <p>Esta ordem de serviço não é documento fiscal.</p>
      </div>
    </>
  )
}
