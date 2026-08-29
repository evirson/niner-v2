-- V095 — o fechamento do caixa exige sangrar o excedente do fundo de troco.
--
-- ⭐ A DECISÃO (dono do produto, 2026-08-29)
--
-- Depois que a Sangria de Caixa (V094) nasceu, sobrou uma pergunta: e o dinheiro que fica na gaveta
-- quando o caixa FECHA? Apresentei três saídas e ele escolheu a primeira:
--
--   "O fechamento exige sangrar até o fundo — o operador é obrigado a deixar na gaveta exatamente
--    o que vai abrir amanhã."
--
-- É a disciplina que faz o número do Fluxo de Caixa fechar. O fundo passou a contar UMA vez por
-- operador (o do último caixa aberto); se o operador fecha com R$ 800 e abre amanhã com R$ 200, os
-- R$ 600 que continuam fisicamente na gaveta somem do saldo. Com esta regra, o que sobra vai para
-- o banco pela sangria e aparece do outro lado — nada desaparece.
--
-- ---------------------------------------------------------------------------------------------
-- ⚠️ POR QUE É PARÂMETRO, E NÃO REGRA FIXA
--
-- Porque ela BLOQUEIA o fechamento do caixa, e sangria exige uma conta corrente cadastrada. A loja
-- pequena que paga despesa em dinheiro e leva o resto para casa — que é parte do público deste
-- produto — não tem conta cadastrada e ficaria SEM CONSEGUIR FECHAR O CAIXA no primeiro dia.
--
-- É o mesmo raciocínio que deixou `cfg_permite_estoque_negativo` LIGADO por padrão (V055): travar
-- a operação por um controle que a loja não alimenta é pior que não ter o controle.
--
-- ⭐ Mas aqui o padrão é o INVERSO daquele caso — nasce LIGADO, porque foi a escolha explícita
-- dele e porque é a opção que mantém o Fluxo de Caixa correto. Quem não quiser, desliga em
-- Parâmetros do Sistema; o custo de desligar está escrito no COMMENT abaixo.

ALTER TABLE cfg_geral
  ADD COLUMN cfg_exige_sangria_fechamento boolean NOT NULL DEFAULT true;

COMMENT ON COLUMN cfg_geral.cfg_exige_sangria_fechamento IS
  'Ligado (padrão): o fechamento do caixa RECUSA enquanto houver dinheiro em espécie acima do '
  'fundo de troco com que o caixa foi aberto — o operador precisa sangrar o excedente para uma '
  'conta corrente antes de fechar (decisão do dono do produto, 2026-08-29). É o que mantém o Fluxo '
  'de Caixa correto: o fundo conta uma vez por operador e o resto vira sangria, visível no banco. '
  '⚠️ Desligando, o fechamento passa, mas o dinheiro que ficar na gaveta acima do fundo SOME do '
  'saldo do Fluxo de Caixa até alguém abrir o caixa seguinte com o valor real. Desligue só em loja '
  'que não deposita em banco. ⚠️ A regra só cobra quando há EXCEDENTE: caixa que ficou abaixo do '
  'fundo (pagou despesa em dinheiro) fecha normalmente — não há o que sangrar, e travar ali '
  'prenderia o operador sem saída.';
