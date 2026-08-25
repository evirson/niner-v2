import CabecalhoModal from '../../components/CabecalhoModal'
import { useEffect, useRef, useState } from 'react'
import EnviarWhatsAppModal from '../../components/EnviarWhatsAppModal'
import { ApiError } from '../../lib/api'
import { compartilharArquivo } from '../../lib/compartilhamento'
import { formatarMoeda } from '../../lib/masks'
import { gerarBlobOrcamentoA4 } from '../../lib/orcamentoPdf'
import { montarLinkWhatsApp } from '../../lib/whatsapp'
import { montarLinhasOrcamentoBobina } from '../../lib/orcamentoImpressao'
import type { Orcamento } from '../../lib/orcamento'
import { imprimirDocumentoA4 } from '../../lib/impressaoDocumento'

type Formato = 'BOBINA' | 'A4'

function formatarData(iso: string): string {
  return new Date(iso).toLocaleDateString('pt-BR')
}

/** ⚠️ `data_validade` é `aaaa-mm-dd` puro: `new Date()` nele interpretaria como UTC e podia voltar
 *  um dia no fuso do Brasil. */
function formatarDataSimples(iso: string): string {
  const [ano, mes, dia] = iso.split('-')
  return `${dia}/${mes}/${ano}`
}

/**
 * Impressão do orçamento nos dois formatos pedidos pelo dono do produto: **bobina** (igual à
 * papeleta de venda) e **A4**.
 *
 * <h2>⚠️ O A4 precisa de `@page` próprio</h2>
 *
 * <p>O `@page` global do projeto é `size: 80mm auto` — a bobina térmica. Sem um `@page` nomeado, o
 * A4 sairia espremido em 80mm. O DANFE já tropeçou nisso; aqui a página é declarada
 * dinamicamente conforme o formato escolhido.
 */
export default function OrcamentoImpressaoModal({
  orcamento,
  aoFechar,
}: {
  orcamento: Orcamento
  aoFechar: () => void
}) {
  const [formato, setFormato] = useState<Formato>('A4')
  const [whatsappAberto, setWhatsappAberto] = useState(false)
  const [enviando, setEnviando] = useState(false)
  const [erroWhatsapp, setErroWhatsapp] = useState<string | null>(null)
  const folhaRef = useRef<HTMLDivElement>(null)

  /**
   * Gera o PDF só ao confirmar o telefone (mesmo fluxo da papeleta), sobe para o cache temporário
   * da API e abre o `wa.me`.
   *
   * <p>⚠️ O link **expira em 24 horas** e o orçamento vale muito mais que isso — por isso a
   * mensagem diz que é uma cópia, e não "o seu orçamento".
   */
  const enviarWhatsApp = async (telefone: string) => {
    if (!folhaRef.current) return
    setEnviando(true)
    setErroWhatsapp(null)
    try {
      const blob = await gerarBlobOrcamentoA4(folhaRef.current)
      const link = await compartilharArquivo(blob, `orcamento-${orcamento.idOrcamento}.pdf`)
      const mensagem =
        `Olá, ${orcamento.nomeCliente}! Segue uma cópia do seu orçamento nº ${orcamento.idOrcamento}, ` +
        `válido até ${formatarDataSimples(orcamento.dataValidade)}:\n${link}\n\n` +
        'O link expira em 24 horas.'
      window.open(montarLinkWhatsApp(telefone, mensagem), '_blank')
      setWhatsappAberto(false)
    } catch (e) {
      setErroWhatsapp(e instanceof ApiError ? e.message : 'Não foi possível preparar o envio.')
    } finally {
      setEnviando(false)
    }
  }

  /**
   * ⚠️ Cada `@page` nomeado precisa de uma regra que o AMARRE a um elemento (auditoria
   * 2026-08-21, item 18).
   *
   * <p>O `@page orcamento-bobina` era injetado e **nada o referenciava** — nenhum seletor tinha
   * `page: orcamento-bobina`. A bobina saía certa por acaso, porque caía no `@page` global do
   * projeto, que também é `size: 80mm auto`. No dia em que esse global mudasse (ou uma tela
   * injetasse outro `@page` antes), a bobina do orçamento sairia no tamanho errado sem que nada
   * apontasse para aqui.
   */
  useEffect(() => {
    const estilo = document.createElement('style')
    estilo.textContent =
      formato === 'A4'
        ? '@page orcamento-a4 { size: A4 portrait; margin: 0; }'
          + '.orcamento-imprimir-a4 { page: orcamento-a4; }'
        : '@page orcamento-bobina { size: 80mm auto; margin: 0; }'
          + '.orcamento-imprimir-bobina { page: orcamento-bobina; }'
    document.head.appendChild(estilo)
    return () => estilo.remove()
  }, [formato])

  const linhasBobina = montarLinhasOrcamentoBobina(orcamento)

  return (
    <>
      <div className="modal-overlay">
        <div className="modal modal-largo" role="dialog" aria-label={`Orçamento nº ${orcamento.idOrcamento}`}>
          <div className="lightbox-topo" style={{ marginBottom: 12 }}>
            <CabecalhoModal titulo=<>Orçamento nº {orcamento.idOrcamento}</> aoFechar={aoFechar} />
            <div style={{ display: 'flex', gap: 8 }}>
              <button
                type="button"
                className={`btn ${formato === 'A4' ? '' : 'ghost'}`}
                onClick={() => setFormato('A4')}
              >
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
              <div className="orcamento-a4-preview">{corpoA4(orcamento)}</div>
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

      {/* Fora da tela — só existe para o navegador imprimir (mesma técnica dos outros
          comprovantes). O formato escolhido decide qual `@page` vale. */}
      {/* ⚠️ As duas versões ficam SEMPRE no DOM: o CSS decide qual sai na impressora, e o envio
          por WhatsApp captura a folha A4 mesmo quando a tela está mostrando a bobina (o PDF do
          cliente é sempre o A4 — bobina é papel de balcão, não anexo de mensagem). */}
      <div className={formato === 'A4' ? 'orcamento-imprimir-a4 documento-a4-imprimir' : 'orcamento-imprimir-oculto'}>
        <div className="orcamento-a4-folha" ref={folhaRef}>{corpoA4(orcamento)}</div>
      </div>
      <div className={formato === 'BOBINA' ? 'etiqueta-imprimir orcamento-imprimir-bobina' : 'orcamento-imprimir-oculto'}>
        <pre className="papeleta-imprimir">{linhasBobina.join('\n')}</pre>
      </div>

      {whatsappAberto && (
        <EnviarWhatsAppModal
          telefoneInicial={orcamento.telefoneCliente}
          enviando={enviando}
          erro={erroWhatsapp}
          aoConfirmar={enviarWhatsApp}
          aoFechar={() => setWhatsappAberto(false)}
        />
      )}
    </>
  )
}

/** Corpo visual do A4 — o mesmo markup serve a pré-visualização e à impressão. */
function corpoA4(o: Orcamento) {
  return (
    <>
      <div className="orcamento-a4-cabecalho">
        <div>
          <strong style={{ fontSize: '1.2em' }}>{o.nomeEmpresa}</strong>
        </div>
        <div style={{ textAlign: 'right' }}>
          <strong>ORÇAMENTO Nº {o.idOrcamento}</strong>
          <br />
          Emissão: {formatarData(o.dataOrcamento)}
        </div>
      </div>

      <div className="orcamento-a4-validade">
        Válido até <strong>{formatarDataSimples(o.dataValidade)}</strong>
      </div>

      <table className="orcamento-a4-dados">
        <tbody>
          <tr>
            <td><strong>Cliente:</strong> {o.nomeCliente}</td>
            <td><strong>Documento:</strong> {o.documentoCliente ?? '—'}</td>
          </tr>
          <tr>
            <td><strong>Telefone:</strong> {o.telefoneCliente ?? '—'}</td>
            <td><strong>Vendedor:</strong> {o.nomeFuncionario}</td>
          </tr>
        </tbody>
      </table>

      <table className="orcamento-a4-itens">
        <thead>
          <tr>
            <th>Código</th>
            <th>Produto</th>
            <th style={{ textAlign: 'right' }}>Qtde</th>
            <th style={{ textAlign: 'right' }}>Unitário</th>
            <th style={{ textAlign: 'right' }}>Total</th>
          </tr>
        </thead>
        <tbody>
          {o.itens.map((i) => (
            <tr key={i.idOrcamentoItem}>
              <td className="mono">{i.sku}</td>
              <td>
                {i.descricao}
                {(i.variacaoCor || i.variacaoTamanho) &&
                  ` ${[i.variacaoCor, i.variacaoTamanho].filter(Boolean).join(' · ')}`}
              </td>
              <td style={{ textAlign: 'right' }}>{i.qtd}</td>
              <td style={{ textAlign: 'right' }}>R$ {formatarMoeda(i.precoVenda)}</td>
              <td style={{ textAlign: 'right' }}>R$ {formatarMoeda(i.valorTotal)}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <div className="orcamento-a4-totais">
        <div>Subtotal: R$ {formatarMoeda(o.subtotal)}</div>
        {o.valorDesconto > 0 && <div>Desconto: R$ {formatarMoeda(o.valorDesconto)}</div>}
        <div style={{ fontSize: '1.3em' }}>
          <strong>Total: R$ {formatarMoeda(o.valorTotal)}</strong>
        </div>
      </div>

      {o.observacao && (
        <div className="orcamento-a4-observacao">
          <strong>Observações:</strong> {o.observacao}
        </div>
      )}

      <div className="orcamento-a4-rodape">
        <div className="orcamento-a4-assinatura">Assinatura do cliente</div>
        <p>Este orçamento não é documento fiscal.</p>
      </div>
    </>
  )
}
