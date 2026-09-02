-- =====================================================================================
-- V110 — ST retido no cadastro do produto (pendência 23, `cStat 938`)
-- =====================================================================================
--
-- ⛔ O DEFEITO, medido contra a SEFAZ/PR e não suposto:
--
--     cStat 938 — "Nao informada vBCSTRet, pST e vICMSSTRet. [nItem:1]"
--     (documentos 63 e 76 deste banco, em 2026-08-24 e 2026-08-27)
--
-- Mercadoria com ICMS já retido por substituição tributária sai com **CSOSN 500** no Simples
-- Nacional. No modelo 55 a SEFAZ exige, junto do CSOSN, o que foi retido lá atrás — base, alíquota
-- e valor. O montador escrevia só `orig` + `CSOSN`: o XSD aceita (o bloco é `minOccurs="0"`), a
-- SEFAZ recusa. ⚠️ E `cfg_csosn.exige_st_retido` já marcava o 500 como exigente **desde a V035** —
-- o catálogo sabia e nenhum código lia a coluna.
--
-- ⭐ DE ONDE VEM O VALOR (decisão do dono do produto, 2026-09-02, depois de conferir com o
--    contador): **da entrada por XML daquele produto** — é o ICMS-ST que o fornecedor de fato
--    reteve, e está em `entrada_nfe_item.base_st_retido`/`icms_st_retido`. As colunas criadas aqui
--    são a **reserva**: valem quando o produto não tem nenhuma entrada por XML, caso em que só o
--    contador sabe o número.
--
-- ⚠️ POR UNIDADE, não por nota. A entrada traz o total do item (`icms_st_retido` de 12 unidades);
-- a venda pode ser de 1. Guardar por unidade é o que torna as duas fontes comparáveis e o rateio
-- trivial — e é como o contador pensa o valor ("quanto de ST tem numa peça").
--
-- ⚠️ `numeric(14,4)`, não `(12,2)`: é valor UNITÁRIO, e o ST de uma peça de baixo valor tem
-- centavos que se perdem em 2 casas — o erro só apareceria multiplicado por 12 na nota.
--
-- ⛔ NULO é diferente de ZERO aqui, e a distinção decide se a nota sai: nulo = "ninguém informou"
-- (a emissão recusa, F11, ANTES de reservar número); zero = "o contador disse que não há ST retido
-- neste produto" (a nota sai com 0,00, que é o que a SEFAZ espera de uma mercadoria sem retenção).
-- É a mesma decisão de `limiteCredito` no cliente: ausente é null, zero é valor legítimo.
--
-- ⛔ SEM BACKFILL: inventar o ST retido dos 621 produtos existentes gravaria como "informado pelo
-- contador" um número que ninguém mediu — e ele iria para uma nota fiscal. Produto sem entrada por
-- XML e sem estes campos simplesmente não emite NF-e 55, com a mensagem dizendo onde preencher.

ALTER TABLE produto
    ADD COLUMN st_retido_base_unitario   numeric(14,4),
    ADD COLUMN st_retido_valor_unitario  numeric(14,4),
    ADD COLUMN st_retido_aliquota        numeric(5,2);

COMMENT ON COLUMN produto.st_retido_base_unitario IS
    'vBCSTRet POR UNIDADE (CSOSN 500). Reserva do contador para quando o produto nao tem entrada '
    'por XML — a fonte preferida e `entrada_nfe_item.base_st_retido`. NULO = nao informado (a '
    'NF-e 55 recusa antes de reservar numero); ZERO = informado e nao ha retencao.';
COMMENT ON COLUMN produto.st_retido_valor_unitario IS
    'vICMSSTRet POR UNIDADE (CSOSN 500). Mesma regra de nulo x zero da coluna da base.';
COMMENT ON COLUMN produto.st_retido_aliquota IS
    'pST — aliquota suportada pelo consumidor final. Quando nula e ha base e valor, e derivada '
    '(valor / base x 100), que e o numero coerente com o que foi de fato retido.';

-- Os três andam juntos: ter valor sem base (ou o contrário) produziria um XML que a SEFAZ recusa
-- por incoerência — e o erro apareceria só na transmissão, com a nota já numerada.
ALTER TABLE produto
    ADD CONSTRAINT produto_st_retido_par_ck CHECK (
        (st_retido_base_unitario IS NULL AND st_retido_valor_unitario IS NULL)
        OR (st_retido_base_unitario IS NOT NULL AND st_retido_valor_unitario IS NOT NULL)
    ) NOT VALID;

-- ⚠️ `NOT VALID` de propósito: constraint que varre tabela existente derruba deploy (mesma decisão
-- da V049, `empresa.estado`). As linhas atuais têm as três colunas nulas, então passam de qualquer
-- forma — o NOT VALID protege o dia em que a tabela for grande.

ALTER TABLE produto
    ADD CONSTRAINT produto_st_retido_nao_negativo_ck CHECK (
        coalesce(st_retido_base_unitario, 0) >= 0
        AND coalesce(st_retido_valor_unitario, 0) >= 0
        AND coalesce(st_retido_aliquota, 0) BETWEEN 0 AND 100
    ) NOT VALID;
