-- V100 — o que o serviço precisa para virar NFS-e (bloco S5 de docs/MODULONFSE.md)
--
-- A V085 criou `produto_servico` e deixou escrito, de propósito, que os campos fiscais ficariam
-- para depois: *"os campos FISCAIS (código da LC 116, alíquota de ISS, retenção, local de
-- incidência) NÃO entram aqui ainda — são o bloco S5"*. É este arquivo.
--
-- Hoje o catálogo de serviço funciona e é **fiscalmente mudo**: um banho e tosa cadastrado não tem
-- como virar nota, porque não sabe dizer QUAL serviço é para a prefeitura.
--
-- ⭐ O QUE ESTE ARQUIVO NÃO CRIA, E É A DECISÃO MAIS IMPORTANTE DELE
--
-- Não existe coluna `local_incidencia`. O `docs/MODULOSERVICOS.md` §5.4 previa uma, preenchida
-- pelo lojista com default PRESTADOR, porque o estudo não conseguiu levantar as exceções do art. 3º
-- da LC 116 ("não vou inventá-las"). Depois disso a fonte apareceu: o anexo oficial da NFS-e traz a
-- regra de incidência **código a código**, e ela está carregada em `cfg_servico_lc116` desde a V099
-- (271 PRESTADOR, 47 PRESTACAO, 10 TOMADOR, 5 ESPECIAL, 1 sem incidência).
--
-- Ou seja: o local de incidência é **derivado** do código escolhido, com JOIN, e o lojista não
-- responde essa pergunta. Guardar uma cópia aqui criaria duas verdades que divergem no dia em que
-- só uma for atualizada — é o mesmo motivo pelo qual a V054 recusou reescrever regra de dinheiro em
-- plpgsql, e o mesmo defeito que a V088 corrigiu no percentual de comissão pelo lado inverso.
--
-- ⚠️ A diferença com a comissão da V088 é real e vale entender, para ninguém "consertar" isto
-- depois: percentual de comissão é CONGELADO na linha porque muda com o tempo e a venda de ontem
-- tem de continuar valendo o que valia. Local de incidência é **classificação legal do serviço**,
-- não preço: se a lei mudar, ela muda para todas as notas, e uma cópia velha estaria errada.
--
-- ⛔ F12: nada aqui muda o comportamento de quem não usa serviço. Todas as colunas são nullable ou
-- têm DEFAULT, e nenhuma tela existente passa a exigir campo novo.

ALTER TABLE produto_servico
  ADD COLUMN codigo_tributacao_nacional  char(6),
  ADD COLUMN codigo_tributacao_municipal text,
  ADD COLUMN aliquota_iss                numeric(5,2),
  ADD COLUMN iss_retido_padrao           boolean NOT NULL DEFAULT false;

-- FK para a tabela GLOBAL, no mesmo molde de `produto.codigo_ncm -> cfg_produto_ncm` (V017):
-- referência simples, sem `id_tenant`, porque a tabela referenciada não tem tenant nenhum.
ALTER TABLE produto_servico
  ADD CONSTRAINT produto_servico_ctribnac_fk
    FOREIGN KEY (codigo_tributacao_nacional) REFERENCES cfg_servico_lc116 (codigo);

-- ⚠️ TETO NA ALÍQUOTA, e ele é lei, não palpite: a LC 116/2003 art. 8º-A fixa o máximo do ISS em
-- 5%. O piso fica em 0 de propósito — imunidade, isenção municipal e ISS fixo (o caso clássico do
-- escritório de contabilidade) são 0 legítimos, e um CHECK em 2% barraria contribuinte correto.
-- Sem teto, a auditoria de 2026-08-27 já mostrou o que acontece: `tipo_carteira.perc_desconto`
-- era `numeric(5,2)` sem CHECK e 999,99% fechava venda de R$ 1.000 com R$ 91 (V083).
ALTER TABLE produto_servico
  ADD CONSTRAINT produto_servico_aliquota_iss_ck
    CHECK (aliquota_iss IS NULL OR (aliquota_iss >= 0 AND aliquota_iss <= 5)),
  ADD CONSTRAINT produto_servico_ctribmun_ck
    CHECK (codigo_tributacao_municipal IS NULL
           OR codigo_tributacao_municipal ~ '^[0-9]{1,3}$');

COMMENT ON COLUMN produto_servico.codigo_tributacao_nacional IS
  'cTribNac — o código deste serviço na Lista Nacional (cfg_servico_lc116, V099). É o que vai no '
  'XML da DPS e o que decide, por JOIN, ONDE o ISS é devido: não há coluna local_incidencia aqui, '
  'de propósito (ver o cabeçalho da V100). Nulo enquanto o lojista não escolheu — a Conformidade '
  'Fiscal aponta antes da emissão (F11), nunca no balcão.';

COMMENT ON COLUMN produto_servico.codigo_tributacao_municipal IS
  'cTribMun — os 3 dígitos que ALGUMAS prefeituras exigem além do código nacional. Opcional e '
  'quase sempre nulo. ⚠️ É texto e não inteiro porque o zero à esquerda é significativo ("007"), '
  'a mesma razão de cTribNac ser char(6).';

COMMENT ON COLUMN produto_servico.aliquota_iss IS
  'Alíquota do ISS deste serviço no município da empresa, em %. ⭐ Não precisa ser digitada de '
  'cabeça: o ADN responde por (município, código, competência) e a tela sugere — ver '
  'docs/MODULONFSE.md §2.4. Nulo = ainda não definida; a emissão é bloqueada antes (F11).';

COMMENT ON COLUMN produto_servico.iss_retido_padrao IS
  'Sugestão de retenção na fonte pelo tomador (tpRetISSQN=2 na DPS). Falso por padrão porque no '
  'balcão do ICP deste produto — petshop, salão, oficina atendendo pessoa física — não há '
  'retenção. É sugestão do cadastro; quem decide por nota é a emissão.';
