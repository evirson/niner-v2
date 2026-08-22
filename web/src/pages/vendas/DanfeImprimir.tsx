import { forwardRef } from 'react'
import { formatarDataHora, formatarSoData } from '../../lib/datas'
import { formatarChaveGrupos4, type Danfe } from '../../lib/danfe55'
import { formatarMoeda, mascararCpfCnpj } from '../../lib/masks'

function moeda(v: number | null | undefined): string {
  return formatarMoeda(v ?? 0)
}

function doc(valor: string | null): string {
  if (!valor) return '—'
  return mascararCpfCnpj(valor, valor.replace(/\D/g, '').length === 11)
}

/** Célula rotulada — o DANFE inteiro é uma grade de caixas com rótulo pequeno em cima. */
function Campo({
  rotulo,
  valor,
  className,
  alinhar,
}: {
  rotulo: string
  valor: React.ReactNode
  className?: string
  alinhar?: 'direita' | 'centro'
}) {
  return (
    <div className={`danfe-campo ${className ?? ''}`}>
      <span className="danfe-campo-rotulo">{rotulo}</span>
      <span
        className="danfe-campo-valor"
        style={alinhar === 'direita' ? { textAlign: 'right' } : alinhar === 'centro' ? { textAlign: 'center' } : undefined}
      >
        {valor}
      </span>
    </div>
  )
}

/**
 * DANFE do modelo 55 em A4 — o documento auxiliar impresso da NF-e, hoje usado pela **nota de
 * devolução** (§10.2, bloco B9). Layout espelhado de uma NF-e real trazida pelo dono do produto
 * (`c:\fix\danfe_55.pdf`, emitida por outro sistema), na ordem que o MOC define: canhoto
 * destacável → cabeçalho em 3 colunas (emitente / DANFE+tipo / chave de acesso) → natureza da
 * operação e protocolo → inscrições → destinatário → cálculo do imposto → transportador → tabela
 * de produtos → dados adicionais.
 *
 * <p>⚠️ **Não confundir com o `DanfceImprimir.tsx`** — aquele é o cupom da NFC-e, em bobina
 * térmica de 80mm, com QR Code e calibragem própria (42 colunas, Consolas em negrito). Este aqui é
 * papel A4 comum, sem QR: a NF-e se confere pela chave de acesso e pelo protocolo, no portal
 * nacional. Os dois documentos não compartilham nada além do nome parecido.
 *
 * <p>`ref` aponta pro elemento raiz — o pai usa para `window.print()` (a calibragem de impressão
 * está em `.danfe-imprimir`, `styles.css`) e para capturar o PDF do envio por WhatsApp.
 */
const DanfeImprimir = forwardRef<HTMLDivElement, { danfe: Danfe }>(function DanfeImprimir({ danfe }, ref) {
  const d = danfe
  const entrada = d.tipoNf === 0

  return (
    <div className="danfe-preview danfe-imprimir documento-a4-imprimir" ref={ref}>
      {/* ---------------------------------------------------------------- canhoto */}
      <div className="danfe-canhoto">
        <div className="danfe-canhoto-texto">
          <div>
            RECEBEMOS DE <strong>{d.emitente.nome}</strong> OS PRODUTOS CONSTANTES DA NOTA FISCAL ELETRÔNICA
            INDICADA ABAIXO
          </div>
          <div className="danfe-canhoto-resumo">
            EMISSÃO: {formatarSoData(d.dataEmissao)} · VALOR TOTAL: R$ {moeda(d.totais.valorTotal)} ·{' '}
            DESTINATÁRIO: {d.destinatario.nome}
          </div>
          <div className="danfe-canhoto-assinatura">
            <span>DATA DE RECEBIMENTO</span>
            <span>IDENTIFICAÇÃO E ASSINATURA DO RECEBEDOR</span>
          </div>
        </div>
        <div className="danfe-canhoto-nf">
          <div className="danfe-canhoto-nf-titulo">NF-e</div>
          <div>Nº {String(d.numero).padStart(9, '0')}</div>
          <div>Série {String(d.serie).padStart(3, '0')}</div>
        </div>
      </div>

      {d.homologacao && (
        <div className="danfe-tarja-homologacao">
          NOTA FISCAL EMITIDA EM AMBIENTE DE HOMOLOGAÇÃO — SEM VALOR FISCAL
        </div>
      )}

      {/* ---------------------------------------------------------------- cabeçalho */}
      <div className="danfe-cabecalho">
        <div className="danfe-bloco danfe-emitente">
          <span className="danfe-campo-rotulo">IDENTIFICAÇÃO DO EMITENTE</span>
          <div className="danfe-emitente-nome">{d.emitente.nome}</div>
          <div>{d.emitente.enderecoLinha1}</div>
          <div>
            {d.emitente.enderecoLinha2} · CEP {d.emitente.cep ?? '—'}
          </div>
          <div>
            {d.emitente.municipio} - {d.emitente.uf}
            {d.emitente.telefone && ` · Fone: ${d.emitente.telefone}`}
          </div>
        </div>

        <div className="danfe-bloco danfe-titulo-danfe">
          <div className="danfe-danfe-nome">DANFE</div>
          <div className="danfe-danfe-sub">
            Documento Auxiliar da
            <br />
            Nota Fiscal Eletrônica
          </div>
          <div className="danfe-tipo-operacao">
            <div className={entrada ? 'danfe-tipo-marcado' : ''}>0 - ENTRADA</div>
            <div className={entrada ? '' : 'danfe-tipo-marcado'}>1 - SAÍDA</div>
            <div className="danfe-tipo-quadrado">{entrada ? '0' : '1'}</div>
          </div>
          <div className="danfe-danfe-numero">
            Nº {String(d.numero).padStart(9, '0')}
            <br />
            Série {String(d.serie).padStart(3, '0')}
            <br />
            Folha 1/1
          </div>
        </div>

        <div className="danfe-bloco danfe-chave">
          <span className="danfe-campo-rotulo">CHAVE DE ACESSO</span>
          <div className="danfe-chave-valor mono">{formatarChaveGrupos4(d.chaveAcesso)}</div>
          <div className="danfe-chave-consulta">
            Consulta de autenticidade no portal nacional da NF-e
            <br />
            www.nfe.fazenda.gov.br/portal ou no site da Sefaz autorizadora
          </div>
        </div>
      </div>

      <div className="danfe-linha">
        <Campo rotulo="NATUREZA DA OPERAÇÃO" valor={d.naturezaOperacao} className="danfe-col-2" />
        <Campo
          rotulo="PROTOCOLO DE AUTORIZAÇÃO DE USO"
          valor={d.protocolo ? `${d.protocolo} - ${formatarDataHora(d.dataAutorizacao)}` : '—'}
        />
      </div>

      <div className="danfe-linha">
        <Campo rotulo="INSCRIÇÃO ESTADUAL" valor={d.emitente.inscricaoEstadual ?? '—'} />
        <Campo rotulo="INSCRIÇÃO ESTADUAL DO SUBST. TRIBUT." valor="—" />
        <Campo rotulo="CNPJ / CPF" valor={doc(d.emitente.cpfCnpj)} />
      </div>

      {/* ---------------------------------------------------------------- destinatário */}
      <div className="danfe-secao-titulo">DESTINATÁRIO / REMETENTE</div>
      <div className="danfe-linha">
        <Campo rotulo="NOME / RAZÃO SOCIAL" valor={d.destinatario.nome} className="danfe-col-2" />
        <Campo rotulo="CNPJ / CPF" valor={doc(d.destinatario.cpfCnpj)} />
        <Campo rotulo="DATA DA EMISSÃO" valor={formatarSoData(d.dataEmissao)} />
      </div>
      <div className="danfe-linha">
        <Campo rotulo="ENDEREÇO" valor={d.destinatario.enderecoLinha1 || '—'} className="danfe-col-2" />
        <Campo rotulo="BAIRRO / DISTRITO" valor={d.destinatario.enderecoLinha2 ?? '—'} />
        <Campo rotulo="CEP" valor={d.destinatario.cep ?? '—'} />
      </div>
      <div className="danfe-linha">
        <Campo rotulo="MUNICÍPIO" valor={d.destinatario.municipio ?? '—'} />
        <Campo rotulo="UF" valor={d.destinatario.uf ?? '—'} />
        <Campo rotulo="FONE / FAX" valor={d.destinatario.telefone ?? '—'} />
        <Campo rotulo="INSCRIÇÃO ESTADUAL" valor={d.destinatario.inscricaoEstadual ?? '—'} />
      </div>

      {/* ---------------------------------------------------------------- cálculo do imposto */}
      <div className="danfe-secao-titulo">CÁLCULO DO IMPOSTO</div>
      <div className="danfe-linha">
        <Campo rotulo="BASE DE CÁLC. DO ICMS" valor={moeda(d.totais.baseCalculoIcms)} alinhar="direita" />
        <Campo rotulo="VALOR DO ICMS" valor={moeda(d.totais.valorIcms)} alinhar="direita" />
        <Campo rotulo="BASE DE CÁLC. ICMS S.T." valor={moeda(d.totais.baseCalculoSt)} alinhar="direita" />
        <Campo rotulo="VALOR DO ICMS SUBST." valor={moeda(d.totais.valorIcmsSt)} alinhar="direita" />
        <Campo rotulo="VALOR DO PIS" valor={moeda(d.totais.valorPis)} alinhar="direita" />
        <Campo rotulo="V. TOTAL PRODUTOS" valor={moeda(d.totais.valorProdutos)} alinhar="direita" />
      </div>
      <div className="danfe-linha">
        <Campo rotulo="VALOR DO FRETE" valor={moeda(d.totais.valorFrete)} alinhar="direita" />
        <Campo rotulo="VALOR DO SEGURO" valor={moeda(d.totais.valorSeguro)} alinhar="direita" />
        <Campo rotulo="DESCONTO" valor={moeda(d.totais.valorDesconto)} alinhar="direita" />
        <Campo rotulo="OUTRAS DESPESAS" valor={moeda(d.totais.valorOutros)} alinhar="direita" />
        <Campo rotulo="VALOR DA COFINS" valor={moeda(d.totais.valorCofins)} alinhar="direita" />
        <Campo rotulo="V. TOTAL DA NOTA" valor={moeda(d.totais.valorTotal)} alinhar="direita" />
      </div>

      {/* ---------------------------------------------------------------- transportador */}
      <div className="danfe-secao-titulo">TRANSPORTADOR / VOLUMES TRANSPORTADOS</div>
      <div className="danfe-linha">
        <Campo rotulo="NOME / RAZÃO SOCIAL" valor="—" className="danfe-col-2" />
        <Campo rotulo="FRETE POR CONTA" valor="9 - Sem frete" />
        <Campo rotulo="CNPJ / CPF" valor="—" />
      </div>

      {/* ---------------------------------------------------------------- produtos */}
      <div className="danfe-secao-titulo">DADOS DOS PRODUTOS / SERVIÇOS</div>
      <table className="danfe-itens">
        <thead>
          <tr>
            <th>CÓDIGO</th>
            <th>DESCRIÇÃO DO PRODUTO / SERVIÇO</th>
            <th>NCM/SH</th>
            <th>O/CST</th>
            <th>CFOP</th>
            <th>UN</th>
            <th className="danfe-num">QUANT</th>
            <th className="danfe-num">VALOR UNIT</th>
            <th className="danfe-num">VALOR TOTAL</th>
            <th className="danfe-num">B.CÁLC ICMS</th>
            <th className="danfe-num">VALOR ICMS</th>
            <th className="danfe-num">ALÍQ ICMS</th>
          </tr>
        </thead>
        <tbody>
          {d.itens.map((i) => (
            <tr key={i.numeroItem}>
              <td className="mono">{i.codigoProduto}</td>
              <td>{i.descricao}</td>
              <td className="mono">{i.ncm ?? '—'}</td>
              <td className="mono">
                {i.origemMercadoria}/{i.cstOuCsosn ?? '—'}
              </td>
              <td className="mono">{i.cfop}</td>
              <td>{i.unidadeComercial}</td>
              <td className="danfe-num mono">{i.quantidade}</td>
              <td className="danfe-num mono">{moeda(i.valorUnitario)}</td>
              <td className="danfe-num mono">{moeda(i.valorTotal)}</td>
              <td className="danfe-num mono">{moeda(i.baseCalculoIcms)}</td>
              <td className="danfe-num mono">{moeda(i.valorIcms)}</td>
              <td className="danfe-num mono">{moeda(i.aliquotaIcms)}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {/* ---------------------------------------------------------------- dados adicionais */}
      <div className="danfe-secao-titulo">DADOS ADICIONAIS</div>
      <div className="danfe-linha">
        <div className="danfe-campo danfe-col-3">
          <span className="danfe-campo-rotulo">INFORMAÇÕES COMPLEMENTARES</span>
          <span className="danfe-campo-valor danfe-infcpl">
            {d.informacoesComplementares ?? '—'}
            {d.totais.valorTotalTributos > 0 && (
              <>
                <br />
                Valor aproximado dos tributos: R$ {moeda(d.totais.valorTotalTributos)} (Lei 12.741/2012 — Fonte:
                IBPT).
              </>
            )}
          </span>
        </div>
        <Campo rotulo="RESERVADO AO FISCO" valor="" />
      </div>
    </div>
  )
})

export default DanfeImprimir
