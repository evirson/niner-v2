-- V108 — Termos populares de busca na Lista Nacional de Serviços (LC 116).
--
-- ⭐ POR QUE: a busca do cadastro de serviço procurava só no texto OFICIAL da lei, e a lei não usa
-- as palavras do lojista. Medido em 2026-09-01, contra a API rodando:
--
--     "tosa"          -> 0 resultados   (e "tosa" é o EXEMPLO no placeholder do campo!)
--     "banho e tosa"  -> 0 resultados
--     "cabelo"        -> 0 resultados   ("cabeleireiros" não contém "cabelo")
--     "manicure"      -> 0 resultados   (na lei é "manicuros")
--     "banho"         -> 1, e o CÓDIGO ERRADO para petshop (060301, banhos/sauna de gente)
--
-- O petshop que vende banho e tosa não tinha como achar o 050801, cujo texto oficial é
-- "Guarda, tratamento, amestramento, EMBELEZAMENTO, alojamento e congêneres" — a palavra "tosa"
-- não aparece em lugar nenhum da lista. O resultado, medido neste banco: **os 5 serviços do
-- tenant 1 ficaram sem código**, e o defeito só apareceu no balcão, com a OS faturada e a nota
-- sem sair.
--
-- ⚠️ ISTO É VOCABULÁRIO DE BUSCA, NÃO ENQUADRAMENTO FISCAL. Os termos ajudam a ENCONTRAR o item
-- da lista; quem decide o enquadramento é o contador, e a tela continua dizendo isso. Mesma
-- natureza (e mesma ressalva) da curadoria ramo->código da V099.
--
-- ⚠️ É DADO, não código: acrescentar um termo é UPDATE, sem deploy. Mesmo raciocínio do
-- `NavItem.sinonimos` do menu ("kardex", "inventário") e do prefixo do gerador de EAN.
--
-- Tabela GLOBAL (sem id_tenant, sem RLS — é a lista da União, igual para todos), então esta
-- migration não precisa de NO FORCE ROW LEVEL SECURITY.

ALTER TABLE cfg_servico_lc116
    ADD COLUMN IF NOT EXISTS termos_busca text;

COMMENT ON COLUMN cfg_servico_lc116.termos_busca IS
    'Palavras que o lojista usa e a lei não usa (tosa, manicure, cabelo). Entram no LIKE da busca '
    'junto com a descrição oficial. Vocabulário de busca — NÃO é enquadramento fiscal.';

-- ---------------------------------------------------------------- petshop / veterinária
UPDATE cfg_servico_lc116 SET termos_busca =
    'tosa banho e tosa banho em cachorro pet petshop pet shop adestramento amestramento '
    || 'hotel para animais creche para caes embelezamento animal higiene animal tosador'
 WHERE codigo = '050801';

UPDATE cfg_servico_lc116 SET termos_busca =
    'veterinario veterinaria consulta veterinaria consulta de animal'
 WHERE codigo = '050101';

UPDATE cfg_servico_lc116 SET termos_busca = 'clinica veterinaria pronto socorro animal ambulatorio animal'
 WHERE codigo = '050202';

UPDATE cfg_servico_lc116 SET termos_busca = 'hospital veterinario internacao animal'
 WHERE codigo = '050201';

UPDATE cfg_servico_lc116 SET termos_busca = 'exame veterinario laboratorio animal analise clinica animal'
 WHERE codigo = '050301';

-- ---------------------------------------------------------------- salão / estética
UPDATE cfg_servico_lc116 SET termos_busca =
    'cabelo corte de cabelo cabeleireiro barbeiro barba manicure pedicure unha esmaltacao '
    || 'escova penteado coloracao tintura salao de beleza'
 WHERE codigo = '060101';

UPDATE cfg_servico_lc116 SET termos_busca =
    'depilacao estetica limpeza de pele sobrancelha design de sobrancelha esteticista'
 WHERE codigo = '060201';

UPDATE cfg_servico_lc116 SET termos_busca = 'massagem massoterapia sauna spa banho de ofuro drenagem'
 WHERE codigo = '060301';

UPDATE cfg_servico_lc116 SET termos_busca = 'spa emagrecimento centro de emagrecimento'
 WHERE codigo = '060501';

-- ---------------------------------------------------------------- oficina / assistência técnica
UPDATE cfg_servico_lc116 SET termos_busca =
    'conserto manutencao revisao reparo oficina mecanica mecanico troca de oleo lubrificacao '
    || 'higienizacao de ar condicionado conserto de bicicleta'
 WHERE codigo = '140101';

UPDATE cfg_servico_lc116 SET termos_busca =
    'assistencia tecnica conserto de celular conserto de eletronico conserto de eletrodomestico '
    || 'troca de tela'
 WHERE codigo = '140201';

UPDATE cfg_servico_lc116 SET termos_busca =
    'lavagem lavanderia costura ajuste de roupa conserto de roupa tingimento pintura polimento '
    || 'reforma de estofado conserto de sapato sapateiro'
 WHERE codigo = '140501';

UPDATE cfg_servico_lc116 SET termos_busca = 'instalacao montagem montagem de moveis montador'
 WHERE codigo = '140601';
