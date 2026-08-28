-- V082 — Uma nota fiscal viva por venda (2026-08-27)
--
-- ⭐ O DEFEITO: nada impedia emitir a NFC-e da mesma venda duas vezes. `documento_fiscal_venda_ix`
-- é ÍNDICE, não UNIQUE, e o serviço não perguntava se a venda já tinha documento. Cada chamada
-- reservava número novo e sorteava um `cNF` novo, então a segunda nota passava limpa pelas
-- restrições de chave e de número.
--
-- ⚠️ E NÃO PRECISA DE MÁ-FÉ: duplo clique no PDV, retry de rede do front ou uma reimpressão
-- produzem a mesma sequência. Duas NFC-e autorizadas para uma venda = receita e ICMS declarados em
-- dobro, sem contrapartida em estoque — e só se desfaz cancelando na SEFAZ, dentro da janela de
-- 30 minutos da NFC-e.
--
-- ⚠️ POR QUE O ÍNDICE É PARCIAL, e quais situações entram:
--
--   entram  AUTORIZADO      a nota existe e vale
--           CONTINGENCIA    cupom na mão do consumidor, esperando o dreno
--           TRANSMITINDO    resposta da SEFAZ ainda desconhecida — o pior momento para emitir outra
--           ASSINADO        XML pronto, número já queimado
--
--   ficam de fora  REJEITADA / DENEGADA / NAO_EMITIDO  nunca viraram nota: a venda PRECISA poder
--                                                      tentar de novo
--                  CANCELADO                           a operação foi desfeita perante a SEFAZ;
--                                                      reemitir é legítimo
--                  RASCUNHO / VALIDADO                 ainda não são documento
--
-- ⚠️ E É POR MODELO: a mesma venda pode ter NFC-e 65 (consumidor) e NF-e 55 (devolução), que são
-- documentos diferentes sobre o mesmo fato — sem `modelo` na chave, a devolução seria barrada pela
-- nota da venda.

CREATE UNIQUE INDEX documento_fiscal_uma_por_venda_uk
    ON documento_fiscal (id_tenant, id_venda, modelo)
 WHERE id_venda IS NOT NULL
   AND situacao IN ('AUTORIZADO', 'CONTINGENCIA', 'TRANSMITINDO', 'ASSINADO');

COMMENT ON INDEX documento_fiscal_uma_por_venda_uk IS
  'Uma nota viva por (venda, modelo) — V082. Impede a segunda NFC-e do duplo clique. Rejeitada, '
  'denegada, não emitida e cancelada ficam de fora: nesses casos reemitir é o comportamento certo.';

-- ⚠️ O índice é a última linha de defesa, não a mensagem: quem recusa com texto legível é
-- `VendaFiscalService`, ANTES de reservar número. Chegar até aqui significaria queimar um número
-- da sequência para depois falhar — e número queimado vira buraco, que vira inutilização formal.
