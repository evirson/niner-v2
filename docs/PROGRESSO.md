# Progresso do Projeto — niner-v2

Registro cronológico das decisões e entregas. Atualizar a cada marco relevante.
**Última atualização:** 2026-07-16

---

## Estado atual

Projeto **spec-driven** em fase de fundação. A **API (Spring Boot 4 / Java 25)** sobe com 3 superfícies + infra de contexto de tenant; o schema completo (control-plane + domínio do lojista, V001–V024) está criado, revisado e com RLS validado. Falta a camada de domínio na API (repos/serviços/endpoints de produto/estoque/pedido) e o app `admin/`.

| Artefato | Situação |
|---|---|
| `spec-driven-erp-varejo.md` | **v2.0 — pivô SaaS multi-tenant** (Constituição P1–P9 + PRD R1–R21 + plano técnico + control-plane + migrations) |
| `docs/PLANO-DE-NEGOCIO.md` | **Novo** — plano de negócio (planos/preços, trial, funil, métricas SaaS, roadmap, decisões D1–D10) |
| `docs/padroes/` | Mockup de referência de UI (golden file, §3.7) — `TELA.rar` descompactado e removido |
| `db/*.txt` | Schema **legado (Firebird)** versionado como referência (31 tabelas + generators, procedures, triggers) |
| `CLAUDE.md` | Guia do repositório — atualizado para o SaaS multi-tenant (P8/P9, plataforma, `id_tenant`+RLS) |
| `docker-compose.yml` | Infra local de dev: `db` (postgres:18, `niner_db`) + `flyway` (profile `migrate`) + **`api`** (Spring Boot, porta 8080, conecta como `niner_app`); **V001–V024 aplicadas e validadas em banco real** (control-plane + domínio do lojista + RLS) |
| `db/migration/V013–V024` | Domínio do lojista (identidade, cadastros, catálogo com `sku`+`ean`, estoque com `reservado`/`disponivel`, vendas, canais, pedidos, integração/outbox, cfg_geral) + **RLS de domínio** (`FORCE` + política por `id_tenant`). **Gate P8 verde** (teste de isolamento cross-tenant automatizado). `financeiro` NÃO entra (Q5/ADR-010). **Revisado em 2026-07-16** (ver linha do tempo): tipos padronizados (`id_tenant SMALLINT`, demais PKs `INTEGER`), sem `ON DELETE CASCADE`, ledger de estoque imutável via `REVOKE`, e-mail case-insensitive, fix de bootstrap (`GRANT CREATE ON SCHEMA public`) |
| `api/` | Spring Boot 4.0.7 / Java 25 (Maven). 3 superfícies com `SecurityFilterChain` separados; `TenantContext` (`ScopedValue`) + `TenantAwareTransactionManager`; **auth JWT HS256** (login/signup emitem, `/api/v1` valida `aud=tenant`); **trial self-service** (`POST /api/publico/assinar` → tenant+configs+ADMIN+assinatura TRIAL + token), `POST /api/publico/login`, `GET /api/v1/eu`. **11 testes verdes** (Testcontainers) + fluxo **verificado ao vivo** como `niner_app`. Persistência: **Spring Data JDBC**. Falta: domínio (repos/serviços/endpoints de produto/estoque/pedido) e os 3 fronts |
| `site/` | Site público (Astro/SSG, ADR-011). **Home institucional "matadora"** (posicionamento concorrente do Bling): hero com painel animado + demo de sincronização, faixa de stats com contadores, contraste problema→solução, 3 passos, 6 recursos, canais (ML/Shopee/Amazon/balcão), planos (preços via `/api/publico/planos`), FAQ e CTA — tudo em CSS/SVG puro com **scroll-reveal** e **prefers-reduced-motion** (sem novas deps). Sistema visual em `src/styles/site.css` portado do golden `nainer_institucional`. `/assinar` (form → `POST /api/publico/assinar` → auto-login → `/bem-vindo`) e `/bem-vindo` mantidos. **Trial 60 dias** em toda a copy. Tema claro/escuro persistido. **Build SSG ok**; hero/reveal/contadores verificados via Playwright |
| `web/` | **Novo — ERP do lojista (React 19 + Vite + TS)**. Auth JWT (login slug+email+senha; **handoff SSO** do site via `#token=`), shell (nav Painel/Produtos/Estoque/Pedidos/Canais + Sair), **Painel** real (`GET /api/v1/eu` via TanStack Query), placeholders "em construção". Design tokens §3.7 (claro/escuro). **Build ok**; fluxo **e2e verificado** (login + handoff). |
| `admin/` | Ainda não criado (backoffice React 19 + Vite) |

**Stack alvo:** Java 25 + Spring Boot 4.x · PostgreSQL 18 (Docker, banco **`niner_db`**) · React 19 + Vite (3 apps) · Flyway · JWT. **SaaS multi-tenant** (banco único + `id_tenant` + Postgres RLS).

---

## Linha do tempo

### 2026-07-16 — Revisão do schema de domínio: tipos, cascade, imutabilidade e bug de bootstrap

Auditoria linha a linha de `db/migration/V001–V024` + `db/bootstrap/00_roles.sql` (nenhum código de
domínio em `api/` ainda depende dessas tabelas, então deu para corrigir sem quebrar nada em produção).

1. **Bug bloqueante corrigido: `niner_owner` sem `CREATE` no schema `public`.**
   `ALTER DATABASE niner_db OWNER TO niner_owner` (bootstrap) muda o dono do **banco**, não do
   schema `public` pré-existente (dono é o superusuário de bootstrap da imagem). Como as
   migrations de domínio (V013+) criam tipos/tabelas sem prefixo de schema (→ `public`), o
   Flyway rodando como `niner_owner` via `docker compose run --rm flyway` falharia em V013 com
   `permission denied for schema public`. Não aparecia nos testes porque
   `api/src/test/resources/bootstrap-test.sql` roda o Flyway como o superusuário do container
   Testcontainers, não como `niner_owner` — o caminho real nunca tinha sido exercitado. Fix:
   `GRANT CREATE ON SCHEMA public TO niner_owner;` no bootstrap.
2. **Regressão revertida em V017 (catálogo).** Uma edição anterior tinha substituído a coluna
   `sku` de `produto_barra` por `ean_fabricante plataforma.sim_nao DEFAULT 'SIML'` — valor
   inválido para o ENUM (`'SIM'`/`'NÃO'`), o que quebraria a migration — e tornado `ean`
   obrigatório, revertendo a decisão **Q7** (sku interno obrigatório/único, ean opcional).
   Restaurado ao design da Q7.
3. **Tipos padronizados em todo o schema:** `id_tenant` sempre `SMALLINT` (raiz do isolamento,
   `plataforma.tenant.id_tenant`); demais PKs surrogate sempre `INTEGER`. Decisão consciente
   (revê a orientação anterior da spec de `BIGINT` genérico) — teto de 32.767 tenants, considerado
   suficiente para o público-alvo; revisitar se o funil comercial aproximar do limite.
4. **Sem `ON DELETE CASCADE` em nenhuma FK do domínio** — decisão do produto: apagar um registro
   com dependentes deve falhar por violação de FK, nunca apagar em cascata silenciosamente.
5. **Ledger de estoque imutável ao nível de banco.** `produto_movimento_mestre`/
   `produto_movimento_detalhe` (P3) ganharam `REVOKE UPDATE, DELETE ... FROM niner_app` em V024
   — mesmo tratamento que `plataforma.impersonacao_log` já tinha (V011). Correção de um
   lançamento é sempre um novo movimento compensatório.
6. **E-mail de login case-insensitive.** `usuario.email` (V015) passou de
   `UNIQUE(id_tenant, email)` para `UNIQUE INDEX (id_tenant, lower(email))`, igual ao padrão já
   usado em `plataforma.staff` (V010) — evita duas contas do mesmo tenant diferindo só por
   maiúscula/minúscula.
7. **`empresa.codigo_empresa`** (número sequencial por tenant, só para exibição em relatório)
   ganhou `UNIQUE(id_tenant, codigo_empresa)` + comentário. **`produto_movimento_mestre.
   id_transferencia`** documentado como proposital sem FK (vem de um gerador externo).
8. **Documentação sincronizada:** `db/migration/README.md` e `spec-driven-erp-varejo.md`
   (§3.1.1, §3.3.1–§3.3.6) atualizados para refletir os tipos/convenções reais e os marcadores
   🔴 já resolvidos pelas migrations V013–V024 (reserva/disponível, ledger, canal/anúncio/pedido,
   outbox/webhook).
9. **`cliente.data_nascimento` + `cliente.genero`.** Campos do legado (§3.3.9: `nascimento`,
   `genero` M/F/O) que tinham ficado de fora quando `V016` foi criado. Como o banco ainda está
   em fase de construção (nenhuma migration foi aplicada em ambiente real até agora), os campos
   entraram **direto na V016** em vez de uma migration nova: `data_nascimento DATE` +
   `genero genero_cliente` (ENUM `MASCULINO`/`FEMININO`/`OUTROS`, tipo definido em V013).
   Primeira versão os deixou `NOT NULL` incondicional; **ajustado no mesmo dia** para
   obrigatório **só em pessoa física** — colunas nullable + `CONSTRAINT
   cliente_dados_pessoais_ck CHECK (NOT fisica_juridica OR (data_nascimento IS NOT NULL AND
   genero IS NOT NULL))`, já que cliente pessoa jurídica não tem data de nascimento nem gênero.
   Convenção do projeto enquanto o banco não roda em ambiente real: só criar migration
   incremental (`V025+`) depois que V001–V024 forem de fato aplicadas num ambiente que importa
   preservar.
10. **`cfg_categoria_cliente` (V016) + `cliente.id_categoria_cliente` (`NOT NULL`).** Tabela do
    legado (§3.3.8) que também tinha ficado de fora — criada com o padrão já usado em
    `cfg_categoria_produto` (V017): `id_categoria_cliente integer GENERATED ALWAYS AS IDENTITY
    PK`, `id_tenant` (P8), `nome_categoria text NOT NULL`, `UNIQUE(id_tenant, nome_categoria)`.
    Entra **antes** de `cliente` no mesmo arquivo (ordem de FK). `cliente` ganhou
    `id_categoria_cliente integer NOT NULL REFERENCES cfg_categoria_cliente` + índice
    `(id_tenant, id_categoria_cliente)`; sem categoria padrão pré-cadastrada, então todo cliente
    precisa de uma categoria já criada. `cfg_categoria_cliente` entrou no array de RLS do V024.
11. **Galeria de imagens do produto.** `produto.imagem` (uma imagem só) removida; criada
    `produto_imagem` (V017) para várias imagens por produto: `id_produto_imagem integer PK`,
    `id_tenant` (P8), `id_produto integer NOT NULL REFERENCES produto` (sem cascade),
    `indice smallint NOT NULL` (ordem de exibição), `imagem text NOT NULL`,
    `UNIQUE(id_tenant, id_produto, indice)`. Entrou no array de RLS do V024.
12. **Seis ajustes pontuais pedidos pelo dono do produto** (V014/V016/V018/V019/V023) — só
    script + documentação, banco **não** recriado/testado nesta rodada (nova convenção: só
    recriar quando pedido explicitamente):
    - `funcionario` (V016): +`telefone`; `funcionario_cpf_uk` virou `UNIQUE(id_tenant,
      id_funcionario)` — como `id_funcionario` já é PK, isso não impõe nada além da PK; **CPF
      deixou de ser único por tenant**.
    - `venda` (V018): removido `id_funcionario` — vendedor/comissão por item ficam só em
      `produto_movimento_detalhe.id_funcionario`.
    - `cfg_geral` (V023): removido `moeda_devolucao`; adicionados `cfg_usa_variante_linha` e
      `cfg_usa_variante_coluna` (`boolean NOT NULL DEFAULT true`).
    - `empresa` (V014): novo campo `cfg_nome_etiqueta text NOT NULL` (texto/modelo da etiqueta
      de produto).
    - `produto_balanco` (V019): removidos `qtd_sistema` e `observacao`; `id_balanco` virou
      `bigint` — **exceção deliberada** à convenção "PKs surrogate são `integer`" (volume de
      contagens de inventário esperado maior que o das demais tabelas).
    - `produto_movimento_detalhe` (V019): removido `saldo_apos` — o ledger continua imutável
      (`REVOKE UPDATE, DELETE`, V024), mas deixa de gravar o saldo resultante por linha; esse
      saldo passa a existir só materializado em `produto_estoque`.
13. **Direção do controle de saldo de estoque em revisão.** O dono do produto avisou que a
    remoção de `saldo_apos` (item 12) é compatível com um plano futuro: recriar
    `SP_ATUALIZA_QUANTIDADE_ESTOQUE` como **stored procedure acionada por trigger** em
    `produto_movimento_mestre`/`produto_movimento_detalhe` — o **oposto** do que está escrito
    hoje em `V019` e na spec (§3.3.1/§3.3.4: "domínio Java, não trigger"). Detalhes ainda **não
    definidos** ("vamos definir mais pra frente"); marcado 🔴 em `V019__estoque.sql` e na spec
    para não ficar como decisão esquecida. Quando fechar, vira ADR e a migration do
    trigger/procedure.
14. **Redes sociais em `cliente`.** `whatsapp`, `instagram`, `facebook`, `tiktok` — `text`
    nullable, sem validação de formato no banco (V016). `instagram`/`facebook` já existiam no
    legado (§3.3.9) e tinham ficado de fora; `whatsapp`/`tiktok` são novos, sem equivalente
    legado. Bloco de sequência de migrations (§3.5.1 da spec) reescrito nesta rodada para bater
    com os números reais V013–V024 (estava com placeholder `V013+`/`V0xx` desde antes da
    implementação).
15. **Item 13 fechado: trigger de estoque implementada.** `trg_produto_movimento_detalhe_estoque`
    (função `fn_atualiza_estoque_movimento()`, PL/pgSQL, fim do `V019__estoque.sql`) mantém
    `produto_estoque.qtd_estoque` em `INSERT`/`UPDATE`/`DELETE` de `produto_movimento_detalhe`:
    `credito_debito='C'` soma, `'D'` subtrai; `UPDATE` desfaz o efeito antigo e aplica o novo
    (cobre troca de empresa/variação/tipo/quantidade); `DELETE` desfaz o efeito. Faz **UPSERT**
    em `produto_estoque` — cria a linha na hora se não existir para o `(id_tenant, id_empresa,
    id_variacao)`. Não existe mais `SP_ATUALIZA_QUANTIDADE_ESTOQUE` como objeto separado — a
    lógica inteira está na função de trigger.
    - **Reverte a regra de imutabilidade do item 12/13:** `produto_movimento_detalhe` saiu do
      `REVOKE UPDATE, DELETE` do `V024` (só `produto_movimento_mestre` continua imutável) —
      sem isso `niner_app` nunca conseguiria disparar as branches de `UPDATE`/`DELETE` da
      trigger (o `REVOKE` bloquearia o comando antes de chegar nela).
    - Escopo confirmado só em `qtd_estoque` — `reservado` continua fora (fluxo de reserva do
      pedido, Q2/ADR-004, não muda).
    - Roda como `niner_app` (SECURITY INVOKER, padrão): RLS de `produto_estoque` continua
      valendo dentro da trigger, sem risco de vazar saldo entre tenants.
    - Único trigger de banco do domínio até agora — todo o resto continua "sem trigger,
      auditoria/saldo no domínio Java" (decisão original, ainda válida para as outras tabelas).
16. **Achado real ao testar a trigger: FK simples não valida o tenant do registro referenciado
    (P8).** Testando isolamento entre tenants (não pedido, verificação por conta própria antes
    de dar a trigger por pronta), consegui — como tenant 2 — inserir em
    `produto_movimento_detalhe` uma linha com `id_tenant=2` mas `id_empresa`/`id_variacao`/
    `id_movimento` **do tenant 1**, e o INSERT passou. A trigger, obediente, criou uma linha em
    `produto_estoque` com `id_tenant=2` apontando pra `id_empresa`/`id_variacao` de outro
    tenant (`qtd_estoque` fabricado). Causa: FK simples (`REFERENCES tabela (id_x)`) só checa
    se o ID existe em algum lugar — **RLS não é aplicado na checagem de integridade
    referencial**. Isso não era bug da trigger; é uma lacuna em **toda** tabela de domínio com
    FK pra outra tabela de domínio (~34 constraints em ~17 tabelas: `usuario`, `cliente`,
    `funcionario`, `produto_categoria`, `produto_barra`, `produto_imagem`, `venda`,
    `venda_devolucao`, `produto_estoque`, `produto_movimento_mestre`,
    `produto_movimento_detalhe`, `produto_balanco`, `anuncio`, `pedido`, `pedido_item`,
    `webhook_recebido` — todo o schema de fato).
    - **Fix (2026-07-16, V014–V022):** toda tabela referenciada ganhou `UNIQUE (id_tenant,
      id_<pk>)` (ex.: `empresa_id_empresa_uk`), e toda FK entre tabelas de domínio virou
      **composta**: `FOREIGN KEY (id_tenant, id_x) REFERENCES tabela (id_tenant, id_x)`. FKs
      nullable (`venda.id_cliente`, `produto_barra.id_variante_linha` etc.) continuam
      funcionando — `MATCH SIMPLE` (padrão do Postgres) não checa a FK se qualquer coluna
      envolvida for `NULL`.
    - Convenção registrada no checklist de aprovação de spec (fim do documento) e em
      `db/migration/README.md`/`spec-driven-erp-varejo.md` §3.1.1: toda FK nova entre tabelas
      de domínio nasce composta, nunca simples.
17. **`cfg_plano_contas` antecipada (V016) — preparação para relatórios/DRE.** O dono do
    produto pediu a tabela de plano de contas (§3.3.7 do legado, que fazia parte do módulo
    `financeiro` deferido pra Fase 2 — Q5/ADR-010) + `fornecedor.id_plano_contas NOT NULL`.
    Só o plano de contas em si entrou — `moeda`, `caixa_*`, `contas_receber*`,
    `contas_pagar`, `conta_corrente*` continuam fora, sem migration (Fase 2). DDL ajustado
    ao padrão do projeto a partir do que foi passado:
    - PK vira **composta `(id_tenant, id_plano_contas)`** em vez de `id_plano_contas` sozinho
      — a chave de negócio (código contábil `text`, ex.: `"3.1.001"`) precisa do tenant pra
      não colidir entre lojistas; é a primeira tabela do domínio sem PK surrogate `integer`.
    - `VARCHAR(13)`/`VARCHAR(100)` → `text`; `VARCHAR(1) CHECK IN ('S','N')`
      (`inclui_dre`/`inclui_fluxo_caixa`) → `boolean` (regra de conversão §3.3.1).
    - `tipo_movimento VARCHAR(1) CHECK IN ('C','D','N')` → novo ENUM `tipo_movimento_conta`
      (V013) — distinto de `credito_debito` (ledger de estoque). Valores por extenso
      (`'CRÉDITO'`/`'DÉBITO'`/`'NEUTRO'`, ajustado logo em seguida no mesmo dia — ver item 18),
      não os códigos de uma letra do legado.
    - `fornecedor.id_plano_contas` entra como FK **composta** desde já (não simples e depois
      corrigida) — já nasce seguindo a convenção do item 16. Sem linha padrão pré-cadastrada
      em `cfg_plano_contas`, então todo fornecedor novo precisa de um plano de contas já
      criado.
    - Constraint/índice nomeados no padrão do projeto (`cfg_plano_contas_pk`,
      `..._id_tenant_ix`, `..._descricao_ix`), não no estilo Firebird (`PK_...`, `IX_...`) do
      DDL original; PK definida inline no `CREATE TABLE`, não via `ALTER TABLE` depois.
    - `cfg_plano_contas` entrou no array de RLS do V024.
18. **Ajuste do ENUM `tipo_movimento_conta` + mais 3 arquivos legados removidos.**
    `tipo_movimento_conta` (item 17) trocou de códigos de uma letra
    (`'C'`/`'D'`/`'N'`) para valores por extenso: `CREATE TYPE tipo_movimento_conta AS ENUM
    ('CRÉDITO', 'DÉBITO', 'NEUTRO')` (V013). Nada dependia do valor anterior (tabela sem
    seed, sem código em `api/`), troca segura. Removidos também `db/005_CFG_PLANO_CONTAS.txt`,
    `db/101_PROCEDURES.txt` e `db/102_TRIGGERS.txt` — dumps do legado Firebird (procedures/
    triggers, incluindo a antiga `SP_ATUALIZA_QUANTIDADE_ESTOQUE`) que não fazem mais falta
    como referência: a lógica de estoque já está na trigger real (`V019`, item 15) e o plano
    de contas já foi migrado (item 17).

### 2026-07-11 — Home institucional (concorrente do Bling) + trial 60 dias (ponta a ponta)

Reforço do topo do funil (§Fase 0, ADR-011) e revisão de D2.

1. **Trial 14 → 60 dias (D2 revisto).** Decisão do dono do produto para dar mais tempo de
   ativação/aha. Aplicado **ponta a ponta**: `application.yml` (`niner.trial.dias: 60`, prod e
   teste), comentário em `OnboardingController`, e todos os textos da spec (R12, §3.3.2, tabela de
   rotas, D2), do `PLANO-DE-NEGOCIO` (§6, intro, D2) e do site. `SignupService` já parametrizava
   via `NinerProperties.Trial.dias` — nenhuma lógica mudou; os 11 testes seguem válidos (nenhum
   depende do valor de dias).
2. **Home reconstruída (`site/index.astro`).** De landing "seca" para página longa e animada,
   posicionando o Niner como ERP multicanal moderno (sem citar o Bling nominalmente): hero com
   painel decorativo + **demo de sincronização estoque→canais**, faixa de prova com **contadores
   animados**, blocos problema→solução, "como funciona" em 3 passos com ilustrações SVG,
   6 recursos, pílulas de canais, planos (mantido o enhancement de preços da API), FAQ em
   `<details>` e CTA final. Toda CTA leva a `/assinar` ("60 dias grátis, sem cartão").
3. **Sistema visual + movimento.** Novo `site/src/styles/site.css` (portado do golden
   `docs/padroes/nainer_institucional`, rebrandeado Niner) e `Base.astro` com navbar sticky,
   toggle de tema persistido, menu mobile e um script de **scroll-reveal** (IntersectionObserver)
   + contadores, tudo desligado sob `prefers-reduced-motion`. Sem novas dependências (P6/ADR-011).
   `/assinar` adotou `form-card`/`field` e ganhou uma coluna de reforços; `/bem-vindo` alinhado.
4. **Verificação:** `npm run build` (SSG, 3 páginas) ok; Playwright confirmou hero animado,
   reveal disparando ao rolar (opacity 0→1) e contadores, em tema claro e escuro; caminho
   reduced-motion mostra tudo estático.

### 2026-07-10 — Esqueleto da API no ar (Fase 0): 3 superfícies + contexto de tenant

Scaffolding do backend (spec §Roadmap Fase 0), **independente** das decisões
bloqueantes Q2/Q5/Q7 — entrega um esqueleto que compila, sobe e prova a arquitetura,
sem as migrations de domínio (V013+).

1. **`api/` criado** via Spring Initializr — **Spring Boot 4.0.7 / Java 25**, Maven,
   deps: webmvc, security, oauth2-resource-server, validation, actuator, postgresql,
   flyway, **data-jdbc**, testcontainers. `groupId com.vetor.niner`.

2. **Decisão de persistência: Spring Data JDBC** (não JPA/Hibernate). Mais explícito e
   previsível para o padrão RLS + `SET LOCAL` por transação. A spec foi atualizada
   (§3.1.1, §3.2, §3.3.1): removidas as menções a Hibernate `@Filter` e JPA auditing;
   timestamps via `DEFAULT now()` + preenchimento no serviço de domínio.

3. **3 superfícies (ADR-007)** — `SegurancaConfig` com `SecurityFilterChain` separados
   por `securityMatcher`: `/api/publico/**` (+ actuator, permitAll), `/api/v1/**`
   (tenant), `/api/admin/**` (staff) e um chain default que **nega** o resto. JWT ainda
   não exigido (sem emissor nesta fase) — marcado `TODO(jwt)`; validação de `aud` entra
   na fase de auth.

4. **Infra de contexto de tenant (P8, §3.1.1)** — `TenantContext` com **`ScopedValue`**
   (Java 25); `TenantFilter` lê o claim `tid` do JWT e liga o contexto na cadeia de
   `/api/v1`; `TenantAwareTransactionManager` roda
   `select set_config('app.id_tenant', :tid, true)` no início da transação, casando com
   as políticas RLS (`plataforma.tenant_atual()`, V002). A app conecta como **`niner_app`**
   (sem BYPASSRLS); **Flyway roda separado** como `niner_owner` (serviço do compose) —
   `spring.flyway.enabled=false` na app.

5. **Pacotes-módulo do monólito** criados com `package-info.java`: `plataforma`,
   `identidade`, `catalogo`, `estoque`, `pedidos`, `precos`, `canais`, `integracao`,
   `comum/{config,tenant,web}`. Domínio entra com as migrations V013+.

6. **Serviço `api` no `docker-compose.yml`** + `Dockerfile` multi-stage (Maven+JDK 25 →
   JRE 25, usuário não-root). Ordem documentada: `db` → `flyway` → `api`.

7. **Verificação de ponta a ponta (tudo verde):**
   - `./mvnw test` → **7 testes** (context loads + 3 superfícies + propagação de tenant
     `tid=42 → id_tenant=42` + rota fora → 403), com **Testcontainers Postgres 18.4** e
     **Flyway aplicando V001–V012** (bootstrap de roles via `bootstrap-test.sql`).
   - App rodando ao vivo contra o `db` do compose (como `niner_app`):
     `/actuator/health` UP; `/api/publico|v1|admin/ping` → 200; rota fora → 403.
   - ⚠️ **Colima + Testcontainers:** exige `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`
     (senão o Ryuk falha ao montar o docker.sock). Anotado no README da `api/`.

8. **Gate P8 (parcial neste momento):** fecha com as tabelas de domínio + RLS — feito no
   mesmo dia (ver entrada seguinte).

### 2026-07-10 — App `web/` (ERP do lojista): login + handoff + painel

1. **`web/` criado** (React 19 + Vite + TS; React Router 7 + TanStack Query 5 — §3.2).
2. **Autenticação:** login (`slug + email + senha` → `POST /api/publico/login`) e
   **handoff SSO** do site — o botão "Ir para o sistema" do `/bem-vindo` leva o token via
   `#token=...`; o app consome, guarda e limpa a URL. Guarda de rota + logout em 401.
3. **Shell do ERP:** cabeçalho (marca + Sair) + navegação lateral (Painel, Produtos,
   Estoque, Pedidos, Canais). **Painel** real via `GET /api/v1/eu` (loja, assinatura TRIAL,
   usuário/papel + próximos passos). Áreas de domínio como placeholders "em construção".
4. **Design system §3.7** (tokens CSS, tema claro/escuro) — mesma paleta do site.
5. **Backend:** CORS já cobre a origem do web (`niner.cors.origins`).
6. **Verificação (e2e, Playwright):** login → painel "Olá, Ana / Loja Web Teste / TRIAL";
   navegação para "Produtos"; **handoff** `#token=` → entra logado e limpa o hash. **Build ok.**
   *(Durante a verificação, uma instância antiga da API presa na 8080 foi derrubada e a API
   subiu do jar atual — sem mudança de código.)*
7. Serviço `web` adicionado ao `docker-compose.yml` (perfil `fronts`).

> O loop de aquisição está fechado: **site → assinar → tenant + trial → "Ir para o sistema" → web logado**.
> Falta: `admin/` (backoffice), endpoints de domínio reais em `/api/v1`, e R22 (ajuda) nos fronts.

### 2026-07-10 — Site público (Astro/SSG) + planos + CORS: aquisição self-service no ar

1. **ADR-011 decidido: Astro (SSG)** para o `site/` (SEO/Core Web Vitals). `web`/`admin`
   seguem React+Vite.
2. **`site/` criado** (Astro): landing SEO-forte (`<title>`/description/OG/canonical, h1,
   planos renderizados no HTML estático), `/assinar` (formulário → `POST /api/publico/assinar`
   → guarda token e vai para `/bem-vindo`), `/bem-vindo` (primeiro uso: `GET /api/v1/eu`).
   Design tokens §3.7 (tema claro/escuro); base-URL da API lida em runtime (`public/config.js`).
   **Build SSG ok** (3 páginas estáticas).
3. **Backend de apoio:** `GET /api/publico/planos` (R11, catálogo público) e **CORS**
   (`niner.cors.origins`) para os fronts chamarem a API. Preflight e planos validados com
   `Origin` do site (headers `Access-Control-Allow-*` corretos). **11 testes seguem verdes.**
4. Serviço `site` adicionado ao `docker-compose.yml` (perfil `fronts`).

> Pendente no site: **R22** (ajuda de tela + vídeo), páginas de conteúdo/sitemap, e o botão
> "Ir para o sistema" ligado ao app `web/` (a criar).

### 2026-07-10 — Motor do trial self-service (R12): signup → tenant + JWT + primeiro uso

Implementado o fluxo de **assinatura-teste** (14 dias, sem cartão — D2) na superfície pública,
com autenticação JWT real protegendo o ERP.

1. **Auth JWT (HS256)** — `JwtConfig` (encoder/decoder com segredo simétrico; decoder valida
   assinatura, expiração e **`aud=tenant`**), `TokenService` (emite token com `sub`/`tid`/`aud`/`roles`),
   `BCryptPasswordEncoder`. Config em `niner.jwt.*`. `/api/v1/**` agora **exige** JWT (era permitAll);
   o `TenantFilter` passa a ler o `tid` de um token real.

2. **`POST /api/publico/assinar`** (`SignupService`, atômico) — numa única transação: cria
   `plataforma.tenant` (TRIAL), estabelece `app.id_tenant` (para o RLS deixar inserir o domínio),
   cria `assinatura` TRIAL (plano-base **Profissional**, `trial_expira_em = now()+14d`), `uso_tenant`,
   `empresa`, `cfg_geral` (configurações padrão) e o primeiro **usuário ADMIN** (senha em BCrypt).
   Devolve o **token de primeiro acesso (auto-login)** + slug + validade do trial.

3. **`POST /api/publico/login`** (slug da loja + email + senha) e **`GET /api/v1/eu`** (primeiro uso:
   com o token, o cliente já enxerga a própria conta/assinatura via RLS).

4. **Verificação (tudo verde):**
   - **11 testes** (`OnboardingTrialTest`: signup cria tenant e libera 1º uso; login; 401 sem token).
   - **Ao vivo como `niner_app`** (RLS ativo): signup de "Loja do Ze" criou `empresa=1`, `cfg_geral=1`,
     `usuario_admin=1`, `assinatura=TRIAL`, `uso.qtd_usuarios=1`; `/api/v1/eu` com o token → conta TRIAL;
     sem token → 401; login → 200.

### 2026-07-10 — Decisões Q2/Q7/Q5 fechadas + domínio do lojista (V013–V024) + gate P8 verde

1. **Decisões bloqueantes de arquitetura fechadas** (todas):
   - **Q2 (ADR-004)** — reserva no **`recebido`** + expiração por canal (§3.3.5).
   - **Q7** — **separar** `sku` interno (obrigatório/único) de `ean` (GTIN, nullable/único), EAN
     exigido só na publicação (§3.3.3).
   - **Q5 (ADR-010)** — `financeiro` do lojista **fora do v1** (Fase 2, crediário priorizado; §3.3.7).
   - ADRs renumerados: **ADR-010** = financeiro fora do v1; **ADR-011** = framework do site (SEO, em aberto).

2. **Migrations de domínio V013–V024 criadas e validadas em banco real** (Postgres 18.4),
   convertendo o legado Firebird conforme §3.3.1 (minúsculo `snake_case`, `NUMERIC` para
   dinheiro, `BOOLEAN`, `TIMESTAMPTZ`, `BIGINT IDENTITY`) e com **`id_tenant` em toda tabela**:
   - identidade (`empresa`, `usuario`, `usuario_rotina`), cadastros (`cliente`/`fornecedor`/`funcionario`),
     catálogo (`produto`, `produto_barra` com `sku`+`ean`), estoque (`produto_estoque` com
     `reservado`/`disponivel` gerado, ledger imutável, balanço), vendas, canais (`canal`/`anuncio`),
     pedidos de canal (idempotência `(canal,id_externo)`), integração (`outbox_evento`/`webhook_recebido`),
     `cfg_geral`.
   - Convenção de domínio: surrogate `id_<x>` PK + `id_tenant` FK + chaves naturais únicas **por tenant**.

3. **RLS de domínio (V024)** — arquivo único e final: `ENABLE`+`FORCE ROW LEVEL SECURITY` +
   política `USING/WITH CHECK (id_tenant = plataforma.tenant_atual())` + grants de `niner_app` em
   **todas** as tabelas de tenant, mais um **guarda-corpo** que faz a migration falhar se alguma
   tabela com `id_tenant` ficar sem RLS (P8 auto-verificável).

4. **✅ Gate P8 verde:** teste automatizado (`RlsIsolamentoTest`, Testcontainers) conectando como
   **`niner_app`** (sem BYPASSRLS) prova: T1 não lê produto de T2; `WITH CHECK` bloqueia gravar para
   outro tenant; sem contexto não vê nada. **Suíte: 8 testes verdes.**

### 2026-07-09 — Infra local no ar: Postgres 18 + migrations validadas

1. **`docker-compose.yml` criado** (raiz, spec §3.5) com dois serviços por ora:
   - `db` — `postgres:18` (banco **`niner_db`**), volume `pgdata`, healthcheck, e `db/bootstrap/` montado em `/docker-entrypoint-initdb.d` (roles criadas no primeiro init).
   - `flyway` — `flyway/flyway:11` sob profile `migrate`; roda como **`niner_owner`** e aplica `db/migration/`. Uso: `docker compose up -d db` e `docker compose run --rm flyway`.
   - Nota técnica: a imagem `postgres:18` mudou o volume de dados para `/var/lib/postgresql` (o yaml de exemplo da spec §3.5 ainda mostra o caminho antigo `/var/lib/postgresql/data`).
   - `api`/`web`/`admin`/`site` entram no compose quando forem scaffolded.

2. **Migrations V001–V012 aplicadas com sucesso em banco real** (PostgreSQL 18.4) — antes só havia validação estática. Verificado:
   - Roles `niner_owner`/`niner_app` criadas, ambas **sem `BYPASSRLS`** (P8).
   - 9 tabelas no schema `plataforma` (dono `niner_owner`); seed dos 3 planos (Essencial R$ 99 / Profissional R$ 249 / Escala R$ 599 — 🔴 provisórios, D1).
   - `plataforma.tenant_atual()` lê `app.id_tenant` corretamente.
   - `niner_app` lê `plataforma.plano` (grant V011) e **não consegue** criar objetos no schema (`permission denied`) — como esperado.

### 2026-07-08 — Pivô para SaaS multi-tenant (spec v2.0)

1. **Padrão de telas incorporado** — `docs/padroes/TELA.rar` descompactado; mockup `cadastro_fornecedor_campos_cinza.html` mantido como *golden file* de UI; RAR e pasta avulsa removidos. Design system documentado na nova **§3.7** da spec (tokens de cor, tema claro/escuro, grid de 12 colunas, componentes de campo/botão, acessibilidade).

2. **Decisão de produto: virar SaaS multi-tenant.** A spec passou a **v2.0**. O que mudou:
   - **Constituição:** novos **P8** (isolamento de tenant inviolável via Postgres RLS) e **P9** (separação control-plane × tenant).
   - **PRD:** multi-tenant/site público/trial/cobrança deixaram de ser non-goal e viraram **CORE**; novas personas C–F; requisitos **R10–R21**; métricas de funil SaaS.
   - **Plano técnico:** topologia de **uma API com 3 superfícies** (`/api/publico`, `/api/v1`, `/api/admin`) + 3 apps React (`web`/`admin`/`site`); **§3.1.1** isolamento de tenant; **§3.3.11** módulo `plataforma` (control-plane); **§3.5.1** sequência de migrations V001–V091 para `niner_db`; ADR-006 a 009.
   - **Novo `docs/PLANO-DE-NEGOCIO.md`** com o plano comercial.

3. **Decisões tomadas nesta sessão:**
   - **Topologia:** Opção A (uma API, monólito modular) — mas **API stateless** e **base-URL configurável em runtime** nos fronts, para rodar 2 servidores/2 APIs e trocar o endereço em manutenção/failover.
   - **Isolamento:** banco único + `id_tenant` + **Postgres RLS** (`FORCE`).
   - **Gateway de cobrança:** **adiado** — adapter abstrato, cobrança manual no início (D3).
   - **Trial:** **14 dias, sem cartão** (D2).
   - **Q6 fechada:** manter `id_empresa` **e** adicionar `id_tenant` (`tenant 1:N empresa`, 1:1 no v1).

4. **Migrations do control-plane criadas** (`db/migration/V001–V012` + `db/bootstrap/00_roles.sql`) — o schema `plataforma` que controla o tenant: roles `niner_owner`/`niner_app` (esta **sem BYPASSRLS**), função de contexto `plataforma.tenant_atual()`, tipos ENUM, e tabelas `tenant`, `plano`, `assinatura`, `fatura`, `pagamento`, `webhook_gateway`, `uso_tenant`, `staff`, `impersonacao_log`, grants e seed de planos (🔴 preços provisórios). Numeração **renumerada em ordem contígua**; domínio do lojista + políticas RLS ficam em V013+ (§3.5.1). Validação apenas estática (Docker daemon desligado no momento).

5. **Novo requisito R22 (ajuda por tela):** toda tela (ERP/backoffice/site) deve ter **ajuda/manual de operação contextual + acesso a vídeo explicativo** (§3.7.1: componente `AjudaDaTela`, catálogo `ajuda_tela` servido pela API, `url_video` NULL ⇒ "em breve"). Adicionado ao template de Spec de Feature (§5) e ao gate de aprovação.

### 2026-07-07 — Fundação da documentação

1. **`CLAUDE.md` criado** — documenta que o repo é spec-driven, a Constituição (P1–P7), a arquitetura pretendida (monolito modular, adapters de canal, outbox no Postgres) e o alerta de que o `db/` é legado Firebird (não carregar padrões como `FLOAT` para dinheiro).

2. **Spec atualizado para v1.1** — modelo de dados (§3.3) reescrito a partir das tabelas reais do `db/`, adaptado de Firebird para PostgreSQL:
   - Nova **§3.3.1** — regras de conversão Firebird → PostgreSQL (`FLOAT`→`NUMERIC`, `S/N`→`BOOLEAN`, `GENERATOR`→`IDENTITY`, procedures/triggers→domínio Java, `TIMESTAMP`→`TIMESTAMPTZ`, etc.).
   - Modelo reorganizado por módulo (§3.3.2–§3.3.9): `identidade`, `catalogo`, `estoque`, `pedidos`, `integracao`, `financeiro`, config e cadastros.
   - Domínio de **marketplaces** (ausente no legado) integrado: `canal`, `anuncio`, `pedido`, `pedido_item`, `outbox_evento`, `webhook_recebido`.
   - Pendências marcadas em **vermelho** (🔴) — convenção definida no topo do documento.

3. **Commit `6c65765`** — CLAUDE.md + `db/` + spec v1.1 enviados para `origin/main`.

4. **GitHub CLI instalado e autenticado** — `gh` 2.96.0 em `C:\Program Files\GitHub CLI\gh.exe` (via winget), autenticado como `evirson` (device flow; escopos `gist`, `read:org`, `repo`).

5. **Coautor convidado** — `claudiocalixto` (`claudio@vetorsistemas.com.br`) convidado com acesso **Write** via `gh api PUT .../collaborators/claudiocalixto -f permission=push`. Convite **aguardando aceite**.

6. **Commit `76922ac`** — `docs/PROGRESSO.md` enviado para `origin/main` (após rebase sobre o commit remoto `9e4fa65` "inclusao de base").

---

## Decisões bloqueantes em aberto (ver §2.7 e §3.3 da spec)

**Todas as bloqueantes de arquitetura (Q2/Q5/Q6/Q7) estão fechadas** — o domínio (V013+) está destravado. Restam só decisões de **negócio**, que não travam o schema central:

- **Decisões de negócio do SaaS (D1–D10)** — ver `docs/PLANO-DE-NEGOCIO.md`. Abertas: D1 preços, D3 gateway (adiado), D5 nome "Niner", D6 NFS-e da assinatura, D8 dunning, D9 metas, D10 comportamento do estado `INADIMPLENTE`.
- **ADR-011 — framework do site público (SEO):** Astro × Next, "decidir depois" (não bloqueia o backend).

> ✅ **Q5 — módulo `financeiro` do lojista:** fechada em 2026-07-10 — **fora do v1** (junto com PDV, §2.3); vai para a **Fase 2** com **crediário priorizado**. R9 (venda manual) é atendido por `venda` + baixa de estoque, sem financeiro. Vira **ADR-010**. Ver §3.3.7.
> ✅ **Q7 — SKU vs EAN:** fechada em 2026-07-10 — **separar** `sku` interno (obrigatório, único, chave do domínio; ex-`codigo_barra`) de `ean` (GTIN real, nullable, único quando preenchido). EAN exigido só na **publicação** em canal, não no cadastro. Nas migrations V013+, as FKs que apontavam para `codigo_barra` passam a referenciar `sku`. Ver §3.3.3.
> ✅ **Q2 — estratégia de reserva:** fechada em 2026-07-10 — reservar no **`recebido`** (pedido importado já incrementa `reservado`), com expiração configurável por canal que devolve reservas não pagas. Alinha R5 + P1. Vira **ADR-004**; adicionar colunas `reservado`/`minimo` a `produto_estoque` nas migrations de domínio. Ver §3.3.5.
> ✅ **Q6 — multi-empresa/tenant:** fechada em 2026-07-08 — manter `id_empresa` **e** adicionar `id_tenant` (banco único + RLS; `tenant 1:N empresa`, 1:1 no v1).

---

## Colaboração / acesso ao repositório

- **Claudio** — username GitHub **`claudiocalixto`** (`claudio@vetorsistemas.com.br`) — é **coautor** com acesso **Write** ao repo `evirson/niner-v2`.
- **Status:** ✅ **convite enviado (2026-07-07), aguardando aceite.** Ele aceita pelo e-mail do GitHub ou em https://github.com/evirson/niner-v2/invitations.
- Após aceitar, comita com a própria identidade Git (`user.name`/`user.email`) e aparece como autor nos commits.

---

## Próximos passos sugeridos

**Feito até 2026-07-10:** ✅ decisões de arquitetura (Q2/Q5/Q6/Q7 + ADRs) · ✅ esqueleto da API + 3 superfícies + contexto de tenant · ✅ domínio V013–V024 + RLS + **gate P8** · ✅ **trial self-service** (signup atômico + JWT + login + `/eu`) · ✅ **site** (Astro/SSG) · ✅ **web** (ERP: login + handoff SSO + painel). **Loop de aquisição fechado** (site → trial → web logado).

**Retomar amanhã (2026-07-11) — ordem sugerida:**

1. **⭐ Vertical slice de Produtos (começar por aqui):** valida a stack inteira ponta a ponta.
   - Backend `/api/v1`: `GET /api/v1/produtos` (lista, cursor) + `POST /api/v1/produtos` (cria produto + variação com `sku`/`ean`) — camada Spring Data JDBC sobre `produto`/`produto_barra`, com o `TenantContext`/RLS já ligados. Atualizar `uso_tenant.qtd_produtos` (enforcement R19 depois).
   - Web: tela **Produtos** real (listar + criar) no lugar do placeholder, via TanStack Query.
2. **Estoque:** `produto_estoque` (saldo/reserva) + movimentações (`POST /api/v1/estoque/movimentacoes`) → tela de estoque.
3. **`admin/`** — backoffice da plataforma (lista/ficha de tenants R17, suspender/impersonar R18/R21).
4. **R22** (`AjudaDaTela` + `ajuda_tela`) nos fronts; páginas de conteúdo/SEO no site.
5. Decisões de negócio em aberto: D1 (preços), D3 (gateway), D5/D6/D8/D9/D10.

**Como subir o ambiente:** `docker compose up -d db && docker compose run --rm flyway` · API: `cd api && ./mvnw spring-boot:run` (ou `java -jar target/*.jar`) · fronts: `cd site && npm run dev` / `cd web && npm run dev`. Testes da API: `cd api && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./mvnw test` (Colima). ⚠️ Se a API der 401 no `/api/publico/**`, há instância velha presa na 8080 → `lsof -ti tcp:8080 | xargs kill -9`.
