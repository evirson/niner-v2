-- V090 — conserta o backfill da V089, que saiu VAZIO em silêncio.
--
-- ⛔ O QUE DEU ERRADO, E POR QUE O FLYWAY DISSE "SUCESSO"
--
-- A V089 fez `ALTER TABLE venda NO FORCE ROW LEVEL SECURITY` antes do UPDATE, sabendo que
-- migration que lê dado de tenant sai vazia sem isso (V024/`FORCE RLS` vale até para o dono).
-- Mas o `NO FORCE` foi aplicado só na tabela ESCRITA. O UPDATE **lê** de
-- `produto_movimento_mestre` e `produto_movimento_detalhe`, que continuaram com `FORCE` — a
-- subconsulta devolveu zero linhas, o UPDATE casou zero linhas, e o Flyway anunciou sucesso,
-- porque não tem como saber que zero era o número errado.
--
-- Medido depois de aplicar: **347 vendas, 0 com vendedor**. Só apareceu porque o resultado foi
-- conferido no banco — `SELECT count(*), count(id_funcionario)`. Migration de backfill que
-- ninguém conferiu não foi verificada, foi torcida.
--
-- ⭐ A REGRA, agora completa: o `NO FORCE` vale por TABELA e precisa cobrir **tudo o que a
-- migration toca — lê e escreve**, não só o alvo do UPDATE.
--
-- ⚠️ Esta migration é nova em vez de uma correção da V089 porque migration já aplicada **nunca**
-- se edita: o Flyway recusa subir inteiro com `checksum mismatch`, e o sintoma não aparece na
-- máquina de quem editou (banco recriado do zero roda o arquivo novo e fica coerente). Foi assim
-- que V017 e V035 travaram a publicação em 2026-08-19.
--
-- ⚠️ E é IDEMPOTENTE (`WHERE v.id_funcionario IS NULL`): num banco onde a V089 tivesse funcionado,
-- ela não faz nada; num banco novo, a V089 roda vazia e esta preenche. Os dois mundos convergem.

ALTER TABLE venda                     NO FORCE ROW LEVEL SECURITY;
ALTER TABLE produto_movimento_mestre  NO FORCE ROW LEVEL SECURITY;
ALTER TABLE produto_movimento_detalhe NO FORCE ROW LEVEL SECURITY;

UPDATE venda v
   SET id_funcionario = sub.id_funcionario
  FROM (SELECT pmm.id_venda, pmm.id_tenant, MIN(pmd.id_funcionario) AS id_funcionario
          FROM produto_movimento_mestre pmm
          JOIN produto_movimento_detalhe pmd
                 ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
         WHERE pmm.tipo_movimento = 'VENDA' AND pmd.id_funcionario IS NOT NULL
         GROUP BY pmm.id_venda, pmm.id_tenant) sub
 WHERE v.id_venda = sub.id_venda AND v.id_tenant = sub.id_tenant
   AND v.id_funcionario IS NULL;

ALTER TABLE venda                     FORCE ROW LEVEL SECURITY;
ALTER TABLE produto_movimento_mestre  FORCE ROW LEVEL SECURITY;
ALTER TABLE produto_movimento_detalhe FORCE ROW LEVEL SECURITY;
