/**
 * NFS-e — o documento fiscal de serviço, contra o Sefin Nacional (blocos S5–S7 de
 * {@code docs/MODULONFSE.md}).
 *
 * <h2>Por que este pacote existe separado de {@code fiscal.documento}</h2>
 *
 * <p>Não é organização estética: a NFS-e não compartilha nem o layout, nem o tributo, nem o
 * calendário normativo da NF-e/NFC-e. O que ela <b>compartilha de verdade</b> é a infraestrutura,
 * e essa é reusada sem cópia — certificado ({@code fiscal.certificado}), cifra de segredo
 * ({@code comum.seguranca}), guarda imutável do XML ({@code comum.armazenamento}, área
 * {@code FISCAL_XML}) e a tela de Documentos Fiscais.
 *
 * <h2>Cinco fatos medidos contra o SEFIN real, que contradizem a documentação de referência</h2>
 *
 * <p>Estão detalhados em {@code docs/MODULONFSE.md} §2.6 e §2.7, e cada um custou uma sondagem:
 *
 * <ol>
 *   <li><b>A chave de acesso NÃO é derivável.</b> Leva o {@code nNFSe} atribuído pelo SEFIN e um
 *       código numérico <b>aleatório</b> de 9 posições. Nota órfã (timeout depois de o SEFIN ter
 *       gerado) se recupera por {@code GET /dps/{id}} — nunca por chave calculada.</li>
 *   <li><b>A DPS não tem itens.</b> {@code serv}, {@code cServ}, {@code xDescServ} e {@code vServ}
 *       são todos 1-1 no leiaute. Daí uma NFS-e por código de serviço distinto da venda.</li>
 *   <li><b>Para ME/EPP o {@code indTotTrib} é proibido</b> ({@code E0712}) e o bloco
 *       {@code totTrib} é exigido pelo schema ({@code E1235}): a alíquota efetiva do Simples é
 *       pré-requisito de emissão, e é a fronteira real do "configurar sem suporte".</li>
 *   <li><b>Com {@code tpEmit=1} o prestador manda só o CNPJ</b> — IM, {@code xNome} e endereço
 *       dão {@code E0120}/{@code E0121}/{@code E0128}. O SEFIN já tem esses dados.</li>
 *   <li><b>O contêiner de erro muda de nome:</b> a emissão recusa em {@code {"erros":[{"Codigo"…}]}}
 *       e o evento em {@code {"erro":[{"codigo"…}]}} — singular e minúsculo. O parser cobre as
 *       quatro combinações; cobrir só uma faz a recusa de cancelamento perder o código.</li>
 * </ol>
 *
 * <h2>Ordem de validação do SEFIN</h2>
 *
 * <p>Deduzida das sondagens: declaração/encoding ({@code E1229}) → schema XSD ({@code E1235}) →
 * assinatura ({@code E0714}) → regras de negócio. <b>Um erro esconde os seguintes</b> — enquanto
 * um código anterior estiver de pé, nada depois dele foi exercitado.
 */
package com.vetor.niner.fiscal.nfse;
