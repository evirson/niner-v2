# Modelo Spec-Driven — ERP de Varejo com Integração a Marketplaces

**Stack:** React (frontend) · Java 25 + Spring Boot 4.x (API) · PostgreSQL 18 (Docker) · Docker Compose
**Versão do documento:** 1.0 · **Status:** Rascunho para preenchimento

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
| Persistência | Spring Data JPA + Hibernate; Flyway para migrations | Migrations versionadas em `db/migration` |
| Banco | PostgreSQL 18 (imagem oficial `postgres:18`) | `JSONB` para payloads brutos de integração |
| Frontend | React 19 + Vite + TypeScript | TanStack Query para dados; React Router |
| UI | shadcn/ui ou Mantine | decidir por ADR |
| Auth | Spring Security + JWT (access curto + refresh) | |
| Docs API | springdoc-openapi (OpenAPI 3.1) | Contrato gerado é parte da spec |
| Observabilidade | Spring Actuator + Micrometer; logs JSON | |
| Testes | JUnit 5, Testcontainers (Postgres real), WireMock (APIs de marketplace), Playwright (e2e crítico) | |

## 3.3 Modelo de dados (núcleo)

```sql
produto(id, nome, descricao, categoria_id, marca, ativo, criado_em)
variacao(id, produto_id, sku UNIQUE, ean, atributos JSONB, custo NUMERIC(12,2))
estoque(variacao_id PK, disponivel INT, reservado INT, minimo INT, atualizado_em)
movimentacao_estoque(id, variacao_id, tipo, quantidade, saldo_apos,
                     origem, referencia_id, usuario_id, criado_em)  -- imutável (P3)
canal(id, tipo ENUM('MERCADO_LIVRE','SHOPEE','AMAZON','ECOMMERCE',...),
      nome, credenciais JSONB cifrado, status, config JSONB)
anuncio(id, canal_id, variacao_id, id_externo, preco NUMERIC(12,2),
        status_sync ENUM('OK','PENDENTE','ERRO'), ultimo_erro TEXT,
        UNIQUE(canal_id, id_externo))
pedido(id, canal_id, id_externo, status, comprador JSONB, total NUMERIC(12,2),
       frete NUMERIC(12,2), payload_bruto JSONB, criado_em,
       UNIQUE(canal_id, id_externo))  -- idempotência
pedido_item(id, pedido_id, variacao_id, anuncio_id, quantidade, preco_unit)
outbox_eventos(id, tipo, agregado_id, payload JSONB, tentativas,
               proximo_retry, processado_em, erro)
usuario(id, email UNIQUE, senha_hash, papel, ativo)
```

Regras: baixa de estoque e criação de `movimentacao_estoque` na mesma transação; `disponivel` derivável mas mantido materializado com verificação periódica de consistência.

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
