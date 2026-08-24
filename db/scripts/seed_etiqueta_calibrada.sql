-- ---------------------------------------------------------------------------------------------
-- Modelo de etiqueta JÁ CALIBRADO na impressora física (2026-08-24).
--
-- POR QUE ISTO EXISTE
-- Este layout não é um dado de exemplo: é o resultado de duas sessões de calibragem contra uma
-- Argox OS-2140 real, com etiqueta impressa a cada rodada. Descobrir que o limite de largura era
-- o papel do driver (e não o cabeçote), que o código de barras precisa de zona de silêncio, que
-- cada fileira tem de ser uma página e que o quadro de corte não pode ser `border` — nada disso
-- se recupera digitando números de novo. O banco de dev, por outro lado, é recriado com
-- frequência (às vezes duas vezes no mesmo dia).
--
-- Enquanto o Nainer está em homologação/construção, este arquivo é o que faz a calibragem
-- sobreviver a `docker volume rm niner_pgdata`. Ele está no git — o banco não.
--
-- COMO USAR (depois de recriar o banco e criar o tenant pelo signup)
--   docker exec -i niner-db psql -U niner_owner -d niner_db < db/scripts/seed_etiqueta_calibrada.sql
--
-- ⚠️ NÃO vire migration. Migration é schema, roda em todo banco (inclusive produção) e não pode
-- ser editada depois de aplicada — e isto aqui é dado de UM tenant, que vai mudar enquanto a
-- calibragem evoluir. Script avulso pode ser reescrito à vontade; `V0xx__*.sql` não.
--
-- ⚠️ RLS: as tabelas têm FORCE ROW LEVEL SECURITY, e com ele NEM O DONO escapa da política. Sem
-- o `set_config('app.id_tenant', ...)` abaixo, o INSERT falha (id_tenant NOT NULL) e — pior — o
-- SELECT devolveria zero linhas em silêncio. Ver docs/infra/isolamento-tenant-rls.md.
--
-- IDEMPOTENTE: rodar de novo atualiza o que existe em vez de duplicar.
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    -- Ajuste estes dois se o slug do tenant de dev mudar ou se quiser outro nome de modelo.
    v_slug   text := 'loja-dev-claudio';
    v_nome   text := '3 COLUNAS - 34 X 31,7';
    v_tenant smallint;
    v_config integer;
BEGIN
    SELECT id_tenant INTO v_tenant FROM plataforma.tenant WHERE slug = v_slug;
    IF v_tenant IS NULL THEN
        RAISE EXCEPTION 'Tenant "%" não existe. Crie a conta pelo signup antes de rodar este script.', v_slug;
    END IF;

    -- Contexto de tenant: sem isto o RLS barra a escrita e esconde a leitura (ver cabeçalho).
    PERFORM set_config('app.id_tenant', v_tenant::text, true);

    -- ---------------------------------------------------------------------------------------
    -- Cabeçalho — a GEOMETRIA DO ROLO, medida com régua no rolo físico.
    -- Os três espaçamentos são espaço EM BRANCO; a posição de cada coluna é derivada (V057).
    -- ⚠️ O "espaço entre fileiras" (2,20) é o que mais dói errado: o erro dele ACUMULA, e a
    -- quarta etiqueta cai inteira fora do adesivo.
    -- ---------------------------------------------------------------------------------------
    INSERT INTO cfg_etiqueta_config (
        id_tenant, nome, largura_rolo_mm, numero_colunas, largura_etiqueta_mm, altura_etiqueta_mm,
        margem_esquerda_mm, espacamento_horizontal_mm, espacamento_vertical_mm, ativo)
    VALUES (v_tenant, v_nome, 110.00, 3, 34.00, 31.70, 3.00, 2.50, 2.20, true)
    ON CONFLICT (id_tenant, nome) DO UPDATE SET
        largura_rolo_mm           = EXCLUDED.largura_rolo_mm,
        numero_colunas            = EXCLUDED.numero_colunas,
        largura_etiqueta_mm       = EXCLUDED.largura_etiqueta_mm,
        altura_etiqueta_mm        = EXCLUDED.altura_etiqueta_mm,
        margem_esquerda_mm        = EXCLUDED.margem_esquerda_mm,
        espacamento_horizontal_mm = EXCLUDED.espacamento_horizontal_mm,
        espacamento_vertical_mm   = EXCLUDED.espacamento_vertical_mm,
        ativo                     = EXCLUDED.ativo,
        atualizado_em             = now()
    RETURNING id_config_etiqueta INTO v_config;

    -- Apaga e regrava os campos: a lista é pequena e assim o script é a verdade inteira sobre o
    -- layout — sem sobra de um campo que existia numa versão anterior.
    DELETE FROM cfg_etiqueta_campo
     WHERE id_tenant = v_tenant AND id_config_etiqueta = v_config;

    -- ---------------------------------------------------------------------------------------
    -- Campos posicionados. ⚠️ SKU_BARRAS é o delicado: 30 mm de largura dão módulo de 0,2655 mm
    -- (2,12 dots a 203 dpi) e ele termina em 31,5 mm de uma etiqueta de 31,70 — sobra 0,2 mm.
    -- Foi essa folga que o `border` do quadro de corte consumia, jogando os dígitos legíveis
    -- para fora do adesivo. Ver docs/telas/configuracao-etiqueta.md.
    -- ---------------------------------------------------------------------------------------
    INSERT INTO cfg_etiqueta_campo (
        id_tenant, id_config_etiqueta, campo, posicao_x_mm, posicao_y_mm, largura_mm, altura_mm,
        fonte, tamanho_fonte_pt, negrito, fundo_preto, alinhamento, exibir_texto_legivel)
    VALUES
        (v_tenant, v_config, 'NOME_EMPRESA',       0.50,  0.50, 32.00,  4.00, 'ARIAL', 6.00, true,  true,  'CENTRO',  NULL),
        (v_tenant, v_config, 'DESCRICAO_PRODUTO',  0.50,  5.50, 32.00,  8.00, 'ARIAL', 7.00, false, false, 'CENTRO',  NULL),
        (v_tenant, v_config, 'PRECO_VENDA',       13.00, 13.50, 18.00,  5.00, 'ARIAL', 8.00, true,  false, 'DIREITA', NULL),
        (v_tenant, v_config, 'SKU_BARRAS',         2.00, 17.50, 30.00, 14.00, 'ARIAL', 8.00, false, false, 'CENTRO',  true);

    RAISE NOTICE 'Modelo "%" gravado no tenant % (config %), com % campos.',
        v_nome, v_tenant, v_config,
        (SELECT count(*) FROM cfg_etiqueta_campo WHERE id_tenant = v_tenant AND id_config_etiqueta = v_config);
END $$;

-- Conferência no banco — script de restauração que ninguém conferiu não foi verificado, foi
-- torcido (mesma lição do backup que saía com zero linha de cliente).
SELECT set_config('app.id_tenant', (SELECT id_tenant::text FROM plataforma.tenant WHERE slug = 'loja-dev-claudio'), false);
SELECT c.nome, c.largura_rolo_mm, c.numero_colunas, c.largura_etiqueta_mm, c.altura_etiqueta_mm,
       c.margem_esquerda_mm, c.espacamento_horizontal_mm, c.espacamento_vertical_mm,
       count(k.*) AS campos
  FROM cfg_etiqueta_config c
  LEFT JOIN cfg_etiqueta_campo k
         ON k.id_tenant = c.id_tenant AND k.id_config_etiqueta = c.id_config_etiqueta
 WHERE c.id_tenant = plataforma.tenant_atual()
 GROUP BY 1,2,3,4,5,6,7,8;
