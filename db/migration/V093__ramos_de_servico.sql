-- V093 — cinco ramos de SERVIÇO, ao lado dos 28 de varejo (V072).
--
-- ⛔ O PROBLEMA
--
-- Os 28 ramos da V072 são todos de varejo. Com o módulo de Serviços no ar (V085–V087, Ordem de
-- Serviço), uma oficina que se cadastra hoje escolhe `AUTOPECAS` ou `OUTROS` — e ela não vende
-- autopeças, ela conserta carro. O dado de segmentação nasce errado, e é justamente o dado que
-- responde "para quem o Nainer está sendo vendido".
--
-- DECISÃO DO DONO DO PRODUTO (2026-08-29): *"Pode fazer como você está sugerindo"* — acrescentar
-- oficina, salão/barbearia, assistência técnica, clínica veterinária e lava-rápido.
--
-- ---------------------------------------------------------------------------------------------
-- ⭐ OS CNAEs NÃO FORAM DIGITADOS DE MEMÓRIA — mesma regra da V072, do NCM e das 27 UFs.
--
-- Carregados de `servicodados.ibge.gov.br/api/v2/cnae/subclasses` (1.332 subclasses, baixadas em
-- 2026-08-29) e cada descrição conferida contra a resposta antes de virar SQL.
--
-- ⚠️ E a fonte pagou o próprio custo na hora: eu ia escrever `4541206` para motocicletas, de
-- memória. A tabela oficial mostrou que 4541206 é *comércio a varejo de peças e acessórios para
-- motocicletas* — já mapeado para AUTOPEÇAS na V072 — e que o serviço é **4543900**. Como `cnae` é
-- PRIMARY KEY, o palpite teria estourado a migration; se não estourasse, teria mandado toda oficina
-- de motos para o ramo errado, em silêncio.
--
-- ---------------------------------------------------------------------------------------------
-- ORDEM DE EXIBIÇÃO. A V072 deixou "Outros" por último (ordem 28) e alfabético antes. Os cinco
-- novos entram na ordem alfabética do conjunto, e "Outros" é empurrado para o fim — senão ele
-- ficaria no meio da lista, que é onde ninguém procura o fallback.

-- 1. "Outros" sai do caminho antes de os novos ocuparem as posições.
UPDATE cfg_ramo_atividade SET ordem = 99 WHERE codigo = 'OUTROS';

INSERT INTO cfg_ramo_atividade (id_ramo, codigo, nome, ordem) VALUES
  (29, 'ASSISTENCIA_TECNICA',   'Assistência técnica e consertos',        29),
  (30, 'CLINICA_VETERINARIA',   'Clínica veterinária',                    30),
  (31, 'LAVA_RAPIDO',           'Lava-rápido e estética automotiva',      31),
  (32, 'OFICINA_MECANICA',      'Oficina mecânica e funilaria',           32),
  (33, 'SALAO_BARBEARIA',       'Salão de beleza e barbearia',            33);

-- 2. Reordena o conjunto inteiro por nome, mantendo "Outros" no fim. Fazer em SQL — em vez de
--    redigitar 33 números — evita a classe de erro que a própria V072 evitou: lista digitada
--    diverge do que ela descreve no dia em que alguém insere no meio.
WITH ordenado AS (
  SELECT id_ramo, row_number() OVER (ORDER BY nome) AS nova
    FROM cfg_ramo_atividade
   WHERE codigo <> 'OUTROS'
)
UPDATE cfg_ramo_atividade r SET ordem = o.nova
  FROM ordenado o WHERE o.id_ramo = r.id_ramo;

UPDATE cfg_ramo_atividade SET ordem = (SELECT max(ordem) + 1 FROM cfg_ramo_atividade WHERE codigo <> 'OUTROS')
 WHERE codigo = 'OUTROS';

-- ---------------------------------------------------------------------------------------------
-- 3. CNAE → ramo. Um CNAE aponta para no máximo um ramo (PK), então a sugestão é determinística.
--    ⚠️ Código genérico fica FORA do mapa, como na V072: sugerir ramo a partir de código que serve
--    a dezenas de atividades é chutar com cara de certeza. Por isso `9529199` (reparação de
--    "outros objetos") e `9609299` ("outras atividades de serviços pessoais") não entram.

INSERT INTO cfg_ramo_cnae (cnae, id_ramo) VALUES
  -- Oficina mecânica e funilaria
  ('4520001', 32),  -- SERVIÇOS DE MANUTENÇÃO E REPARAÇÃO MECÂNICA DE VEÍCULOS AUTOMOTORES
  ('4520002', 32),  -- SERVIÇOS DE LANTERNAGEM OU FUNILARIA E PINTURA DE VEÍCULOS AUTOMOTORES
  ('4520003', 32),  -- SERVIÇOS DE MANUTENÇÃO E REPARAÇÃO ELÉTRICA DE VEÍCULOS AUTOMOTORES
  ('4520004', 32),  -- SERVIÇOS DE ALINHAMENTO E BALANCEAMENTO DE VEÍCULOS AUTOMOTORES
  ('4520006', 32),  -- SERVIÇOS DE BORRACHARIA PARA VEÍCULOS AUTOMOTORES
  ('4520007', 32),  -- SERVIÇOS DE INSTALAÇÃO, MANUTENÇÃO E REPARAÇÃO DE ACESSÓRIOS PARA VEÍCULOS
  ('4520008', 32),  -- SERVIÇOS DE CAPOTARIA
  ('4543900', 32),  -- MANUTENÇÃO E REPARAÇÃO DE MOTOCICLETAS E MOTONETAS  ⚠️ não é 4541206

  -- Lava-rápido e estética automotiva
  -- ⚠️ Mora na mesma família 4520 da oficina, e é ramo PRÓPRIO de propósito: o lava-rápido não tem
  -- peça, não tem mecânico e o ticket é outro — juntá-lo à oficina apagaria a diferença que a
  -- segmentação existe para enxergar.
  ('4520005', 31),  -- SERVIÇOS DE LAVAGEM, LUBRIFICAÇÃO E POLIMENTO DE VEÍCULOS AUTOMOTORES

  -- Salão de beleza e barbearia
  ('9602501', 33),  -- CABELEIREIROS, MANICURE E PEDICURE
  ('9602502', 33),  -- ATIVIDADES DE ESTÉTICA E OUTROS SERVIÇOS DE CUIDADOS COM A BELEZA

  -- Assistência técnica e consertos
  ('9511800', 29),  -- REPARAÇÃO E MANUTENÇÃO DE COMPUTADORES E DE EQUIPAMENTOS PERIFÉRICOS
  ('9512600', 29),  -- REPARAÇÃO E MANUTENÇÃO DE EQUIPAMENTOS DE COMUNICAÇÃO
  ('9521500', 29),  -- REPARAÇÃO E MANUTENÇÃO DE EQUIPAMENTOS ELETROELETRÔNICOS DE USO PESSOAL
  ('9529101', 29),  -- REPARAÇÃO DE CALÇADOS, DE BOLSAS E ARTIGOS DE VIAGEM
  ('9529102', 29),  -- CHAVEIROS
  ('9529103', 29),  -- REPARAÇÃO DE RELÓGIOS
  ('9529104', 29),  -- REPARAÇÃO DE BICICLETAS, TRICICLOS E OUTROS VEÍCULOS NÃO MOTORIZADOS
  ('9529105', 29),  -- REPARAÇÃO DE ARTIGOS DO MOBILIÁRIO
  ('9529106', 29),  -- REPARAÇÃO DE JÓIAS

  -- Clínica veterinária
  ('7500100', 30),  -- ATIVIDADES VETERINÁRIAS

  -- ⭐ Pet shop (ramo 25, que já existia) ganha os dois CNAEs que descrevem o SERVIÇO dele. A V072
  -- só mapeou o comércio (`4789004`), então um pet shop cujo CNAE principal fosse banho e tosa não
  -- recebia sugestão nenhuma — e é justamente esse o pet shop que o módulo de Serviços atende.
  ('9609207', 25),  -- ALOJAMENTO DE ANIMAIS DOMÉSTICOS
  ('9609208', 25);  -- HIGIENE E EMBELEZAMENTO DE ANIMAIS DOMÉSTICOS

COMMENT ON TABLE cfg_ramo_atividade IS
  'Ramos de atividade atendidos pelo Nainer: 28 de varejo (V072, 2026-08-27) + 5 de serviço '
  '(V093, 2026-08-29, junto com a Ordem de Serviço). Global, sem RLS.';
