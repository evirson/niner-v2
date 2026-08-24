-- ---------------------------------------------------------------------------------------------
-- CFOP interestadual na regra do perfil fiscal (2026-08-24).
--
-- POR QUÊ: o CFOP muda com o destino — 5xxx é operação DENTRO do estado, 6xxx é interestadual.
-- Até aqui a regra tinha um CFOP só, e uma venda do PR para um cliente de SP saía com 5405: a
-- SEFAZ recusou com **cStat 733**, "CFOP de operação interna e idDest difere de 1". Achado na
-- primeira transmissão real de uma NF-e 55 de venda.
--
-- POR QUE NÃO DERIVAR O DÍGITO: parece que bastaria trocar o 5 pelo 6 mantendo o sufixo, e para os
-- CFOPs comuns funciona (5102 → 6102). Mas NÃO é uma regra geral: `5405` (revenda de mercadoria
-- com ST, contribuinte substituído) não vira `6405` — esse CFOP **não existe** na tabela oficial;
-- o correspondente é `6404`. Um sistema que deriva às cegas emite nota com CFOP inválido ou, pior,
-- com um CFOP válido que descreve outra operação. A escolha é do contador, não do software.
--
-- POR QUE NÃO UMA REGRA POR UF: o modelo aceita `uf_destino` coringa ('*') ou uma UF específica,
-- mas não "todas as de fora do estado". Cobrir o país exigiria 26 regras por perfil.
--
-- COMO FICA: a mesma regra carrega os dois CFOPs, e quem escolhe é a UF do destinatário comparada
-- à do emitente. Decisão do dono do produto em 2026-08-24.
--
-- NULO é permitido de propósito: quem só vende dentro do estado não precisa preencher, e a
-- ausência vira erro explícito na emissão (F11) em vez de um CFOP chutado.
--
-- Só DDL — não lê nem transforma dado de tenant, então dispensa o `NO FORCE ROW LEVEL SECURITY`
-- que backfill exigiria (ver docs/infra/isolamento-tenant-rls.md).
-- ---------------------------------------------------------------------------------------------
ALTER TABLE cfg_perfil_fiscal_regra
    ADD COLUMN IF NOT EXISTS cfop_interestadual character(4);

COMMENT ON COLUMN cfg_perfil_fiscal_regra.cfop_interestadual IS
    'CFOP quando o destinatário é de OUTRA UF (6xxx). NULL = a regra só cobre operação interna, e '
    'a emissão interestadual é recusada com mensagem, nunca com CFOP derivado.';
