-- V063 — pedido de marketplace vira `venda` (decisão do dono do produto, 2026-08-25).
--
-- Ver docs/MODULOMARKETPLACE.md §8. A escolha foi "pedido de marketplace VIRA venda", e o modelo
-- do legado já acomodava isso quase inteiro:
--   * venda.id_caixa  aceita NULL  -> venda de marketplace NÃO entra no caixa (o dinheiro do canal
--                                    cai na conta dias depois, não na gaveta; vinculá-la quebraria
--                                    a conferência do Fechamento de Caixa);
--   * venda.id_cliente aceita NULL -> o comprador do marketplace não precisa virar cadastro;
--   * produto_movimento_detalhe.id_funcionario aceita NULL -> sem vendedor, logo SEM COMISSÃO,
--                                    sem nenhuma linha de código (o relatório agrupa por
--                                    funcionário).
-- Por isso esta migration é só um valor de enum: o resto do caminho já existia.
--
-- ⚠️ ALTER TYPE ... ADD VALUE não roda dentro de bloco de transação em versões antigas do
-- Postgres; no 18 roda, mas o valor novo só fica visível para outros comandos APÓS o commit.
-- Como nada mais aqui usa o valor, não há problema.
--
-- ⚠️ IF NOT EXISTS é deliberado: se um banco já tiver recebido o valor por outra via, a migration
-- não pode quebrar (o Flyway não deixa editar arquivo aplicado — ver CLAUDE.md).

ALTER TYPE origem_venda ADD VALUE IF NOT EXISTS 'MARKETPLACE';

COMMENT ON TYPE origem_venda IS
  'Como a venda entrou no ERP: PDV (balcão), IMPORTACAO (carga de sistema legado) ou '
  'MARKETPLACE (pedido importado de canal de venda — sem caixa e sem comissão, ver '
  'docs/MODULOMARKETPLACE.md §8).';
