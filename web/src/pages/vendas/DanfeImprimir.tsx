import { forwardRef } from 'react'
import { formatarDataHora, formatarSoData } from '../../lib/datas'
import { formatarChaveGrupos4, type Danfe } from '../../lib/danfe55'
import CodigoBarrasChave from './CodigoBarrasChave'
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
 * <p>`ref` aponta pro elemento raiz — o pai usa para capturar o PDF do envio por WhatsApp.
 *
 * <p>⚠️ `paraImpressao` marca a cópia que vai para a impressora, e ela precisa estar num
 * <b>portal</b> para o `<body>` (ver `PortalDeImpressao`): o isolamento de impressão é
 * `body > *:not(...)`, então uma cópia renderizada dentro do `#root` é apagada junto com ele e a
 * folha sai <b>em branco</b> — medido em 2026-08-29. A cópia da tela fica sem a classe.
 */
const DanfeImprimir = forwardRef<HTMLDivElement, { danfe: Danfe; paraImpressao?: boolean }>(
    function DanfeImprimir({ danfe, paraImpressao = false }, ref) {
  const d = danfe
  const entrada = d.tipoNf === 0

  return (
    <div className={`danfe-preview danfe-imprimir${paraImpressao ? ' documento-a4-imprimir' : ''}`} ref={ref}>
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
        <>
          {/* ⭐ MARCA D'ÁGUA em diagonal, como no modelo que ele mandou (2026-09-01). O texto é o
              mesmo do exemplo: "NFe sem Valor Fiscal - Homologação".

              ⚠️ É TEXTO, não `background-image`: background não sai na impressão (o navegador
              descarta por padrão), e este é justamente o aviso que não pode faltar no papel — uma
              nota de homologação impressa sem ele parece uma nota de verdade. Ver
              feedback_impressao_background_nao_sai; `print-color-adjust: exact` no CSS é o par
              obrigatório disso.

              ⚠️ `aria-hidden`: a tarja logo abaixo já diz a mesma coisa em texto normal, e um
              leitor de tela lendo as duas repetiria o aviso. */}
          <div className="danfe-marca-dagua" aria-hidden="true">
            NFe sem Valor Fiscal - Homologação
          </div>
          <div className="danfe-tarja-homologacao">
            NOTA FISCAL EMITIDA EM AMBIENTE DE HOMOLOGAÇÃO — SEM VALOR FISCAL
          </div>
        </>
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
          {/* ⭐ O código de barras CODE-128C da chave (2026-09-01) — exigência do leiaute, e o que
              permite ao fiscal na estrada conferir a nota com um leitor em vez de digitar 44
              números. O Nainer imprimia só os dígitos. */}
          <CodigoBarrasChave chave={d.chaveAcesso} />
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

      {/* ---------------------------------------------------------------- fatura / duplicata */}
      {/* ⚠️ Bloco OBRIGATÓRIO do leiaute do DANFE (Manual de Integração, quadro "FATURA/DUPLICATA"),
          e ele aparece MESMO SEM DUPLICATA — é onde a nota declara como foi paga. O PDV fecha a
          venda à vista ou em crediário e não gera duplicata, então o que vai aqui é a forma de
          pagamento; o texto da direita é o mesmo que o leiaute manda quando o pagamento não é por
          duplicata. Faltava inteiro até 2026-09-01. */}
      {/* ⚠️ Sem detalhar a forma de pagamento AQUI, de propósito: ela já vai por extenso no campo
          de informações complementares ("FORMA PGTO: …"), montado pelo servidor e presente no XML.
          Repetir no papel um dado que vem de outra fonte é como duas contagens do mesmo número
          divergirem depois — e o modelo que ele mandou faz exatamente assim. */}
      <div className="danfe-secao-titulo">FATURA / DUPLICATA</div>
      <div className="danfe-linha">
        <Campo
          rotulo=""
          valor="Outras formas de pagamento — verifique as informações no campo de dados adicionais"
          className="danfe-col-3"
        />
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
      {/* ⚠️ Três linhas, como manda o leiaute — até 2026-09-01 saía só a primeira.
          O Nainer não tem cadastro de transportadora nem de volumes (o PDV é venda de balcão), e
          por isso os campos saem vazios. ⛔ Vazio NÃO é o mesmo que ausente: o quadro precisa
          existir no papel porque é nele que o transportador ANOTA À MÃO, na entrega, o que não foi
          digitado — é a razão de o DANFE ter esse bloco mesmo em nota que não tem frete. */}
      <div className="danfe-secao-titulo">TRANSPORTADOR / VOLUMES TRANSPORTADOS</div>
      <div className="danfe-linha">
        <Campo rotulo="NOME / RAZÃO SOCIAL" valor="—" className="danfe-col-2" />
        <Campo rotulo="FRETE POR CONTA" valor="9 - Sem frete" />
        <Campo rotulo="CÓDIGO ANTT" valor="—" />
        <Campo rotulo="PLACA DO VEÍCULO" valor="—" />
        <Campo rotulo="UF" valor="—" />
        <Campo rotulo="CNPJ / CPF" valor="—" />
      </div>
      <div className="danfe-linha">
        <Campo rotulo="ENDEREÇO" valor="—" className="danfe-col-2" />
        <Campo rotulo="MUNICÍPIO" valor="—" />
        <Campo rotulo="UF" valor="—" />
        <Campo rotulo="INSCRIÇÃO ESTADUAL" valor="—" />
      </div>
      <div className="danfe-linha">
        <Campo rotulo="QUANTIDADE" valor="—" alinhar="direita" />
        <Campo rotulo="ESPÉCIE" valor="—" />
        <Campo rotulo="MARCA" valor="—" />
        <Campo rotulo="NUMERAÇÃO" valor="—" />
        <Campo rotulo="PESO BRUTO" valor="—" alinhar="direita" />
        <Campo rotulo="PESO LÍQUIDO" valor="—" alinhar="direita" />
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

      {/* ---------------------------------------------------------------- ISSQN */}
      {/* ⚠️ Quadro obrigatório do leiaute, e ele fica ZERADO de propósito nesta nota: serviço não
          entra em NFC-e/NF-e — é ISS municipal, e o documento dele é a NFS-e, emitida à parte pelo
          mesmo PDV desde 2026-09-01. O quadro existe porque o DANFE é um formulário padronizado: o
          contador espera encontrá-lo, e a ausência dele levanta dúvida que o zero não levanta. */}
      <div className="danfe-secao-titulo">CÁLCULO DO ISSQN</div>
      <div className="danfe-linha">
        {/* ⚠️ Em branco de propósito, e o modelo que ele mandou faz igual: a Inscrição Municipal só
            tem função neste quadro quando a nota declara SERVIÇO, e uma NFC-e/NF-e do PDV nunca
            declara — o serviço sai em NFS-e, documento próprio. Trazer o campo do cadastro da
            empresa até aqui seria plumbing para um valor decorativo. */}
        <Campo rotulo="INSCRIÇÃO MUNICIPAL" valor="—" />
        <Campo rotulo="VALOR TOTAL DOS SERVIÇOS" valor={moeda(0)} alinhar="direita" />
        <Campo rotulo="BASE DE CÁLCULO DO ISSQN" valor={moeda(0)} alinhar="direita" />
        <Campo rotulo="VALOR DO ISSQN" valor={moeda(0)} alinhar="direita" />
      </div>

      {/* ---------------------------------------------------------------- dados adicionais */}
      <div className="danfe-secao-titulo">DADOS ADICIONAIS</div>
      <div className="danfe-linha">
        <div className="danfe-campo danfe-col-3">
          <span className="danfe-campo-rotulo">INFORMAÇÕES COMPLEMENTARES</span>
          {/* ⭐ O texto vem INTEIRO do `infCpl` do XML (2026-09-01), inclusive o valor aproximado
              dos tributos e a observação do operador.

              ⛔ Antes o "Valor aproximado dos tributos" era desenhado AQUI, pelo React, e não
              existia no XML autorizado — ou seja, a informação que a Lei 12.741/2012 exige NO
              DOCUMENTO FISCAL estava só no retrato dele. Agora o backend a coloca no `infCpl`, e o
              DANFE apenas mostra o que a SEFAZ recebeu. Reintroduzir o texto aqui faria a linha
              aparecer DUAS vezes no papel. */}
          <span className="danfe-campo-valor danfe-infcpl" style={{ whiteSpace: 'pre-line' }}>
            {/* ⚠️ `||`, não `??`: nota emitida ANTES de 2026-09-01 volta com string VAZIA, não
                null, e o `??` deixava a caixa em branco — visto na tela, não deduzido. */}
            {d.informacoesComplementares || '—'}
          </span>
        </div>
        <Campo rotulo="RESERVADO AO FISCO" valor="" />
      </div>
    </div>
  )
})

export default DanfeImprimir
