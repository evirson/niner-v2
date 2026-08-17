/**
 * Transporte com a SEFAZ (bloco B6 de docs/MODULOFISCAL.md §17.1) — mTLS, envelope SOAP e
 * resolução do autorizador por UF.
 *
 * <p><b>Sem Axis2 e sem lib de NF-e</b> (DF7, plano B provado no B0): o {@code HttpClient} do
 * JDK faz mTLS nativamente. O que a lib traria de bom — modelo/XSD, assinatura, QR Code — já foi
 * resolvido com JDK puro nos blocos B5 e B6.
 *
 * <p>Dois invariantes que este pacote existe para garantir:
 * <ul>
 *   <li><b>Um SSLContext por empresa.</b> Cada nota vai com o certificado do seu lojista.
 *       Compartilhar o contexto entre empresas emitiria nota no CNPJ errado — e seria
 *       autorizada, sem erro visível. Testado em {@code SefazTransporteTest}.</li>
 *   <li><b>Endpoint é dado, não código</b> (F10). Tudo vem de {@code cfg_uf_autorizador}:
 *       acrescentar uma UF é uma linha na tabela.</li>
 * </ul>
 */
package com.vetor.niner.fiscal.sefaz;
