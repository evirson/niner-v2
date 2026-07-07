# Modelo Spec-Driven — ERP de Varejo com Integração a Marketplaces

**Stack:** React (frontend) · Java 25 + Spring Boot 4.x (API) · PostgreSQL 18 (Docker) · Docker Compose
**Versão do documento:** 1.1 · **Status:** Rascunho para preenchimento

> **Convenção de marcação:** itens em <span style="color:red">🔴 **vermelho**</span> são **pendências** — decisões ou tarefas que ainda preciso resolver antes de codar. Renderizam em vermelho em VS Code/Obsidian; no GitHub aparecem com o marcador 🔴. Ao fechar uma pendência, remova a marcação.

---

## Como usar este modelo (fluxo spec-driven)

O desenvolvimento orientado a especificação segue quatro artefatos em cadeia. Nada é implementado sem que o artefato anterior esteja aprovado:

1. **Constituição** — princípios imutáveis do projeto (seção 1). Escreve-se uma vez, muda raramente.
2. **Spec do produto (PRD)** — o *o quê* e o *porquê* (seção 2). Sem menção a tecnologia.
3. **Plano técnico** — o *como* (seção 3). Arquitetura, contratos, modelo de dados.
4. **Tasks** — decomposição em unidades testáveis e entregáveis (seção 4).

Para cada nova feature após o MVP, use os templates das seções 5, 6 e 7 (spec de feature, ADR e task). Regra de ouro: **se surgir uma dúvida durante a implementação, a resposta deve estar na spec; se não estiver, a spec é atualizada antes do código.**

---

# 1. Constituição do Projeto

Princípios que nenhuma spec ou task pode violar:

- **P1 — Estoque é a fonte da verdade.** O ERP é o master de estoque e preço; marketplaces são réplicas. Nenhuma integração escreve estoque de volta sem passar pelo domínio central.
- **P2 — Toda integração é assíncrona e idempotente.** Chamadas a marketplaces passam por fila/outbox com retry. Reprocessar um evento nunca pode duplicar pedido, baixa de estoque ou nota.
- **P3 — Auditabilidade.** Toda mutação de estoque, preço e pedido gera registro imutável (quem, quando, origem, valor anterior/novo).
- **P4 — API-first.** O frontend React consome exclusivamente a API REST pública/interna. Nenhuma lógica de negócio no frontend.
- **P5 — Testes acompanham a spec.** Todo critério de aceitação vira teste automatizado antes do merge (contrato Given/When/Then → teste).
- **P6 — Simplicidade primeiro.** Monolito modular antes de microsserviços. Uma instância Postgres. Escalar só quando a métrica justificar.
- **P7 — Dinheiro em `NUMERIC`.** Valores monetários nunca em float; sempre `NUMERIC(12,2)` no banco e `BigDecimal` no Java.

---

# 2. Spec do Produto (PRD)

## 2.1 Problema

Pequenos varejistas que vendem em loja física e em 2–5 canais online (Mercado Livre, Shopee, Amazon, loja própria) gerenciam estoque, preço e pedidos em planilhas e painéis separados de cada canal. Isso causa venda de itens sem estoque (cancelamentos e penalização de reputação nos marketplaces), preços desatualizados e horas diárias de retrabalho conciliando pedidos. O custo de não resolver: perda de reputação nos canais, ruptura de estoque e incapacidade de crescer sem contratar operação.

## 2.2 Objetivos (mensuráveis)

- **O1:** Zerar overselling — 0 cancelamentos por falta de estoque após 60 dias de uso (baseline do cliente).
- **O2:** Sincronizar estoque e preço com todos os canais em < 2 minutos após alteração no ERP.
- **O3:** Reduzir em 80% o tempo de conciliação de pedidos multi-canal (medido por pesquisa com usuários-piloto).
- **O4:** Importar 100% dos pedidos de marketplace automaticamente, sem digitação manual.
- **O5:** Onboarding de um novo canal em < 30 minutos pelo próprio lojista.

## 2.3 Non-Goals (fora de escopo do v1)

- **Emissão fiscal (NF-e/NFC-e):** integrar com emissor terceiro via API é P2; construir emissor próprio nunca.
- **PDV/frente de caixa:** o v1 registra vendas da loja física por lançamento manual/importação; PDV completo é produto separado.
- **Multi-empresa/multi-CNPJ:** v1 atende um CNPJ por conta. Arquitetura deve permitir depois (P2).
- **Logística própria (etiquetas, rastreio):** usa-se a logística do próprio marketplace (ML Envios, etc.). Gestão de transportadora própria fica fora.
- **BI avançado:** v1 entrega relatórios operacionais básicos; dashboards analíticos ficam para v2.

## 2.4 Personas e User Stories

**Persona A — Dono/gestor da loja** (decide preço, compra estoque)
**Persona B — Operador** (separa pedidos, dá baixa, cadastra produto)

Prioridade decrescente:

1. Como **operador**, quero que pedidos de todos os canais apareçam numa fila única para eu separar e expedir sem abrir cada painel.
2. Como **gestor**, quero que a venda em qualquer canal baixe o estoque central e atualize os demais canais automaticamente, para nunca vender sem estoque.
3. Como **gestor**, quero cadastrar um produto uma única vez e publicá-lo nos canais que eu escolher, com preço por canal.
4. Como **operador**, quero registrar entrada de mercadoria (compra) e ajustes de inventário com motivo, para o estoque refletir a realidade.
5. Como **gestor**, quero definir margem/markup e ver o preço sugerido considerando a comissão de cada canal.
6. Como **gestor**, quero conectar minha conta do Mercado Livre/Shopee com OAuth em poucos cliques, sem suporte técnico.
7. Como **operador**, quero ver o status de sincronização de cada anúncio (ok, pendente, erro) e reprocessar erros com um clique.
8. Como **gestor**, quero relatório de vendas por canal, por produto e por período.
9. *(edge)* Como **operador**, quando dois canais venderem a última unidade quase simultaneamente, quero que o sistema aceite o primeiro, cancele/alerta o segundo e me notifique.
10. *(edge)* Como **gestor**, quando a API de um marketplace ficar fora do ar, quero que as sincronizações fiquem enfileiradas e sejam aplicadas quando voltar, sem perda.

## 2.5 Requisitos

### Must-Have (P0) — MVP

| ID | Requisito | Critérios de aceitação (resumo) |
|----|-----------|-------------------------------|
| R1 | Catálogo de produtos com variações (SKU, EAN, cor/tamanho), categorias e fotos | Dado um produto com 3 variações, quando salvo, então cada variação tem SKU único validado |
| R2 | Estoque central com movimentações tipadas (entrada, venda, ajuste, devolução, reserva) | Toda movimentação registra origem, usuário e saldo resultante; saldo nunca negativo sem flag explícita |
| R3 | Conector Mercado Livre: OAuth, publicar/vincular anúncio, sync de estoque/preço, importar pedidos via webhook + polling de segurança | Dado estoque alterado no ERP, quando decorridos ≤ 2 min, então anúncio ML reflete o valor |
| R4 | Conector Shopee (mesmo escopo do R3) | idem |
| R5 | Fila única de pedidos multi-canal com estados: `recebido → pago → em separação → enviado → entregue / cancelado` | Pedido importado gera reserva de estoque; cancelamento devolve reserva |
| R6 | Vinculação de anúncio existente a SKU do ERP (de-para) | Dado anúncio ML já publicado, quando vinculado a um SKU, então passa a ser sincronizado |
| R7 | Painel de saúde das integrações com fila de erros e reprocessamento manual | Erro de sync exibe payload, motivo e botão "reprocessar" |
| R8 | Autenticação (e-mail/senha + JWT), papéis `ADMIN` e `OPERADOR` | Operador não acessa configuração de integrações nem preços de custo |
| R9 | Venda manual (loja física) com baixa de estoque | Venda manual dispara mesma sincronização que venda de canal |

### Nice-to-Have (P1)

- Conector Amazon e Magalu; conector de e-commerce próprio (Nuvemshop, Shopify, WooCommerce via plugin/API).
- Regras de preço por canal (markup sobre custo + comissão do canal).
- Importação de catálogo via planilha (CSV/XLSX).
- Notificações (e-mail/WhatsApp) para pedido novo e estoque mínimo.
- Kits/composições (um anúncio consome múltiplos SKUs).

### Future (P2) — decisões de arquitetura devem permitir

- Emissão de NF-e via emissor parceiro (Focus NFe, eNotas ou similar).
- Multi-depósito e multi-CNPJ.
- Precificação dinâmica/repricing competitivo.
- App mobile de conferência/expedição (leitura de código de barras).

## 2.6 Métricas de Sucesso

**Leading (semanas):** % de pedidos importados sem intervenção (meta ≥ 99%); latência p95 de sync estoque→canal (meta < 120 s); taxa de erro de sync (meta < 1% com auto-retry resolvendo 90%).
**Lagging (meses):** cancelamentos por falta de estoque (meta 0); retenção dos lojistas-piloto em 90 dias (meta ≥ 80%); tempo médio diário de operação manual (meta −80% vs. baseline).
Medição: métricas expostas via endpoint `/actuator` + tabela de eventos; avaliação em 30/60/90 dias pós-go-live.

## 2.7 Questões Abertas

| # | Questão | Responsável | Bloqueante? |
|---|---------|-------------|-------------|
| Q1 | Shopee exige empresa registrada no programa de parceiros — prazo de aprovação? | Negócio | Sim (para R4) |
| Q2 | Estratégia de reserva: reservar no `pago` ou no `recebido`? (marketplaces variam) | Produto + Eng | Sim |
| Q3 | Limite de rate das APIs (ML: ~X req/s por app) comporta quantos lojistas por app credential? | Engenharia | Não |
| Q4 | Precisamos de LGPD DPA com os marketplaces para dados de comprador? | Jurídico | Não |
| Q5 | <span style="color:red">🔴 O módulo `financeiro` (caixa, crediário, contas a pagar/receber, conta corrente) do schema legado entra no v1 ou fica para depois? (ver §3.3.7)</span> | Produto + Eng | Sim |
| Q6 | <span style="color:red">🔴 Multi-empresa: manter `id_empresa` desde já (preparando P2) ou remover no v1? (ver §3.3.2)</span> | Produto + Eng | Sim |
| Q7 | <span style="color:red">🔴 SKU = EAN (código de barras) ou código interno + coluna `ean` separada? (ver §3.3.3)</span> | Produto + Eng | Sim |

---

# 3. Plano Técnico

## 3.1 Arquitetura (monolito modular)

```
┌────────────┐   HTTPS/JSON    ┌──────────────────────────────────────┐
│  React SPA │ ──────────────► │  API Java 25 · Spring Boot 4.x       │
│  (Vite)    │                 │                                      │
└────────────┘                 │  módulos (packages):                 │
                               │   catalogo/  estoque/  pedidos/      │
      webhooks ──────────────► │   precos/    canais/   identidade/   │
   (ML, Shopee, ...)           │   integracao/{mercadolivre,shopee}   │
                               │                                      │
                               │  outbox + scheduler (retry/poll)     │
                               └──────────────┬───────────────────────┘
                                              │ JDBC
                                       ┌──────▼──────┐
                                       │ PostgreSQL18│  (Docker)
                                       └─────────────┘
```

> <span style="color:red">🔴 O schema legado (`db/*.txt`) traz também um módulo **`financeiro`** (caixa, crediário, contas a pagar/receber, conta corrente) não representado neste diagrama. Confirmar se entra no v1 e, em caso positivo, adicioná-lo aqui — ver §3.3.7 e Q5.</span>

Decisões-chave (registrar cada uma como ADR — template na seção 6):

- **Monolito modular** com módulos por contexto de domínio; comunicação entre módulos por interfaces Java + eventos de domínio internos (Spring events ou Spring Modulith). Justificativa: time pequeno, deploy único, P6.
- **Padrão de integração:** cada marketplace tem um *adapter* isolado (anti-corruption layer) que implementa a interface comum `CanalDeVenda` (`publicarAnuncio`, `atualizarEstoque`, `atualizarPreco`, `importarPedidos`, `confirmarEnvio`). O domínio nunca conhece payloads do ML/Shopee.
- **Outbox pattern:** mutações de estoque/preço gravam evento na tabela `outbox_eventos` na mesma transação; um worker (Spring `@Scheduled` no v1; fila dedicada só se necessário) publica para os canais com retry exponencial e dead-letter.
- **Idempotência:** pedidos importados usam chave natural `(canal, id_externo)` com constraint única; webhooks processados registram `webhook_id` recebido.
- **Sem broker externo no v1** (sem Kafka/RabbitMQ): Postgres como fila via outbox + `SELECT ... FOR UPDATE SKIP LOCKED`. ADR explícito; revisitar se throughput exigir.

## 3.2 Stack e versões

| Camada | Escolha | Observação |
|--------|---------|------------|
| Linguagem | Java 25 (LTS) | Virtual threads habilitadas (`spring.threads.virtual.enabled=true`) para I/O de integrações |
| Framework | Spring Boot 4.x / Spring Framework 7 | Suporte oficial a Java 25 |
| Persistência | Spring Data JPA + Hibernate; Flyway para migrations | Migrations versionadas em `db/migration`. Auditoria de datas via `@CreatedDate`/`@LastModifiedDate` (substitui os triggers Firebird de data) |
| Banco | PostgreSQL 18 (imagem oficial `postgres:18`) | `JSONB` para payloads brutos de integração. Schema legado em `db/*.txt` é **Firebird** e serve de referência — ver §3.3.1 para as regras de conversão |
| Frontend | React 19 + Vite + TypeScript | TanStack Query para dados; React Router |
| UI | shadcn/ui ou Mantine | decidir por ADR |
| Auth | Spring Security + JWT (access curto + refresh) | |
| Docs API | springdoc-openapi (OpenAPI 3.1) | Contrato gerado é parte da spec |
| Observabilidade | Spring Actuator + Micrometer; logs JSON | |
| Testes | JUnit 5, Testcontainers (Postgres real), WireMock (APIs de marketplace), Playwright (e2e crítico) | |

## 3.3 Modelo de dados

O modelo abaixo parte do **schema legado real** (`db/*.txt`, originalmente Firebird) já **adaptado para PostgreSQL** e reorganizado por módulo de domínio. Nomes em `snake_case` minúsculo (idioma Postgres), vocabulário de domínio em **português** preservado. Tabelas/colunas marcadas em <span style="color:red">🔴</span> **ainda não existem no legado** e precisam ser criadas para atender à Constituição e aos requisitos (integração com marketplaces, reserva, auditoria).

### 3.3.1 Regras de conversão Firebird → PostgreSQL

Aplicar de forma sistemática ao gerar as migrations Flyway a partir de `db/*.txt`:

| Legado (Firebird) | PostgreSQL | Observação |
|---|---|---|
| `FLOAT` para dinheiro (`preco_custo`, `preco_venda`, `valor_*`, `saldo_*`) | `NUMERIC(12,2)` | **P7** — nunca float para dinheiro. Regra dura. |
| `FLOAT` para quantidade (`qtd_estoque`, `qtd_produto`, `qtd_contagem`) | `NUMERIC(14,3)` | permite fracionado (peso/volume) |
| `FLOAT` para percentual (`perc_comissao`, `perc_desconto`, `taxa_administradora`) | `NUMERIC(5,2)` | |
| `VARCHAR(1) CHECK IN ('S','N')` | `BOOLEAN` | `ativo`, `bloqueado`, `caixa_fechado`, `documento_pago`... |
| `VARCHAR(1/2) CHECK` de domínio (`tipo_operacao`, `credito_debito`, `fisica_juridica`, `genero`, `status_sync`) | `ENUM` nativo **ou** `VARCHAR` + `CHECK` | usar ENUM quando o conjunto for estável |
| `INTEGER ... GENERATED BY DEFAULT AS IDENTITY` (PK) | `BIGINT GENERATED ALWAYS AS IDENTITY` | migrar PKs para `BIGINT` por escala |
| `CREATE GENERATOR GT_*` + `gen_id()` (arquivo `100_GERADORES.txt`) | `IDENTITY`/`SEQUENCE` | `id_venda`, `id_devolucao`, `id_transferencia`, `id_movimento`, `id_lote_recebimento` passam a IDENTITY |
| `CREATE PROCEDURE` PSQL (`SP_ATUALIZA_QUANTIDADE_ESTOQUE`) | <span style="color:red">🔴 **mover para o domínio Java**</span> | manter saldo no serviço de estoque, na mesma transação da movimentação (P1/P3). PL/pgSQL só como fallback. |
| `CREATE TRIGGER` de auditoria de data (`TG_*_INSERT_UPDATE`) | JPA auditing (`@CreatedDate`/`@LastModifiedDate`) | remove os triggers `102_TRIGGERS.txt` |
| `TIMESTAMP` | `TIMESTAMPTZ` | armazenar com fuso (UTC) |
| `LOCALTIMESTAMP` | `now()` / relógio da app | |
| `IMAGEM VARCHAR(200)` (caminho de arquivo) | manter URL/chave de object storage | não gravar binário no banco |

### 3.3.2 Módulo `identidade` (auth, empresas, permissões)

```sql
empresa(id_empresa PK, razao_social, cnpj, inscricao, endereco, numero, bairro,
        cidade, estado, cep, telefone, email, imagem_relatorio)
usuario(id_usuario PK, nome_usuario, email UNIQUE, senha_hash,
        ativo BOOL, administrador BOOL)          -- 'papel' ADMIN/OPERADOR deriva de administrador
usuario_rotina(id_usuario FK, nome_rotina, PK(id_usuario, nome_rotina))  -- permissões finas legadas
```
<span style="color:red">🔴 **Decisão pendente (multi-empresa):** o legado é multi-empresa (`id_empresa` em quase toda tabela), mas o Non-goal §2.3 define v1 = **1 CNPJ por conta**. Decidir: (a) manter a coluna `id_empresa` fixa/default no v1 preparando o P2, ou (b) remover agora e reintroduzir depois. Registrar como ADR.</span>
<span style="color:red">🔴 Mapear `usuario.administrador` + `usuario_rotina` para os papéis `ADMIN`/`OPERADOR` (R8) — definir se v1 usa RBAC simples (2 papéis) ou mantém as rotinas granulares.</span>
<span style="color:red">🔴 `senha` do legado é texto — no v1 é **hash** (BCrypt/Argon2) + JWT (P4/R8).</span>

### 3.3.3 Módulo `catalogo` (produtos, variações, SKU)

```sql
cfg_categoria_produto(id_categoria PK, nome_categoria)
cfg_variante_linha(id_variante_linha PK, descricao)      -- ex.: cor
cfg_variante_coluna(id_variante_coluna PK, descricao)    -- ex.: tamanho / voltagem
produto(id_produto PK, ativo BOOL, marca, referencia, descricao,
        preco_custo NUMERIC(12,2), percentual_venda NUMERIC(5,2),
        preco_venda NUMERIC(12,2), data_inicio_oferta, data_final_oferta,
        preco_oferta NUMERIC(12,2), codigo_ncm, peso_bruto NUMERIC(14,3),
        peso_liquido NUMERIC(14,3), nome_variante_linha, nome_variante_coluna,
        imagem, criado_em, alterado_em, reajustado_em)
produto_categoria(id_produto FK, id_categoria FK, PK(id_produto, id_categoria))
-- PRODUTOS_BARRA é o SKU real: 1 código de barras por (produto × linha × coluna)
produto_barra(codigo_barra PK, id_produto FK, id_variante_linha FK, id_variante_coluna FK)
```
> **Mapeamento p/ a spec:** o par (`produto` + `produto_barra`) implementa `produto`+`variacao` do PRD (R1). O **SKU = `codigo_barra`** (estrutura documentada em `033_PRODUTOS_BARRA.txt`: grupo+sequencial+dígito).

<span style="color:red">🔴 Garantir unicidade de SKU (R1): `codigo_barra` é PK — ok; adicionar `UNIQUE` de `(id_produto, id_variante_linha, id_variante_coluna)` para impedir barra duplicada da mesma variação.</span>
<span style="color:red">🔴 Falta `ean` explícito (R1 pede EAN): decidir se `codigo_barra` = EAN ou se é código interno + coluna `ean` separada.</span>

### 3.3.4 Módulo `estoque` (saldo, movimentações, balanço)

```sql
-- saldo materializado por SKU × empresa
produto_estoque(id_empresa FK, codigo_barra FK, qtd_estoque NUMERIC(14,3),
                reservado NUMERIC(14,3),          -- 🔴 novo (P1/R5: reserva)
                minimo NUMERIC(14,3),             -- 🔴 novo (estoque mínimo / alerta)
                atualizado_em,                    -- 🔴 novo
                PK(codigo_barra, id_empresa))
-- ledger de movimentação (imutável — P3)
produto_movimento_mestre(id_movimento PK, tipo_movimento,   -- 1 compra,2 transf,3 devol,4 ajuste,5 venda
                         data_movimento, id_empresa FK, id_fornecedor FK,
                         id_transferencia, id_devolucao FK, id_venda FK, nota_fiscal)
produto_movimento_detalhe(localizador PK, id_movimento FK, id_empresa FK,
                          id_funcionario FK, credito_debito,  -- C/D
                          codigo_barra FK, qtd_produto NUMERIC(14,3),
                          preco_custo NUMERIC(12,2), preco_venda NUMERIC(12,2),
                          valor_desconto NUMERIC(12,2), valor_acrescimo NUMERIC(12,2),
                          produto_oferta BOOL,
                          saldo_apos NUMERIC(14,3),    -- 🔴 novo (P3: saldo resultante)
                          origem)                       -- 🔴 novo (venda manual / canal X)
```
> **Regra de negócio (P1/P3):** a baixa/entrada de estoque e a linha em `produto_movimento_detalhe` acontecem **na mesma transação** no serviço de estoque Java. O saldo em `produto_estoque` é derivável, mas mantido materializado com verificação periódica de consistência. **Não** replicar a lógica via trigger/procedure de banco (a `SP_ATUALIZA_QUANTIDADE_ESTOQUE` legada é substituída pelo domínio).

<span style="color:red">🔴 `produto_estoque` não tem `reservado`: sem isso não há como cumprir R5 (reserva ao importar pedido) nem P1 anti-overselling. Adicionar `reservado` e `minimo`.</span>
<span style="color:red">🔴 Adicionar `saldo_apos` e `origem` ao detalhe para auditabilidade plena (P3).</span>
<span style="color:red">🔴 Impedir saldo negativo sem flag explícita (R2) — constraint/validação no domínio.</span>

```sql
-- inventário / contagem
produto_balanco(localizador PK, id_empresa FK, data_movimento,
                codigo_barra FK, qtd_contagem NUMERIC(14,3))
```

### 3.3.5 Módulo `pedidos` / vendas (loja física + marketplace)

```sql
-- venda da loja física (legado)
venda(id_venda PK, id_empresa FK, id_cliente FK, data_venda,
      tipo_operacao)                 -- V venda / D devolução
venda_devolucao(id_devolucao PK, id_empresa FK, data_devolucao,
                id_venda_credito, id_venda_debito,
                id_vale_mercadoria, vale_usado BOOL)
```
<span style="color:red">🔴 **Tabelas de marketplace ausentes no legado — criar (núcleo da Fase 2/R3–R7):**</span>
```sql
-- 🔴 canal de venda (ML, Shopee, ...)
canal(id_canal PK, tipo ENUM('MERCADO_LIVRE','SHOPEE','AMAZON','ECOMMERCE'),
      nome, credenciais JSONB /* cifrado AES-GCM */, status, config JSONB)
-- 🔴 de-para anúncio ↔ SKU (R6)
anuncio(id_anuncio PK, id_canal FK, codigo_barra FK, id_externo,
        preco NUMERIC(12,2),
        status_sync ENUM('OK','PENDENTE','ERRO'), ultimo_erro TEXT,
        UNIQUE(id_canal, id_externo))
-- 🔴 pedido de marketplace (fila única R5) — idempotência por (canal, id_externo)
pedido(id_pedido PK, id_canal FK, id_externo, status, comprador JSONB,
       total NUMERIC(12,2), frete NUMERIC(12,2), payload_bruto JSONB, criado_em,
       UNIQUE(id_canal, id_externo))
pedido_item(id_pedido_item PK, id_pedido FK, codigo_barra FK, id_anuncio FK,
            quantidade NUMERIC(14,3), preco_unit NUMERIC(12,2))
```
<span style="color:red">🔴 Unificar o modelo de "pedido": o legado só tem `venda` (física). Decidir se `venda` e `pedido` (canal) convergem para uma fila única de expedição (R5, estados `recebido→pago→em separação→enviado→entregue/cancelado`) ou permanecem separados com uma view unificada.</span>
<span style="color:red">🔴 A venda física (`venda`) hoje não tem itens próprios — os itens vêm de `produto_movimento_detalhe` (tipo 5). Confirmar se esse é o design desejado ou se `venda` ganha `venda_item`.</span>
<span style="color:red">🔴 Estratégia de reserva (Q2): reservar no `recebido` ou no `pago`? Bloqueante — define quando `reservado` é incrementado.</span>

### 3.3.6 Módulo `integracao` (outbox, webhooks)

<span style="color:red">🔴 **Ausente no legado — criar (P2: async + idempotente):**</span>
```sql
-- 🔴 outbox: toda mutação de estoque/preço grava evento na MESMA transação
outbox_evento(id PK, tipo, agregado_id, payload JSONB, tentativas,
              proximo_retry, processado_em, erro)
-- 🔴 idempotência de webhooks recebidos
webhook_recebido(id PK, id_canal FK, webhook_id UNIQUE, recebido_em, processado_em)
```
Worker `@Scheduled` consome `outbox_evento` com `SELECT ... FOR UPDATE SKIP LOCKED`, retry exponencial e dead-letter visível no painel (R7). Polling de segurança a cada 15 min cobre webhooks perdidos.

### 3.3.7 Módulo `financeiro` (caixa, contas, conta corrente) — <span style="color:red">🔴 escopo a confirmar</span>

O legado traz um financeiro completo que **não está no diagrama de módulos do §3.1** e tangencia o Non-goal §2.3 (PDV/frente de caixa fora do v1).

```sql
cfg_plano_contas(id_plano_contas PK /*VARCHAR(13)*/, descricao, tipo_movimento, -- C/D/N
                 inclui_dre BOOL, inclui_fluxo_caixa BOOL)
moeda(id_moeda PK, nome_moeda, perc_desconto NUMERIC(5,2), perc_acrescimo NUMERIC(5,2))  -- formas de pgto
tipo_carteira(id_carteira PK, nome_carteira, prazo_pagamento, pc_minima, pc_maxima,
              taxa_administradora NUMERIC(5,2))
moeda_detalhe(id_moeda FK, id_carteira FK, PK(id_moeda, id_carteira))
caixa_mestre(id_caixa PK, id_empresa FK, id_usuario FK, data_abertura, data_fechamento,
             saldo_inicial NUMERIC(12,2), saldo_final NUMERIC(12,2), caixa_fechado BOOL, observacoes)
caixa_detalhe(localizador PK, id_caixa FK, id_moeda FK, id_venda, id_lote_recebimento,
              id_plano_contas FK, valor NUMERIC(12,2),
              tipo_operacao,          -- RV/RP/DC/CC/TR
              credito_debito, observacoes)
contas_receber(id_conta_receber PK, id_venda FK, id_carteira FK, numero_parcela,
               data_vencimento, data_recebimento, valor_receber NUMERIC(12,2),
               valor_juros NUMERIC(12,2), valor_desconto NUMERIC(12,2),
               valor_recebido NUMERIC(12,2), documento_recebido BOOL, id_lote_recebimento)
contas_receber_detalhe(id_conta_receber FK, numero_autorizacao,
               valor_bruto NUMERIC(12,2), taxa_administradora NUMERIC(5,2),
               valor_liquido NUMERIC(12,2))
contas_pagar(localizador PK, id_empresa FK, id_fornecedor FK, id_plano_contas FK,
             nota_fiscal, numero_duplicata, data_lancamento, data_vencimento,
             data_pagamento, valor_pagar NUMERIC(12,2), valor_pago NUMERIC(12,2),
             documento_pago BOOL, observacoes)
conta_corrente(id_conta_corrente PK /*VARCHAR(20)*/, id_banco, id_agencia,
               id_empresa FK, ativo BOOL, descricao, data_abertura, saldo_inicial NUMERIC(12,2))
conta_corrente_movimento(localizador PK, id_conta_corrente FK, data_movimento,
               numero_documento, credito_debito, compensado BOOL,
               valor NUMERIC(12,2), observacao)
```
<span style="color:red">🔴 **Decisão de escopo (bloqueante para planejar fases):** o financeiro/caixa/crediário entra no v1 ou fica para depois? O §2.3 lista PDV como fora de escopo, mas R9 (venda manual com baixa) e o crediário legado sugerem financeiro parcial. Definir o subconjunto mínimo do v1 e registrar ADR. Ajustar o diagrama §3.1 para incluir (ou não) o módulo `financeiro`.</span>

### 3.3.8 Configuração global

```sql
cfg_geral(juros_crediario_dias, juros_crediario NUMERIC(5,2), multa_crediario_dias,
          multa_crediario NUMERIC(5,2), moeda_devolucao, percentual_desconto_venda NUMERIC(5,2),
          usa_variante_linha BOOL, usa_variante_coluna BOOL, nome_etiqueta)
cfg_categoria_cliente(id_categoria_cliente PK, nome_categoria)
```
<span style="color:red">🔴 `cfg_geral` no legado é tabela sem PK (singleton): no Postgres, garantir linha única (ex.: PK fixa `id = 1` + `CHECK (id = 1)`).</span>

### 3.3.9 Cadastros auxiliares

```sql
fornecedor(id_fornecedor PK, razao_social, cnpj, inscricao, endereco, numero, bairro,
           cidade, estado, cep, telefone, contato, email, id_plano_contas FK)
cliente(id_cliente PK, id_empresa FK, bloqueado BOOL, contato_crm BOOL,
        fisica_juridica,  -- F/J
        id_categoria_cliente FK, nome_cliente, nascimento, cpf,
        genero,           -- M/F/O
        endereco, numero, bairro, cidade, estado, cep, fone_fixo, fone_celular,
        email, instagram, facebook, observacao, criado_em, alterado_em)
funcionario(id_funcionario PK, nome_funcionario, fone_celular, perc_comissao NUMERIC(5,2))
```

### 3.3.10 Pendências transversais do modelo

- <span style="color:red">🔴 **Preço por canal (P1/R-nice):** o legado tem um único `preco_venda` por produto. Para markup + comissão por canal, o preço de venda por canal fica em `anuncio.preco`; documentar a regra de precificação.</span>
- <span style="color:red">🔴 **Kits/composições (P1):** um anúncio consumindo múltiplos SKUs não é suportado pelo modelo atual — prever tabela `kit_componente` se entrar no roadmap.</span>
- <span style="color:red">🔴 **Auditoria imutável (P3):** confirmar que `produto_movimento_detalhe`, `pedido`, `anuncio` (sync) cobrem "quem/quando/origem/valor anterior→novo" para estoque **e preço**. Preço hoje não tem histórico — avaliar `produto_preco_historico`.</span>
- <span style="color:red">🔴 **Cifragem de credenciais (ADR-005):** `canal.credenciais` em `JSONB` cifrado (AES-GCM, chave fora do banco).</span>

## 3.4 Contratos de API (amostra do padrão)

```
POST   /api/v1/produtos                      cria produto + variações
GET    /api/v1/estoque?sku=...               consulta saldo
POST   /api/v1/estoque/movimentacoes         entrada/ajuste {tipo, sku, qtd, motivo}
GET    /api/v1/pedidos?status=EM_SEPARACAO   fila unificada
POST   /api/v1/pedidos/{id}/enviar           marca enviado + notifica canal
POST   /api/v1/canais/{tipo}/conectar        inicia OAuth (retorna URL)
GET    /api/v1/canais/{id}/saude             status, fila de erros
POST   /api/v1/anuncios/{id}/reprocessar     re-sync manual
POST   /webhooks/mercadolivre                recepção de notificações (público, validado)
```

Convenções: versionamento no path; erros no formato Problem Details (RFC 9457); paginação por cursor em listagens; todos os endpoints documentados no OpenAPI antes da implementação (contrato faz parte da spec da feature).

## 3.5 Docker / ambiente

```yaml
# docker-compose.yml (dev)
services:
  db:
    image: postgres:18
    environment:
      POSTGRES_DB: erp
      POSTGRES_USER: erp
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes: [pgdata:/var/lib/postgresql/data]
    ports: ["5432:5432"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U erp"]
      interval: 5s
  api:
    build: ./api          # eclipse-temurin:25-jre
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/erp
    depends_on:
      db: { condition: service_healthy }
    ports: ["8080:8080"]
  web:
    build: ./web          # node:22 build → nginx
    ports: ["5173:80"]
volumes:
  pgdata:
```

Produção: mesma composição + backup diário (`pg_dump` ou WAL-G), segredos via variáveis de ambiente/secret manager, TLS no proxy reverso (Caddy/Traefik/nginx).

## 3.6 Requisitos não funcionais

- **Desempenho:** p95 < 300 ms nos endpoints do painel; importação de pedido processada em < 10 s do webhook.
- **Confiabilidade:** retry exponencial (1 min → 2 → 4… máx 1 h) com dead-letter visível no painel (R7); polling de segurança a cada 15 min cobre webhooks perdidos.
- **Segurança:** credenciais de canal cifradas em repouso (AES-GCM, chave fora do banco); LGPD — dados de comprador retidos só o necessário; rate limit nos webhooks; validação de assinatura dos webhooks quando o canal oferecer.
- **Backup/restauração:** RPO 24 h (v1), teste de restore mensal.

---

# 4. Fases e Tasks (macro)

**Fase 0 — Fundação (1–2 semanas):** repositório mono (api/ web/), Docker Compose, CI (build + testes + Testcontainers), esqueleto Spring Boot com módulos, Flyway, auth JWT, layout base do React.

**Fase 1 — Núcleo do ERP (3–4 semanas):** R1, R2, R8, R9 — catálogo, estoque, movimentações, venda manual. *Gate:* todos os critérios de aceitação com teste verde.

**Fase 2 — Primeira integração (3–4 semanas):** R3 (Mercado Livre completo) + R6 + R7 + R5. WireMock simulando ML em testes; conta de teste ML para homologação.

**Fase 3 — Segunda integração (2 semanas):** R4 (Shopee) reutilizando a interface `CanalDeVenda` — o esforço desta fase valida a arquitetura de adapters.

**Fase 4 — Piloto:** 2–3 lojistas reais, métricas da seção 2.6, correções. Só então P1.

---

# 5. Template — Spec de Feature (usar para cada feature nova)

```markdown
# Spec: [nome da feature]                    Status: Rascunho|Aprovada|Implementada
Autor: · Data: · Módulo(s): · Fase:

## Problema
[2–3 frases: quem sofre, com que frequência, custo de não resolver]

## Solução proposta (o quê, não o como)
[comportamento esperado do ponto de vista do usuário]

## User stories
- Como [persona], quero [capacidade] para [benefício].

## Critérios de aceitação (viram testes)
- Dado [contexto], quando [ação], então [resultado].
- [ ] caso feliz  [ ] erro  [ ] borda  [ ] o que NÃO deve acontecer

## Impacto no contrato de API
[endpoints novos/alterados — trecho OpenAPI]

## Impacto no banco
[migration Flyway prevista]

## Impacto nas integrações
[algum adapter muda? novo evento no outbox?]

## Non-goals desta feature
## Questões abertas (responsável + bloqueante?)
## Métrica de sucesso
```

# 6. Template — ADR (Architecture Decision Record)

```markdown
# ADR-NNN: [título da decisão]              Status: Proposto|Aceito|Substituído por ADR-XXX
Data: · Decisores:

## Contexto
[forças em jogo: requisito, restrição, prazo]

## Decisão
[o que foi decidido, em uma frase afirmativa]

## Alternativas consideradas
1. [alternativa] — prós / contras
2. ...

## Consequências
Positivas: · Negativas/dívidas: · Gatilho de revisão: [métrica ou evento que faria rever]
```

ADRs já previstos: ADR-001 monolito modular; ADR-002 outbox sobre Postgres sem broker; ADR-003 biblioteca de UI; ADR-004 estratégia de reserva de estoque (depende de Q2); ADR-005 cifragem de credenciais.

# 7. Template — Task

```markdown
## TASK-NNN: [verbo + objeto]         Spec: [link] · Estimativa: P|M|G · Dono:
Descrição: [1–3 frases]
Definição de pronto:
- [ ] critérios de aceitação da spec cobertos por testes
- [ ] migration aplicada e reversível
- [ ] OpenAPI atualizado
- [ ] sem lint/erros; revisado por 1 pessoa
Dependências: TASK-XXX
```

---

## Checklist de aprovação de uma spec (gate antes de codar)

- [ ] Problema fundamentado (dor real do lojista, não suposição)
- [ ] Critérios de aceitação testáveis e sem palavras ambíguas ("rápido", "intuitivo")
- [ ] Non-goals explícitos
- [ ] Contrato de API e migration esboçados
- [ ] Nenhuma violação da Constituição (P1–P7)
- [ ] Questões bloqueantes respondidas
