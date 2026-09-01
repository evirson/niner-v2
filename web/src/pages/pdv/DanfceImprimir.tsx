import { forwardRef, Fragment } from 'react'
import type { ComprovanteVenda } from '../../lib/pdv'
import { mascararCpfCnpj, formatarMoeda, formatarPercentual } from '../../lib/masks'
import {
  moedaDanfce,
  formatarDataHoraDanfce,
  formatarChaveGrupos4,
  formatarNumeroNfce,
  formatarQuantidadeDanfce,
} from '../../lib/danfce'

/**
 * DANFE NFC-e como tabela HTML de verdade (fonte proporcional, colunas alinhadas) — reconstruído
 * em 2026-08-19 a partir de um modelo real trazido pelo dono do produto ("DANF ÓTIMA"), depois
 * que a versão em texto monoespaçado herdada do B7 saiu feia na impressão real (e, pior, cortava
 * a chave de acesso em silêncio — texto maior que as 42 colunas). Mesma bobina térmica 80mm de
 * sempre, confirmado com o dono do produto antes de reescrever — só o motor de renderização
 * mudou, de "array de linhas de 42 colunas" pra HTML/CSS com `<table>`. A papeleta comum (venda
 * sem fiscal) continua no texto monoespaçado antigo, intocada — só o DANFCE trocou.
 *
 * `ref` aponta pro elemento raiz — o pai usa isso tanto pra `window.print()` (a calibragem de
 * impressão está na classe `.danfce-imprimir`, `styles.css`) quanto pra capturar o PDF do envio
 * por WhatsApp (`gerarBlobDanfce`, `lib/danfce.ts`).
 */
/** Traço gráfico contínuo (barra preenchida via CSS `background`, não caractere de texto nem
 *  `border-style: dashed`) — achado em 2026-08-19: tanto borda tracejada quanto uma fileira de
 *  `-` de texto saem picotadas/pontilhadas na impressora térmica (a resolução da bobina não
 *  resolve traços finos intercalados com espaço); só uma barra sólida imprime como linha contínua
 *  de verdade em qualquer bobina. */
function Separador() {
  return <div className="danfce-sep" />
}

const DanfceImprimir = forwardRef<
  HTMLDivElement,
  { comprovante: ComprovanteVenda; qrDataUrl: string | null; permiteQtdDecimal: boolean }
>(function DanfceImprimir({ comprovante: c, qrDataUrl, permiteQtdDecimal }, ref) {
    const f = c.dadosFiscais
    if (!f) return null
    const empresa = c.empresaFiscal

    const percentualTributo = c.totalAPagar > 0 ? (f.valorTotalTributos / c.totalAPagar) * 100 : 0
    const percentualTrib = (valor: number) => (c.totalAPagar > 0 ? (valor / c.totalAPagar) * 100 : 0)
    const documentoConsumidorTexto = f.documentoConsumidor
      ? mascararCpfCnpj(f.documentoConsumidor, f.documentoConsumidor.length === 11)
      : 'CONSUMIDOR NÃO IDENTIFICADO'
    return (
      <div className="danfce-preview danfce-imprimir" ref={ref}>
        {empresa && (
          <div className="danfce-empresa">
            <div className="danfce-empresa-nome">{empresa.razaoSocial}</div>
            {(empresa.cnpj || empresa.inscricaoEstadual) && (
              <div>
                {empresa.cnpj && `CNPJ: ${mascararCpfCnpj(empresa.cnpj, false)}`}
                {empresa.cnpj && empresa.inscricaoEstadual && ' - '}
                {empresa.inscricaoEstadual && `IE: ${empresa.inscricaoEstadual}`}
              </div>
            )}
            {empresa.enderecoCompleto && <div>{empresa.enderecoCompleto}</div>}
            {empresa.telefone && <div>TELEFONE: {empresa.telefone}</div>}
          </div>
        )}

        <Separador />
        <div className="danfce-titulo">
          DANFE NFC-e - Documento Auxiliar da Nota Fiscal
          <br />
          Eletrônica de Consumidor Final
        </div>
        <Separador />
        <div className="danfce-aviso">NFC-e não permite aproveitamento de crédito de ICMS</div>
        {f.homologacao && (
          <div className="danfce-tarja">NOTA FISCAL EMITIDA EM AMBIENTE DE HOMOLOGACAO - SEM VALOR FISCAL</div>
        )}
        {f.contingencia && (
          <div className="danfce-tarja">EMITIDA EM CONTINGENCIA - AGUARDANDO AUTORIZACAO DA SEFAZ</div>
        )}

        <Separador />
        <table className="danfce-itens">
          <thead>
            <tr>
              <th colSpan={5} className="danfce-itens-titulo">Descrição dos Produtos</th>
            </tr>
            <tr>
              <th>Cod.</th>
              <th className="danfce-num">Qtd.</th>
              <th>Un</th>
              <th className="danfce-num">Vlr. Unit.</th>
              <th className="danfce-num">Vlr. Total.</th>
            </tr>
          </thead>
          <tbody>
            {c.itens.map((item, i) => (
              <Fragment key={i}>
                {i > 0 && (
                  <tr>
                    <td colSpan={5}>
                      <Separador />
                    </td>
                  </tr>
                )}
                <tr>
                  <td colSpan={5} className="danfce-item-desc">
                    {[item.descricaoProduto, item.variacaoCor, item.variacaoTamanho].filter(Boolean).join(' ')}
                  </td>
                </tr>
                <tr>
                  <td>{item.sku}</td>
                  <td className="danfce-num">{formatarQuantidadeDanfce(item.qtd, permiteQtdDecimal)}</td>
                  <td>{item.unidadeComercial ?? ''}</td>
                  <td className="danfce-num">{formatarMoeda(item.valorUnitario)}</td>
                  <td className="danfce-num">{formatarMoeda(item.valorTotal)}</td>
                </tr>
              </Fragment>
            ))}
          </tbody>
        </table>

        <Separador />
        <div className="danfce-totais">
          <div>
            <span>Qtd. Total de Itens</span>
            <b>{c.itens.length}</b>
          </div>
          <div>
            <span>Valor Total (R$)</span>
            <b>{formatarMoeda(c.subtotal)}</b>
          </div>
          <div className="danfce-total-pagar">
            <span>Valor a Pagar (R$)</span>
            <b>{formatarMoeda(c.totalAPagar)}</b>
          </div>
        </div>

        <Separador />
        <table className="danfce-pagamentos">
          <thead>
            <tr>
              <th>Forma de Pagamento</th>
              <th className="danfce-num">Valor Pago (R$)</th>
            </tr>
          </thead>
          <tbody>
            {c.pagamentos.map((p, i) => (
              <tr key={i}>
                <td className="danfce-item-desc">{p.nomeCarteira}</td>
                <td className="danfce-num">{formatarMoeda(p.valorPago)}</td>
              </tr>
            ))}
          </tbody>
        </table>

        {/* ⛔ O "Valor aproximado dos tributos" (Lei 12.741/2012) NÃO é escrito aqui (2026-09-01):
            desde que o `VendaFiscalAssembler` passou a gravá-lo no `infCpl`, ele chega pronto no
            bloco abaixo, vindo do XML autorizado. Reescrevê-lo aqui o imprimiria DUAS vezes — e a
            versão do React não é a que a SEFAZ recebeu.

            O que sobra neste bloco é o detalhamento por ente federativo, que o `infCpl` não
            carrega: sem ele o cupom perderia informação que hoje imprime. */}
        <Separador />
        <div className="danfce-tributo">
          Tributos aproximados por ente ({formatarPercentual(percentualTributo)} % do total)
          <br />
          Federal: {formatarPercentual(percentualTrib(f.valorTribFederal))}% Estadual:{' '}
          {formatarPercentual(percentualTrib(f.valorTribEstadual))}% Municipal:{' '}
          {formatarPercentual(percentualTrib(f.valorTribMunicipal))}%
          {/* ⚠️ "Fonte: IBPT" SÓ quando não há `infCpl` (2026-09-01, visto no cupom impresso na
              tela): o texto do XML já termina com "(Lei 12.741/2012 - Fonte: IBPT)", e repetir a
              fonte gastava uma das 42 colunas da bobina dizendo duas vezes a mesma coisa. Na nota
              antiga, que cai no caminho de baixo, ela continua sendo a única menção à fonte. */}
          {!f.informacoesComplementares && (
            <>
              <br />
              Fonte: IBPT
            </>
          )}
        </div>

        <Separador />
        <div className="danfce-adicional">
          Informações adicionais de interesse do contribuinte
          <br />
          {/* ⭐ O texto vem INTEIRO do `infCpl` do XML assinado (2026-09-01), como no DANFE A4:
              número da venda, forma de pagamento, vendedor, a OBSERVAÇÃO que o operador digitou
              antes de emitir e o valor aproximado dos tributos.

              ⛔ Antes o React remontava aqui um texto PARECIDO com o do XML — e a observação do
              operador, que ia para a SEFAZ, simplesmente não saía no papel que o consumidor leva
              (medido na NFC-e nº 62). O cupom mostra o documento, nunca uma paráfrase dele.

              ⚠️ Nota emitida ANTES desta data cai no caminho de baixo: o XML dela não tem a tag,
              `extrairTag` devolve `null` e a reimpressão sairia com a área de informações
              adicionais EM BRANCO. Ali o texto antigo continua sendo melhor que nada — e é ele
              que traz o valor aproximado dos tributos, que naquele XML também não está. O ternário
              testa a veracidade (e não `!= null`) de propósito, para que string vazia caia no
              mesmo caminho. */}
          {f.informacoesComplementares ? (
            <span style={{ whiteSpace: 'pre-line' }}>{f.informacoesComplementares}</span>
          ) : (
            <>
              Venda Nº: {c.idVenda}
              {(c.numeroCaixa != null || c.codigoVendedor != null) && <br />}
              {c.numeroCaixa != null && `CX.: ${c.numeroCaixa}`}
              {c.numeroCaixa != null && c.codigoVendedor != null && ', '}
              {c.codigoVendedor != null && `VEN.: ${c.codigoVendedor}-${c.nomeVendedor}`}
              <br />
              Valor aproximado dos tributos {moedaDanfce(f.valorTotalTributos)}
            </>
          )}
          {f.baseIbsCbs > 0 && (
            <>
              <br />
              Reforma Tributária (LC 214/2025) - Base IBS/CBS: {formatarMoeda(f.baseIbsCbs)} | IBS:{' '}
              {formatarMoeda(f.valorIbsUf + f.valorIbsMun)} | CBS: {formatarMoeda(f.valorCbs)}
            </>
          )}
        </div>

        <Separador />
        <div className="danfce-consulta">
          {/* ⚠️ `urlConsultaChave` é `null` na NF-e 55 (2026-08-29) — sem a guarda, o cupom saía
              com a frase "Consulte pela chave de acesso em:" seguida de NADA, mandando o cliente
              a um endereço em branco. Sem endereço, a chave se apresenta sozinha. */}
          {f.urlConsultaChave ? (
            <>
              Consulte pela chave de acesso em:
              <br />
              {f.urlConsultaChave}
            </>
          ) : (
            'Chave de acesso:'
          )}
          <br />
          <span className="danfce-chave">{formatarChaveGrupos4(f.chaveAcesso)}</span>
        </div>

        <Separador />
        <div className="danfce-rodape">
          {qrDataUrl && (
            <div className="danfce-qrcode">
              <img src={qrDataUrl} alt={`QR Code da NFC-e ${f.chaveAcesso}`} />
            </div>
          )}
          <div className="danfce-info">
            <div>Consumidor</div>
            <b>{documentoConsumidorTexto}</b>
            {f.homologacao && (
              <>
                <br />
                <b>HOMOLOGAÇÃO - SEM VALOR FISCAL</b>
              </>
            )}
            <br />
            Número:{formatarNumeroNfce(f.numero)} Série:{f.serie}
            <br />
            {formatarDataHoraDanfce(c.dataVenda)} - Via Consumidor
            {f.protocolo && (
              <>
                <br />
                <br />
                Protocolo de Autorização:
                <br />
                <b>
                  {f.protocolo} {f.dataAutorizacao && formatarDataHoraDanfce(f.dataAutorizacao)}
                </b>
              </>
            )}
          </div>
        </div>
      </div>
    )
  },
)

export default DanfceImprimir
