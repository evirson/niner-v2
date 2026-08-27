-- V072 — Ramo de atividade da empresa (2026-08-27)
--
-- PARA QUE SERVE. Na contratação, o sistema precisa saber o ramo da empresa que está entrando
-- para poder perguntar a coisa certa: o dono do produto descreveu o caso do lojista que já tem
-- 3 empresas de calçados num tenant e vai contratar uma quarta empresa de outro ramo — nessa
-- hora o sistema deve perguntar se ela fica no mesmo tenant ou num novo, e explicar o impacto
-- de cada escolha. Sem o ramo gravado não há como nem detectar que a pergunta é necessária.
--
-- POR QUE NÃO USAR O CNAE DIRETO. O CNAE é a classificação oficial e é o que vem no CNPJ, mas
-- tem 1.332 subclasses — ninguém escolhe "4782-2/01" numa tela de contratação. A lista abaixo é
-- curta (28 itens, definida com o dono do produto em 2026-08-27), e o CNAE fica POR TRÁS: quando
-- o CNPJ é consultado, o CNAE sugere o ramo e o usuário confirma ou troca. ⭐ A decisão dele foi
-- literal: "dar a sugestão, mas o usuário define".
--
-- ⚠️ QUATRO RAMOS NÃO TÊM CNAE, E ISSO É FATO DA FONTE, NÃO ESQUECIMENTO: "artigos para festas
-- e descartáveis", "artigos religiosos" e "produtos naturais e suplementos" não existem como
-- subclasse — essas lojas se registram em códigos genéricos (4789-0/99, 4729-6/99), que também
-- servem a dezenas de outras atividades. Sugerir um ramo a partir de um código genérico seria
-- chutar com cara de certeza, então esses códigos ficam FORA do mapa: sem palpite, o usuário
-- escolhe. "Outros" é o fallback manual e nunca é sugerido.
--
-- A LISTA DE CNAEs NÃO FOI DIGITADA DE MEMÓRIA. Foi montada a partir da tabela oficial do IBGE
-- (servicodados.ibge.gov.br/api/v2/cnae/subclasses, 1.332 subclasses) e cada código foi
-- conferido contra ela por script antes de virar SQL — mesma regra que valeu para o NCM da
-- Receita e para as 27 UFs: dado oficial se carrega da fonte, não se lembra.
--
-- DE QUEM É O RAMO. Da EMPRESA — é o CNPJ que tem atividade econômica. O ramo do TENANT é
-- derivado (o conjunto dos ramos das empresas dele); guardá-lo também no tenant criaria duas
-- versões da mesma verdade, que divergem no dia em que só uma for atualizada.

-- Tabelas GLOBAIS (sem id_tenant/RLS, P9) — mesma exceção documentada de cfg_produto_ncm: são
-- tabelas de referência iguais para todos os tenants, e o ramo precisa ser legível também no
-- catálogo, que fica fora das células (docs/infra/parque-de-celulas.md).
CREATE TABLE cfg_ramo_atividade (
  id_ramo smallint  PRIMARY KEY,          -- fixo no seed (não IDENTITY): o mesmo id em toda célula
  codigo  text      NOT NULL UNIQUE,      -- estável, é o que o código Java referencia
  nome    text      NOT NULL,             -- o que aparece na tela, do jeito que ele escreveu
  ordem   smallint  NOT NULL              -- ordem de exibição (alfabética, "Outros" por último)
);

COMMENT ON TABLE cfg_ramo_atividade IS
  'Ramos de atividade do varejo atendido pelo Nainer (28, definidos em 2026-08-27). Global, sem RLS.';

-- Subclasse CNAE (7 dígitos, sem máscara) → ramo. Um CNAE aponta para no máximo um ramo, então
-- a sugestão é determinística; um ramo pode ter vários CNAEs. Código genérico não entra aqui.
CREATE TABLE cfg_ramo_cnae (
  cnae    text     PRIMARY KEY,
  id_ramo smallint NOT NULL REFERENCES cfg_ramo_atividade (id_ramo),
  CONSTRAINT cfg_ramo_cnae_formato_ck CHECK (cnae ~ '^[0-9]{7}$')
);

CREATE INDEX cfg_ramo_cnae_ramo_ix ON cfg_ramo_cnae (id_ramo);

GRANT SELECT ON cfg_ramo_atividade, cfg_ramo_cnae TO niner_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON cfg_ramo_atividade, cfg_ramo_cnae TO niner_owner;

INSERT INTO cfg_ramo_atividade (id_ramo, codigo, nome, ordem) VALUES
  (1, 'ACOUGUE_PEIXARIA', 'Açougue e peixaria', 1),
  (2, 'AGROPECUARIA', 'Agropecuária e insumos agrícolas', 2),
  (3, 'ARMARINHO_TECIDOS', 'Armarinhos, aviamentos e tecidos', 3),
  (4, 'ARTIGOS_ESPORTIVOS', 'Artigos esportivos', 4),
  (5, 'ARTIGOS_FESTA', 'Artigos para festas e descartáveis', 5),
  (6, 'ARTIGOS_RELIGIOSOS', 'Artigos religiosos', 6),
  (7, 'AUTOPECAS', 'Autopeças', 7),
  (8, 'BEBIDAS', 'Bebidas e adega', 8),
  (9, 'BRINQUEDOS', 'Brinquedos', 9),
  (10, 'CALCADOS', 'Calçados', 10),
  (11, 'CONFECCAO', 'Confecção e moda', 11),
  (12, 'ELETRO_INFORMATICA', 'Eletrônicos e informática', 12),
  (13, 'FARMACIA', 'Farmácia', 13),
  (14, 'FLORICULTURA', 'Floricultura e paisagismo', 14),
  (15, 'INSTR_MUSICAIS', 'Instrumentos musicais', 15),
  (16, 'JOIAS_BIJUTERIAS', 'Joias e bijuterias', 16),
  (17, 'LIVRARIA', 'Livraria', 17),
  (18, 'MAT_CONSTRUCAO', 'Materiais de construção', 18),
  (19, 'MERCEARIA', 'Mercearia e mercado', 19),
  (20, 'MOVEIS_DECORACAO', 'Móveis e decoração', 20),
  (21, 'OTICA', 'Ótica', 21),
  (22, 'PADARIA', 'Padaria e confeitaria', 22),
  (23, 'PAPELARIA', 'Papelaria', 23),
  (24, 'PERFUMARIA', 'Perfumaria e cosméticos', 24),
  (25, 'PET_SHOP', 'Pet shop', 25),
  (26, 'NATURAIS_SUPLEMENTOS', 'Produtos naturais e suplementos', 26),
  (27, 'UTILIDADES_BAZAR', 'Utilidades e bazar', 27),
  (28, 'OUTROS', 'Outros', 28);

INSERT INTO cfg_ramo_cnae (cnae, id_ramo) VALUES
  ('4722901', 1),  -- COMÉRCIO VAREJISTA DE CARNES - AÇOUGUES
  ('4722902', 1),  -- PEIXARIA
  ('4683400', 2),  -- COMÉRCIO ATACADISTA DE DEFENSIVOS AGRÍCOLAS, ADUBOS, FERTILIZANTES E
  ('4692300', 2),  -- COMÉRCIO ATACADISTA DE MERCADORIAS EM GERAL, COM PREDOMINÂNCIA DE IN
  ('4623106', 2),  -- COMÉRCIO ATACADISTA DE SEMENTES, FLORES, PLANTAS E GRAMAS
  ('4755501', 3),  -- COMÉRCIO VAREJISTA DE TECIDOS
  ('4755502', 3),  -- COMERCIO VAREJISTA DE ARTIGOS DE ARMARINHO
  ('4755503', 3),  -- COMERCIO VAREJISTA DE ARTIGOS DE CAMA, MESA E BANHO
  ('4763602', 4),  -- COMÉRCIO VAREJISTA DE ARTIGOS ESPORTIVOS
  ('4763603', 4),  -- COMÉRCIO VAREJISTA DE BICICLETAS E TRICICLOS; PEÇAS E ACESSÓRIOS
  ('4763604', 4),  -- COMÉRCIO VAREJISTA DE ARTIGOS DE CAÇA, PESCA E CAMPING
  ('4530703', 7),  -- COMÉRCIO A VAREJO DE PEÇAS E ACESSÓRIOS NOVOS PARA VEÍCULOS AUTOMOTO
  ('4530704', 7),  -- COMÉRCIO A VAREJO DE PEÇAS E ACESSÓRIOS USADOS PARA VEÍCULOS AUTOMOT
  ('4530705', 7),  -- COMÉRCIO A VAREJO DE PNEUMÁTICOS E CÂMARAS DE AR
  ('4541206', 7),  -- COMÉRCIO A VAREJO DE PEÇAS E ACESSÓRIOS NOVOS PARA MOTOCICLETAS E MO
  ('4541207', 7),  -- COMÉRCIO A VAREJO DE PEÇAS E ACESSÓRIOS USADOS PARA MOTOCICLETAS E M
  ('4723700', 8),  -- COMÉRCIO VAREJISTA DE BEBIDAS
  ('4763601', 9),  -- COMÉRCIO VAREJISTA DE BRINQUEDOS E ARTIGOS RECREATIVOS
  ('4782201', 10),  -- COMÉRCIO VAREJISTA DE CALÇADOS
  ('4782202', 10),  -- COMÉRCIO VAREJISTA DE ARTIGOS DE VIAGEM
  ('4781400', 11),  -- COMÉRCIO VAREJISTA DE ARTIGOS DO VESTUÁRIO E ACESSÓRIOS
  ('4751201', 12),  -- COMÉRCIO VAREJISTA ESPECIALIZADO DE EQUIPAMENTOS E SUPRIMENTOS DE IN
  ('4752100', 12),  -- COMÉRCIO VAREJISTA ESPECIALIZADO DE EQUIPAMENTOS DE TELEFONIA E COMU
  ('4753900', 12),  -- COMÉRCIO VAREJISTA ESPECIALIZADO DE ELETRODOMÉSTICOS E EQUIPAMENTOS 
  ('4757100', 12),  -- COMÉRCIO VAREJISTA ESPECIALIZADO DE PEÇAS E ACESSÓRIOS PARA APARELHO
  ('4771701', 13),  -- COMÉRCIO VAREJISTA DE PRODUTOS FARMACÊUTICOS, SEM MANIPULAÇÃO DE FÓR
  ('4771702', 13),  -- COMÉRCIO VAREJISTA DE PRODUTOS FARMACÊUTICOS, COM MANIPULAÇÃO DE FÓR
  ('4771703', 13),  -- COMÉRCIO VAREJISTA DE PRODUTOS FARMACÊUTICOS HOMEOPÁTICOS
  ('4789002', 14),  -- COMÉRCIO VAREJISTA DE PLANTAS E FLORES NATURAIS
  ('4756300', 15),  -- COMÉRCIO VAREJISTA ESPECIALIZADO DE INSTRUMENTOS MUSICAIS E ACESSÓRI
  ('4783101', 16),  -- COMÉRCIO VAREJISTA DE ARTIGOS DE JOALHERIA
  ('4783102', 16),  -- COMÉRCIO VAREJISTA DE ARTIGOS DE RELOJOARIA
  ('4789001', 16),  -- COMÉRCIO VAREJISTA DE SUVENIRES, BIJUTERIAS E ARTESANATOS
  ('4761001', 17),  -- COMÉRCIO VAREJISTA DE LIVROS
  ('4761002', 17),  -- COMÉRCIO VAREJISTA DE JORNAIS E REVISTAS
  ('4762800', 17),  -- COMÉRCIO VAREJISTA DE DISCOS, CDS, DVDS E FITAS
  ('4741500', 18),  -- COMÉRCIO VAREJISTA DE TINTAS E MATERIAIS PARA PINTURA
  ('4742300', 18),  -- COMÉRCIO VAREJISTA DE MATERIAL ELÉTRICO
  ('4743100', 18),  -- COMÉRCIO VAREJISTA DE VIDROS
  ('4744001', 18),  -- COMÉRCIO VAREJISTA DE FERRAGENS E FERRAMENTAS
  ('4744002', 18),  -- COMÉRCIO VAREJISTA DE MADEIRA E ARTEFATOS
  ('4744003', 18),  -- COMÉRCIO VAREJISTA DE MATERIAIS HIDRÁULICOS
  ('4744004', 18),  -- COMÉRCIO VAREJISTA DE CAL, AREIA, PEDRA BRITADA, TIJOLOS E TELHAS
  ('4744005', 18),  -- COMÉRCIO VAREJISTA DE MATERIAIS DE CONSTRUÇÃO NÃO ESPECIFICADOS ANTE
  ('4744006', 18),  -- COMÉRCIO VAREJISTA DE PEDRAS PARA REVESTIMENTO
  ('4744099', 18),  -- COMÉRCIO VAREJISTA DE MATERIAIS DE CONSTRUÇÃO EM GERAL
  ('4711301', 19),  -- COMÉRCIO VAREJISTA DE MERCADORIAS EM GERAL, COM PREDOMINÂNCIA DE PRO
  ('4711302', 19),  -- COMÉRCIO VAREJISTA DE MERCADORIAS EM GERAL, COM PREDOMINÂNCIA DE PRO
  ('4712100', 19),  -- COMÉRCIO VAREJISTA DE MERCADORIAS EM GERAL, COM PREDOMINÂNCIA DE PRO
  ('4724500', 19),  -- COMÉRCIO VAREJISTA DE HORTIFRUTIGRANJEIROS
  ('4754701', 20),  -- COMÉRCIO VAREJISTA DE MÓVEIS
  ('4754702', 20),  -- COMÉRCIO VAREJISTA DE ARTIGOS DE COLCHOARIA
  ('4754703', 20),  -- COMÉRCIO VAREJISTA DE ARTIGOS DE ILUMINAÇÃO
  ('4759801', 20),  -- COMÉRCIO VAREJISTA DE ARTIGOS DE TAPEÇARIA, CORTINAS E PERSIANAS
  ('4774100', 21),  -- COMÉRCIO VAREJISTA DE ARTIGOS DE ÓPTICA
  ('4721102', 22),  -- PADARIA E CONFEITARIA COM PREDOMINÂNCIA DE REVENDA
  ('4721103', 22),  -- COMÉRCIO VAREJISTA DE LATICÍNIOS E FRIOS
  ('4721104', 22),  -- COMÉRCIO VAREJISTA DE DOCES, BALAS, BOMBONS E SEMELHANTES
  ('4761003', 23),  -- COMÉRCIO VAREJISTA DE ARTIGOS DE PAPELARIA
  ('4772500', 24),  -- COMÉRCIO VAREJISTA DE COSMÉTICOS, PRODUTOS DE PERFUMARIA E DE HIGIEN
  ('4789004', 25),  -- COMÉRCIO VAREJISTA DE ANIMAIS VIVOS E DE ARTIGOS E ALIMENTOS PARA AN
  ('4771704', 25),  -- COMÉRCIO VAREJISTA DE MEDICAMENTOS VETERINÁRIOS
  ('4713002', 27),  -- LOJAS DE VARIEDADES, EXCETO LOJAS DE DEPARTAMENTOS OU MAGAZINES
  ('4759899', 27),  -- COMÉRCIO VAREJISTA DE OUTROS ARTIGOS DE USO PESSOAL E DOMÉSTICO NÃO 
  ('4789005', 27);  -- COMÉRCIO VAREJISTA DE PRODUTOS SANEANTES DOMISSANITÁRIOS

-- Ramo da empresa. NULL = não informado — é o estado de toda empresa criada antes desta data, e
-- de qualquer uma cujo dono pule a pergunta. Nada no ERP depende dele para funcionar: ele serve
-- à decisão de tenant na contratação e, mais adiante, a corte de relatório.
ALTER TABLE empresa ADD COLUMN id_ramo smallint REFERENCES cfg_ramo_atividade (id_ramo);

-- ⚠️ GRANT POR COLUNA: niner_app não tem privilégio no nível da TABELA `empresa` — tem em cada
-- uma das 25 colunas, individualmente. Coluna nova, portanto, nasce INACESSÍVEL para a
-- aplicação, e o erro só aparece em runtime ("permission denied for table empresa"). Pior: a
-- suíte NÃO pega isso, porque o Testcontainers conecta como superusuário do container e não
-- como niner_app (pendência conhecida). Mesmo tropeço da V036 (Cancelamento de Entrada).
-- (a lista de colunas se repete por privilégio — `SELECT, INSERT, UPDATE (col)` é erro de sintaxe)
GRANT SELECT (id_ramo), INSERT (id_ramo), UPDATE (id_ramo) ON empresa TO niner_app;
