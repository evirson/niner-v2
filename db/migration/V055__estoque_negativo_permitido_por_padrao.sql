-- V055 — "Permite Quantidade de Estoque Negativo" nasce LIGADO (2026-08-20, algumas horas depois
-- da V054).
--
-- POR QUE UM ARQUIVO NOVO PARA MUDAR UM DEFAULT
-- A V054 já rodou. Editar migration aplicada é o que travou a publicação em 2026-08-19
-- (`Validate failed: checksum mismatch`), e o sintoma não aparece na máquina de quem edita —
-- banco recriado do zero fica coerente. Schema é sempre para frente.
--
-- A DECISÃO
-- A V054 nasceu bloqueando (`false`) porque o pedido descrevia o bloqueio como o comportamento
-- desejado. Perguntado sobre o padrão, o dono do produto foi claro: **"na maioria das vezes o
-- usuário não vai querer controlar o estoque, aí se ficar negativo não tem problema"**. Ou seja,
-- a loja típica do produto não faz gestão de estoque, e travar a venda dela por causa de um
-- cadastro que ninguém alimenta seria travar o caixa por um número que não existe.
--
-- O controle continua inteiro — só passou a ser **opt-in**: quem quer estoque confiável desmarca
-- "Permite quantidade de estoque negativo" em Parâmetros do Sistema → Estoque, e aí nenhuma
-- rotina tira mais do que existe (a regra continua na trigger da V054, cobrindo inclusive as
-- rotinas que ninguém lembra que debitam, como o Cancelamento de Entrada).
ALTER TABLE cfg_geral
  ALTER COLUMN cfg_permite_estoque_negativo SET DEFAULT true;

-- ⚠️ As linhas existentes também voltam para `true`, e isso é deliberado: a coluna nasceu na V054
-- poucas horas antes, ninguém chegou a **escolher** `false` — foi um default que durou uma sessão.
-- Deixá-las bloqueando faria as lojas já cadastradas se comportarem diferente das novas, por um
-- acidente de cronologia. (Não há cliente real em produção nesta data; se houvesse, esta linha
-- precisaria de conversa antes.)
UPDATE cfg_geral SET cfg_permite_estoque_negativo = true;

COMMENT ON COLUMN cfg_geral.cfg_permite_estoque_negativo IS
  'Ligado (padrao): movimentacao pode deixar produto_estoque.qtd_estoque negativo - a loja tipica nao faz gestao de estoque e a venda nao deve travar. Desligado: a trigger fn_atualiza_estoque_movimento recusa o debito, contando o saldo por EMPRESA. A Devolucao ao Fornecedor tem regra propria e nao consulta este parametro.';
