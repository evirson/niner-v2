# Modelo Spec-Driven — ERP de Varejo com Integração a Marketplaces (SaaS multi-tenant)

**Stack:** React (frontend) · Java 25 + Spring Boot 4.x (API) · PostgreSQL 18 (Docker, banco `niner_db`) · Docker Compose
**Versão do documento:** 2.0 · **Status:** Rascunho para preenchimento

> **Pivô v2.0 — SaaS multi-tenant:** a partir desta versão o produto é um **SaaS por assinatura** (marca **Niner**, banco `niner_db`), com muitos lojistas (**tenants**) isolados na mesma instância, site público de aquisição + trial, backoffice de gestão de tenants e cobrança recorrente das assinaturas. Isso **supera** o non-goal de multiempresa da v1 (§2.3) e introduz os princípios **P8/P9** (§1) e o **Plano de Controle × Plano do Inquilino** (§3.1). Conceitos-chave de vocabulário: *assinatura/plano/trial/mensalidade/gateway* = **plataforma** (Vetor cobra o lojista); *caixa/crediário/contas a pagar-receber/conta corrente da loja* = **financeiro do lojista** (§3.3.7). Nunca conflar os dois.

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
- **P8 — Isolamento de tenant é inviolável.** Nenhuma query cruza a fronteira de um tenant. O isolamento é imposto pelo **banco** (Postgres RLS com `FORCE ROW LEVEL SECURITY`), não apenas pelo código de aplicação. A role de aplicação (`niner_app`) **nunca** tem `BYPASSRLS`; migrations rodam com uma role dona separada (`niner_owner`). Toda tabela de dados de lojista carrega `id_tenant` e tem política RLS; as tabelas do módulo `plataforma` (control-plane) são a exceção explícita e documentada. Todo caminho **sem requisição** (worker/outbox/webhook) estabelece o `TenantContext` antes de tocar dados de domínio.
- **P9 — Separação de planos (control-plane × tenant).** Dados da plataforma (assinatura, faturas, cobrança) e dados do lojista nunca compartilham a mesma política de acesso. Staff da plataforma só acessa dados de um tenant via **impersonação auditada** (trilha imutável, P3). O módulo `plataforma` é o único que enxerga cross-tenant.

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

- **Emissão fiscal do lojista (NF-e/NFC-e das vendas):** integrar com emissor terceiro via API é P2; construir emissor próprio nunca. **Exceção (v2.0):** a **NFS-e da própria assinatura** (Vetor→lojista) é obrigação da plataforma, não do produto — ver decisão D6 no plano de negócio.
- **PDV/frente de caixa:** o v1 registra vendas da loja física por lançamento manual/importação; PDV completo é produto separado.
- **Multi-CNPJ dentro de um mesmo tenant:** um tenant = **um CNPJ** no v1. Um assinante com **N empresas/CNPJs** é recurso de plano / P2 (a arquitetura já nasce `tenant 1:N empresa`, ver §3.1.1). *(Isto substitui o antigo non-goal "multi-empresa"; a modelagem legada mantém `id_empresa` — Q6 fechada.)*
- **Logística própria (etiquetas, rastreio):** usa-se a logística do próprio marketplace (ML Envios, etc.). Gestão de transportadora própria fica fora.
- **BI avançado:** v1 entrega relatórios operacionais básicos; dashboards analíticos ficam para v2.

> **Agora CORE (deixaram de ser non-goal com o pivô SaaS v2.0):** **multi-tenancy** com isolamento entre assinantes (R10); **site público** de aquisição + **trial** self-service (R11–R12, R20); **cobrança recorrente** da assinatura / faturamento da plataforma (R14, R16); **backoffice** de gestão de tenants (R17). Ver §2.5 e o `docs/PLANO-DE-NEGOCIO.md`.

## 2.4 Personas e User Stories

**Plano do Inquilino (usuários do ERP — dados de um tenant):**
**Persona A — Dono/gestor da loja** (decide preço, compra estoque)
**Persona B — Operador** (separa pedidos, dá baixa, cadastra produto)

**Plano de Controle (usuários da plataforma Niner / Vetor):**
**Persona C — Super-admin da plataforma** (gerencia todos os tenants, planos, limites, staff)
**Persona D — Suporte / Customer Success** (consulta tenants, impersona com auditoria, acompanha ativação/saúde)
**Persona E — Financeiro/Cobrança da plataforma** (faturas, inadimplência, reembolsos, conciliação com gateway)
**Persona F — Assinante/comprador** (o dono da loja na ótica de aquisição/billing: faz signup, escolhe plano, gerencia a própria assinatura e vê faturas)

Prioridade decrescente (operação do ERP — Personas A/B):

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

**Aquisição e assinatura (Persona F — site público):**

11. Como **visitante**, quero criar uma conta e começar um trial em minutos, **sem cartão**, para avaliar o produto com meus próprios dados.
12. Como **assinante**, quero escolher um plano e concluir o pagamento (cartão/PIX/boleto) para ativar minha conta ao fim do trial.
13. Como **assinante**, quero fazer upgrade/downgrade ou cancelar minha assinatura sozinho, com efeito e proration claros.
14. Como **assinante**, quero ver minhas faturas, o método de pagamento e o status da assinatura.

**Operação da plataforma (Personas C/D/E — backoffice):**

15. Como **super-admin**, quero listar/buscar tenants e ver plano, status, uso vs. limites e saúde das integrações.
16. Como **suporte**, quero acessar (impersonar) um tenant com registro de auditoria para diagnosticar um problema.
17. Como **financeiro**, quero ver faturas, inadimplentes e disparar/acompanhar a régua de cobrança (dunning).
18. Como **super-admin**, quero suspender ou reativar um tenant (por inadimplência ou pedido do cliente) sem perder dados.

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

#### Must-Have (P0) — SaaS multi-tenant (v2.0)

| ID | Requisito | Critérios de aceitação (Dado/Quando/Então) |
|----|-----------|-------------------------------|
| R10 | **Multi-tenancy com isolamento de dados por tenant** | Dado dois tenants T1 e T2, quando um usuário de T1 consulta qualquer recurso (produto, estoque, pedido, financeiro), então **nunca** retorna dado de T2; o corte por `id_tenant` é imposto por RLS no banco (P8), não só pelo código. |
| R11 | **Site público de aquisição** (landing, planos, preços) | Dado um visitante, quando acessa a página de planos, então vê os 3 tiers com limites e preços e um CTA de "iniciar avaliação". |
| R12 | **Signup self-service + criação de tenant + trial** | Dado um visitante que informa e-mail/senha/nome da loja, quando confirma, então é criado um tenant `TRIAL` (expira em 60 dias) e o usuário vira `ADMIN` desse tenant; e-mail duplicado **no mesmo tenant** é rejeitado. |
| R13 | **Catálogo de planos e limites (entitlements)** | Dado um plano com limites (canais, SKUs, usuários, pedidos/mês), quando um tenant é associado a ele, então os limites ficam disponíveis para enforcement (R19). |
| R14 | **Checkout e criação de assinatura (adapter de gateway)** | Dado um tenant em trial que escolhe um plano e informa pagamento, quando o gateway aprova, então o tenant vira `ATIVA`, a assinatura é criada com ciclo (mensal/anual) e a 1ª fatura é registrada. *(Gateway real a definir — D3; v1 usa adapter com cobrança manual/registro.)* |
| R15 | **Autogestão da assinatura pelo lojista** | Dado um tenant ativo, quando o ADMIN faz upgrade, então limites novos valem imediatamente com cobrança proporcional; quando faz downgrade, então vale no próximo ciclo e o uso atual é validado contra os novos limites; quando cancela, então mantém acesso até o fim do ciclo pago. |
| R16 | **Cobrança recorrente + inadimplência + suspensão (dunning)** | Dado uma fatura vencida não paga, quando decorre a régua (D+1, D+3, D+7 de tentativas/avisos), então o tenant é notificado e, ao esgotar, entra em `SUSPENSA` (somente leitura, sync pausado); ao pagar, é reativado sem perda de dados. |
| R17 | **Backoffice de gestão de tenants (staff)** | Dado um staff autenticado, quando abre o backoffice, então lista/filtra tenants por status/plano/uso e abre a ficha de um tenant (plano, faturas, saúde, uso vs. limite). |
| R18 | **Papéis de staff da plataforma** (super-admin/suporte/financeiro) | Dado um usuário `SUPORTE`, quando tenta editar dados de cobrança, então é negado (403); papéis de staff são **separados** do RBAC `ADMIN`/`OPERADOR` do tenant (R8) e vivem em `plataforma.staff`. |
| R19 | **Enforcement de limites do plano** | Dado um tenant no limite de canais/usuários/SKUs, quando tenta exceder, então a ação é bloqueada (Problem Details) com CTA de upgrade; para **pedidos/mês** o pedido **é sempre importado** e o excedente gera alerta/gatilho de upgrade — **nunca descarta pedido** (preserva O1/O4). |
| R20 | **Ciclo de vida do trial** | Dado um trial expirado sem conversão, quando a data passa, então o tenant vai a modo **leitura/graça** por 7 dias (dados preservados, sync pausado); ao assinar dentro da graça, retoma sem reprovisionar. |
| R21 | **Impersonação auditada pelo suporte** | Dado um suporte acessando um tenant, quando entra em modo impersonação, então gera registro imutável (quem, quando, tenant, duração) visível ao super-admin (P3/P9). |
| R22 | **Ajuda de tela (manual de operação) + vídeo em TODA tela** | Dado qualquer tela do produto (ERP/backoffice/site), quando o usuário aciona a ajuda, então vê o manual de operação da tela (objetivo + passo a passo) e um acesso a vídeo explicativo (ou "em breve" se o vídeo ainda não existir). Nenhuma tela sem ajuda (§3.7.1). |

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

**Funil SaaS (v2.0 — detalhado no `docs/PLANO-DE-NEGOCIO.md`):** conversão visitante→signup; signup→trial ativo; **trial→paid (meta ≥ 20%)**; % de trials no aha moment ≤ 7 dias (meta ≥ 60%); MRR/ARR; churn de receita mensal (meta ≤ 4%); NRR (meta ≥ 100%); inadimplência mensal (meta ≤ 5%) e recuperação por dunning (meta ≥ 50% das falhas de cartão).

## 2.7 Questões Abertas

| # | Questão | Responsável | Bloqueante? |
|---|---------|-------------|-------------|
| Q1 | Shopee exige empresa registrada no programa de parceiros — prazo de aprovação? | Negócio | Sim (para R4) |
| Q2 | ✅ **Fechada (2026-07-10):** reservar no **`recebido`** — o pedido importado já incrementa `produto_estoque.reservado` (alinha R5 + P1 zero-overselling), com **expiração** que devolve a reserva de pedidos que não pagam no prazo. Ver §3.3.5 / ADR-004. | Produto + Eng | — |
| Q3 | Limite de rate das APIs (ML: ~X req/s por app) comporta quantos lojistas por app credential? | Engenharia | Não |
| Q4 | Precisamos de LGPD DPA com os marketplaces para dados de comprador? | Jurídico | Não |
| Q5 | ✅ **Fechada (2026-07-10), revisada em 2026-07-16 (duas vezes):** o módulo `financeiro` do lojista **entra no v1** — crediário (`tipo_carteira`/`moeda`/`contas_receber`), caixa (`caixa_mestre`/`caixa_detalhe`, V025) e `contas_pagar` (V026) antecipados da Fase 2. Só `conta_corrente(_movimento)` continua **fora do v1**. Ver §3.3.7 / ADR-010 (revisado) / ADR-012. | Produto + Eng | — |
| Q6 | ✅ **Fechada (v2.0):** manter `id_empresa` **e** adicionar `id_tenant` como chave de isolamento. Relação `tenant 1:N empresa` (1:1 no v1). Ver §3.1.1 / ADR-006. | Produto + Eng | — |
| Q7 | ✅ **Fechada (2026-07-10):** **separar** `sku` interno (obrigatório, único, chave do domínio) de `ean` (GTIN real, nullable, único quando preenchido). EAN exigido só na **publicação** em canal, não no cadastro. Ver §3.3.3. | Produto + Eng | — |

**Decisões de negócio do SaaS (v2.0) — detalhadas em `docs/PLANO-DE-NEGOCIO.md`:**

| # | Decisão | Situação |
|---|---------|----------|
| D1 | Preços dos 3 planos (Essencial/Profissional/Escala) e desconto anual | 🔴 Aberta (placeholders R$ 99 / 249 / 599) |
| D2 | Trial: **60 dias**, **sem cartão**, expondo o plano Profissional (revê a escolha anterior de 14 dias, 2026-07-11) | ✅ Decidida |
| D3 | Gateway de cobrança (PIX/boleto/cartão recorrente) | 🔴 **Adiada** — modelar via adapter abstrato; cobrança manual no início |
| D4 | Multi-CNPJ por tenant como recurso de plano / P2 | ✅ Decidida (1 CNPJ/tenant no v1) |
| D5 | Nome comercial "Niner" (DB `niner_db`) + domínio do site | 🔴 Confirmar |
| D6 | NFS-e da assinatura (Vetor→lojista): emissor/município | 🔴 Aberta |
| D7 | Overage de pedidos/mês: nunca descartar, só gatilhar upgrade | ✅ Recomendada (R19) |
| D8 | Régua de dunning (avisos D+1/D+3/D+7, suspensão ~15d, graça 7d) | 🔴 Confirmar |
| D9 | Metas numéricas (MRR, trial→paid, churn) para GA+6m | 🔴 Aberta |
| D10 | Estado `INADIMPLENTE`: ERP em modo leitura/aviso vs bloqueio total | 🔴 Aberta (regra do gate de login) |

---

# 3. Plano Técnico

## 3.1 Arquitetura (monolito modular)

```
 3 apps React (Vite) — cada um lê a base-URL da API em RUNTIME (não no build):
 ┌───────────────┐   ┌───────────────┐   ┌───────────────┐
 │ site/ público │   │ web/ ERP      │   │ admin/ backof.│
 │ (aquisição/   │   │ (lojista)     │   │ (plataforma)  │
 │  trial)       │   │               │   │               │
 └──────┬────────┘   └──────┬────────┘   └──────┬────────┘
   /api/publico/**     /api/v1/**          /api/admin/**
        │                   │                   │
        ▼                   ▼                   ▼
 ┌──────────────────────────────────────────────────────────┐
 │  API Java 25 · Spring Boot 4.x  (STATELESS — N instâncias)│
 │  3 SecurityFilterChain (publico / tenant / plataforma)   │
 │  módulos: catalogo/ estoque/ pedidos/ precos/ canais/    │
 │           identidade/ integracao/{mercadolivre,shopee}   │
 │           plataforma/  ← control-plane (financeiro: Fase 2)│
 │  outbox + scheduler (retry/poll) · TenantContext + RLS   │
 └──────────────────────────┬───────────────────────────────┘
   webhooks (ML/Shopee,      │ JDBC (role niner_app, SEM BYPASSRLS)
   gateway de cobrança) ─────┘
                      ┌──────▼──────┐
                      │ PostgreSQL18│  banco niner_db (Docker)
                      │  RLS FORCE  │
                      └─────────────┘
```

**Topologia (ADR-007).** **Uma** API (monólito modular, P6) expõe **três superfícies** por prefixo de path, cada uma com seu `SecurityFilterChain`: `/api/publico/**` (site: signup, checkout, trial — sem auth ou auth leve), `/api/v1/**` (ERP do tenant — JWT de tenant, RLS ativo), `/api/admin/**` (backoffice da plataforma — JWT de staff, opera em `plataforma.*`). Três apps React independentes: **`site/`** (público), **`web/`** (ERP do lojista) e **`admin/`** (backoffice). A separação pedida ("api e front separados") é de **superfície e front-end**, não de processo. A **API é stateless** (JWT, sem sessão/afinidade) e cada front lê a **base-URL da API em runtime** (arquivo de config/env servido, não embutido no bundle) — assim é possível rodar **2 servidores com 2 instâncias** da API e trocar o endereço para manutenção/failover. *Gatilhos para separar o control-plane em serviço próprio depois: janela de manutenção independente, compliance de dados de cartão em rede isolada, ou scale-out do ERP que torne caro arrastar a plataforma junto.*

> ✅ **Q5 fechada (2026-07-10):** o módulo **`financeiro`** do lojista (caixa, crediário, contas a pagar/receber, conta corrente) **não entra no v1** (fora de escopo, junto com PDV — §2.3). Fica para a **Fase 2** (crediário priorizado). Por isso não aparece no diagrama. Ver §3.3.7 / ADR-010.

Decisões-chave (registrar cada uma como ADR — template na seção 6):

- **Monolito modular** com módulos por contexto de domínio; comunicação entre módulos por interfaces Java + eventos de domínio internos (Spring events ou Spring Modulith). Justificativa: time pequeno, deploy único, P6.
- **Padrão de integração:** cada marketplace tem um *adapter* isolado (anti-corruption layer) que implementa a interface comum `CanalDeVenda` (`publicarAnuncio`, `atualizarEstoque`, `atualizarPreco`, `importarPedidos`, `confirmarEnvio`). O domínio nunca conhece payloads do ML/Shopee.
- **Outbox pattern:** mutações de estoque/preço gravam evento na tabela `outbox_eventos` na mesma transação; um worker (Spring `@Scheduled` no v1; fila dedicada só se necessário) publica para os canais com retry exponencial e dead-letter.
- **Idempotência:** pedidos importados usam chave natural `(canal, id_externo)` com constraint única; webhooks processados registram `webhook_id` recebido.
- **Sem broker externo no v1** (sem Kafka/RabbitMQ): Postgres como fila via outbox + `SELECT ... FOR UPDATE SKIP LOCKED`. ADR explícito; revisitar se throughput exigir.

### 3.1.1 Isolamento de tenant (multi-tenancy) — ADR-006

**Estratégia: banco único + coluna `id_tenant` + Postgres RLS** (`FORCE ROW LEVEL SECURITY`). Escolhida sobre schema-per-tenant e db-per-tenant por aderência ao P6 (uma instância Postgres, uma migração) e ao ticket baixo do público-alvo (muitos lojistas pequenos). Isolamento imposto pelo kernel do banco = defesa em profundidade além do código (P8).

- **Modelo tenant → empresa:** `tenant (conta_assinante)` **1:N** `empresa`. No v1 é **1:1** (uma assinatura = um CNPJ), mas já nasce 1:N para o multi-CNPJ futuro (P2) sem refazer schema. Fecha **Q6**: mantém-se `id_empresa` (granularidade de negócio: loja/depósito/CNPJ) **e** adiciona-se `id_tenant`.
- **`id_tenant SMALLINT` em toda tabela de domínio** (desnormalização deliberada: RLS usa um predicado simples e indexável, sem join na política). `empresa.id_tenant` FK → `plataforma.tenant`. `SMALLINT` (não `BIGINT`) por decisão explícita de 2026-07-16 — teto de 32.767 tenants, considerado suficiente para o público-alvo (micro/pequeno varejo); revisitar se o funil comercial aproximar desse limite.
- **Resolução por requisição:** login emite **JWT com claim `tid`** (id_tenant) + `sub` + `roles` (+ `emp`). Um `OncePerRequestFilter` (`TenantFilter`) valida a assinatura ativa e popula o **`TenantContext`** (**`ScopedValue`** no Java 25 — implementado na Fase 0). Um transaction manager dedicado (`TenantAwareTransactionManager`, sobre `JdbcTransactionManager`) executa **`select set_config('app.id_tenant', :tid, true)`** (equivalente parametrizável e injection-safe de `SET LOCAL`) logo após o início da transação; as políticas RLS usam `current_setting('app.id_tenant')::smallint` (via `plataforma.tenant_atual()`, V002). O valor é local à transação — some no commit/rollback, sem vazamento entre conexões do pool. **A garantia dura de isolamento é o RLS no banco** (P8); não há filtro de ORM (ver §3.2 — persistência é Spring Data JDBC, não JPA).
- **Roles Postgres:** aplicação roda como **`niner_app`** (sem `BYPASSRLS`, não é dona das tabelas); migrations rodam como **`niner_owner`**.
- **Caminhos sem requisição (P8):** `outbox_evento` carrega `id_tenant`; o worker fica **fora** do RLS de tenant (é infraestrutura da plataforma) e, ao despachar cada evento, estabelece o `TenantContext` a partir de `evento.id_tenant` antes de aplicar efeitos de domínio. Webhook de marketplace resolve o tenant pelo `id_canal` que recebeu a notificação; webhook do gateway de cobrança age em `plataforma.*` (global).
- **Módulo `plataforma` (control-plane)** é o **único** que enxerga cross-tenant; suas tabelas são **globais** e **não** entram no RLS de tenant (P9). Ver §3.3.11.
- **FKs entre tabelas de domínio são sempre compostas `(id_tenant, id_x)` — não simples
  (2026-07-16, achado real de teste, não teórico).** O Postgres **não aplica RLS na
  checagem de integridade referencial**: com `FOREIGN KEY (id_empresa) REFERENCES empresa
  (id_empresa)`, um tenant conseguia inserir um `produto_movimento_detalhe` com `id_tenant`
  próprio mas `id_empresa`/`id_variacao` de **outro tenant** — e a trigger de estoque
  (§3.3.4) materializava esse cruzamento como saldo fabricado em `produto_estoque`. O RLS
  em `USING`/`WITH CHECK` só protege consultas/gravações **diretas**; não protege o que uma
  FK "enxerga" como existente. Fix: toda tabela referenciada ganhou `UNIQUE (id_tenant,
  id_<pk>)` e toda FK entre tabelas de domínio virou `FOREIGN KEY (id_tenant, id_x)
  REFERENCES tabela (id_tenant, id_x)` — ~34 constraints em ~17 tabelas (V014–V022).

## 3.2 Stack e versões

| Camada | Escolha | Observação |
|--------|---------|------------|
| Linguagem | Java 25 (LTS) | Virtual threads habilitadas (`spring.threads.virtual.enabled=true`) para I/O de integrações |
| Framework | Spring Boot 4.x / Spring Framework 7 | Suporte oficial a Java 25 |
| Persistência | **Spring Data JDBC** (não JPA/Hibernate); Flyway para migrations | Decisão de 2026-07-10: JDBC é mais explícito e previsível para o padrão RLS + `SET LOCAL` por transação (sem proxy/lazy-loading/1º nível de cache do Hibernate a mascarar o contexto). Migrations versionadas em `db/migration`. Auditoria de datas via `DEFAULT now()` na coluna + preenchimento no serviço de domínio (na mesma transação, P3) — não `@CreatedDate` |
| Banco | PostgreSQL 18 (imagem oficial `postgres:18`) | `JSONB` para payloads brutos de integração. Schema legado em `db/*.txt` é **Firebird** e serve de referência — ver §3.3.1 para as regras de conversão |
| Frontend | `web`/`admin`: React 19 + Vite + TS (TanStack Query, React Router). `site`: **Astro (SSG)** para SEO (ADR-011) | Fronts leem a base-URL da API em runtime (não embutida) |
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
| `INTEGER ... GENERATED BY DEFAULT AS IDENTITY` (PK) | `id_tenant`: `SMALLINT GENERATED ALWAYS AS IDENTITY`; demais PKs surrogate: `INTEGER GENERATED ALWAYS AS IDENTITY` | decisão de 2026-07-16 (revê a orientação anterior de `BIGINT` genérico): `id_tenant` é a raiz do isolamento e todo FK para ele usa o mesmo tipo (`SMALLINT`); as demais PKs (`id_produto`, `id_pedido` etc.) usam `INTEGER`. FK sempre com o **mesmo tipo exato** da PK referenciada. |
| `CREATE GENERATOR GT_*` + `gen_id()` (arquivo `100_GERADORES.txt`) | `IDENTITY`/`SEQUENCE` | `id_venda`, `id_devolucao`, `id_transferencia`, `id_movimento`, `id_lote_recebimento` passam a IDENTITY. `id_transferencia` continua **sem FK** — número vindo de um gerador externo, proposital (não existe tabela de transferência ainda). |
| `CREATE PROCEDURE` PSQL (`SP_ATUALIZA_QUANTIDADE_ESTOQUE`) | ✅ **Resolvido (2026-07-16):** trigger `trg_produto_movimento_detalhe_estoque` | orientação anterior (mover para o domínio Java, nunca trigger) **revertida**. A procedure legada não é recriada como objeto separado — a lógica inteira vive na função de trigger `fn_atualiza_estoque_movimento()` (V019), acionada por `INSERT`/`UPDATE`/`DELETE` em `produto_movimento_detalhe`. Ver §3.3.4. |
| `CREATE TRIGGER` de auditoria de data (`TG_*_INSERT_UPDATE`) | coluna `TIMESTAMPTZ DEFAULT now()` + preenchimento no serviço de domínio (mesma transação, P3) | remove os triggers `102_TRIGGERS.txt`. Persistência é Spring Data JDBC (§3.2), não JPA auditing |
| `TIMESTAMP` | `TIMESTAMPTZ` | armazenar com fuso (UTC) |
| `LOCALTIMESTAMP` | `now()` / relógio da app | |
| `IMAGEM VARCHAR(200)` (caminho de arquivo) | manter URL/chave de object storage | não gravar binário no banco |

**Convenções adicionais consolidadas nas migrations (2026-07-16):**
- **Sem `ON DELETE CASCADE`** em nenhuma FK do domínio: apagar um registro com dependentes falha por violação de FK — a aplicação decide explicitamente o que fazer com os dependentes, nunca o schema por conta própria.
- **E-mail de login sempre único por índice case-insensitive** (`UNIQUE INDEX ... (id_tenant, lower(email))` em `usuario`; `UNIQUE INDEX ... (lower(email))` em `plataforma.staff`) — evita duas contas coexistirem só por causa de maiúscula/minúscula.
- **`produto_movimento_mestre` imutável ao nível de banco:** `niner_app` não recebe `UPDATE`/`DELETE` (V024), mesmo tratamento já dado a `plataforma.impersonacao_log` (V011). `produto_movimento_detalhe` **não** entra mais nessa regra desde 2026-07-16 — é corrigível/excluível, e a trigger `trg_produto_movimento_detalhe_estoque` (V019) mantém `produto_estoque.qtd_estoque` em dia a cada mudança (ver §3.3.4).
- **Bootstrap de roles (`db/bootstrap/00_roles.sql`):** `ALTER DATABASE ... OWNER TO niner_owner` não dá a `niner_owner` privilégio de `CREATE` no schema `public` (schema pré-existente, dono é o superusuário de bootstrap da imagem) — como as migrations de domínio (V013+) criam objetos sem prefixo de schema (portanto em `public`), o bootstrap precisa de `GRANT CREATE ON SCHEMA public TO niner_owner` explícito, senão o Flyway falha em V013 rodando como `niner_owner` (`docker compose run --rm flyway`).

### 3.3.2 Módulo `identidade` (auth, empresas, permissões)

```sql
empresa(id_empresa PK, id_tenant FK -> plataforma.tenant, codigo_empresa,  -- nº sequencial por tenant, só p/ exibir em relatório (01, 02...); UNIQUE(id_tenant, codigo_empresa)
        razao_social, cnpj, inscricao,
        endereco, numero, bairro, cidade, estado, cep, telefone, email, imagem_relatorio,
        cfg_nome_etiqueta)  -- NOT NULL: texto/modelo impresso na etiqueta de produto (2026-07-16)
-- usuário do TENANT (lojista). email é único POR TENANT (case-insensitive), não global.
usuario(id_usuario PK, id_tenant FK, nome_usuario, email, senha_hash,
        ativo BOOL, administrador BOOL,          -- papel ADMIN/OPERADOR deriva de administrador (R8)
        UNIQUE INDEX(id_tenant, lower(email)))   -- mesmo padrão de plataforma.staff (§3.3.11)
usuario_rotina(id_usuario FK, nome_rotina, PK(id_usuario, nome_rotina))  -- permissões finas legadas
```
> **Duas populações de usuário separadas (P9, R18):** os usuários do lojista ficam em `usuario` (com `id_tenant`, sujeitos a RLS). Os usuários da **plataforma** (staff Vetor: `SUPER_ADMIN`/`SUPORTE`/`FINANCEIRO`) ficam em `plataforma.staff` (global, §3.3.11). **JWTs distintos por `aud`** (`tenant` × `plataforma`): a chain de `/api/v1/**` rejeita token de staff e a de `/api/admin/**` rejeita token de tenant.

**Signup público atômico** (`POST /api/publico/assinar`, R12): numa **única transação** cria `tenant (status=TRIAL)` + `assinatura (status=TRIAL, trial_expira_em = now()+60d)` + linha inicial em `plataforma.uso_tenant` + `empresa (id_tenant, codigo_empresa=1, cfg_nome_etiqueta=<modelo padrão>)` + `cfg_geral (id_tenant)` + **7 linhas de `moeda`** (formas de recebimento padrão, §3.3.7/V025) + primeiro `usuario` (ADMIN, `senha_hash`); dispara e-mail de boas-vindas via **outbox**. A atomicidade é trivial por tudo estar no mesmo banco/monólito (mais um ponto a favor da topologia de uma API). `codigo_empresa`/`cfg_nome_etiqueta` (`empresa`, `NOT NULL` sem `DEFAULT`, V014) precisam de valor explícito no insert — bug encontrado e corrigido em 2026-07-16 ao recriar o banco do zero pela primeira vez desde que essas colunas viraram obrigatórias.

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
-- produto_barra é a VARIAÇÃO. Q7 (fechada): sku interno (chave) + ean opcional (GTIN real).
produto_barra(id_variacao PK,                      -- surrogate; sku é a chave de negócio, não a PK física
              sku,                                  -- identificador INTERNO, obrigatório, único por tenant (ex-codigo_barra); imprimível como código de barras na loja
              ean,                                  -- GTIN real (EAN-13/UPC), NULLABLE; UNIQUE quando preenchido
              id_produto FK, id_variante_linha FK, id_variante_coluna FK)
```
> **Mapeamento p/ a spec:** o par (`produto` + `produto_barra`) implementa `produto`+`variacao` do PRD (R1). A chave da variação é o **`sku`** (interno, papel do antigo `codigo_barra`; estrutura grupo+sequencial+dígito de `033_PRODUTOS_BARRA.txt`). Nas migrations de domínio (V013+), **todas as FKs que hoje apontam para `codigo_barra`** (estoque, movimento, pedido_item, anúncio…) passam a referenciar `sku`.

> **SKU × EAN (Q7, fechada em 2026-07-10):** **separar** `sku` (interno, obrigatório, único por tenant — a chave usada em todo o domínio) de `ean` (**GTIN real, nullable**, `UNIQUE` parcial `WHERE ean IS NOT NULL`). O produto pode ser **cadastrado sem EAN** (loja física, sem marca); o EAN só é **exigido na publicação** em canal que pede GTIN — o adapter (P2/anti-corruption) manda `ean` como GTIN, e se estiver NULL bloqueia o *publicar anúncio* com Problem Details pedindo o EAN (ou usa o fluxo "sem GTIN" quando o canal permitir). Nunca empurrar o `sku` interno como se fosse EAN.

> **Galeria de imagens (2026-07-16):** o campo `imagem` saiu de `produto` (um produto pode ter
> **várias** imagens) e virou tabela própria:
> ```sql
> produto_imagem(id_produto_imagem PK, id_tenant FK, id_produto FK,
>                 indice SMALLINT, imagem TEXT,          -- URL/chave de object storage
>                 UNIQUE(id_tenant, id_produto, indice))  -- indice controla a ordem de exibição
> ```
> Sem `ON DELETE CASCADE` (convenção do projeto): apagar um `produto` com imagens vinculadas
> falha por FK até a aplicação remover as imagens primeiro.

✅ **Resolvido (V017):** `sku` único por tenant (`UNIQUE(id_tenant, sku)`) e `UNIQUE(id_produto, id_variante_linha, id_variante_coluna)` impedindo variação duplicada.

### 3.3.4 Módulo `estoque` (saldo, movimentações, balanço)

```sql
-- saldo materializado por variação (SKU) × empresa
produto_estoque(id_produto_estoque PK, id_empresa FK, id_variacao FK, qtd_estoque NUMERIC(14,3),
                reservado NUMERIC(14,3),          -- P1/R5: reserva (Q2/ADR-004)
                minimo NUMERIC(14,3),             -- estoque mínimo / alerta
                disponivel NUMERIC(14,3) GENERATED ALWAYS AS (qtd_estoque - reservado) STORED,
                atualizado_em,
                UNIQUE(id_empresa, id_variacao))
-- movimento (mestre): imutável — P3; niner_app SEM UPDATE/DELETE, V024
produto_movimento_mestre(id_movimento PK, tipo_movimento,   -- COMPRA/TRANSFERENCIA/DEVOLUCAO/AJUSTE/VENDA/RESERVA/LIBERACAO_RESERVA
                         data_movimento, id_empresa FK, id_fornecedor FK,
                         id_transferencia,        -- sem FK: número vindo de gerador externo (proposital)
                         id_devolucao FK, id_venda FK, nota_fiscal)
-- movimento (detalhe): CORRIGÍVEL desde 2026-07-16 (niner_app tem UPDATE/DELETE, V024) —
-- trigger trg_produto_movimento_detalhe_estoque recalcula produto_estoque.qtd_estoque
produto_movimento_detalhe(id_movimento_detalhe PK, id_movimento FK, id_empresa FK,
                          id_funcionario FK, credito_debito,  -- C/D
                          id_variacao FK, qtd_produto NUMERIC(14,3),
                          preco_custo NUMERIC(12,2), preco_venda NUMERIC(12,2),
                          valor_desconto NUMERIC(12,2), valor_acrescimo NUMERIC(12,2),
                          produto_oferta BOOL,
                          origem)                       -- venda manual / canal X
```
> **Regra de negócio (P1/P3) — decisão fechada em 2026-07-16 (reverte a orientação anterior
> "domínio Java, nunca trigger"):** quem mantém `produto_estoque.qtd_estoque` é a trigger
> `trg_produto_movimento_detalhe_estoque` (função `fn_atualiza_estoque_movimento()`, PL/pgSQL,
> V019), acionada por `INSERT`/`UPDATE`/`DELETE` em `produto_movimento_detalhe`:
> `credito_debito = 'C'` soma `qtd_produto`, `'D'` subtrai; `UPDATE` desfaz o efeito da linha
> antiga e aplica o da nova (cobre mudança de empresa/variação/tipo/quantidade); `DELETE`
> desfaz o efeito. A trigger faz **UPSERT** em `produto_estoque` — cria a linha na hora se
> ainda não existir para o `(id_tenant, id_empresa, id_variacao)`, para o saldo nunca ficar
> "invisível" por falta de linha pré-cadastrada (P1). Não existe mais
> `SP_ATUALIZA_QUANTIDADE_ESTOQUE` como objeto separado — a lógica está toda na função de
> trigger. Roda como `niner_app` (SECURITY INVOKER, padrão do Postgres): a política RLS de
> `produto_estoque` continua valendo, sem risco de vazar saldo entre tenants.
>
> Consequência: `produto_movimento_detalhe` **deixou de ser imutável** (`niner_app` ganhou
> `UPDATE`/`DELETE` de volta em V024) — só `produto_movimento_mestre` continua sob a regra P3
> de "correção só por novo movimento compensatório, nunca edição".

✅ **Resolvido (V019):** `reservado`, `minimo` e `disponivel` (coluna gerada) em `produto_estoque`;
`origem` em `produto_movimento_detalhe`. `saldo_apos` chegou a ser adicionado e foi **removido em
2026-07-16** (decisão do produto) — o saldo resultante fica só materializado em `produto_estoque`,
não mais snapshot por linha do ledger.

<span style="color:red">🔴 Impedir saldo negativo sem flag explícita (R2) — hoje só há `CHECK (reservado >= 0)`; falta constraint/validação para `qtd_estoque`/`disponivel` nunca negativos sem flag explícita.</span>

```sql
-- inventário / contagem
produto_balanco(id_balanco PK,               -- BIGINT (2026-07-16): volume de contagens esperado maior que as demais tabelas
                id_empresa FK, data_balanco,
                id_variacao FK, qtd_contagem NUMERIC(14,3))
```
> `qtd_sistema` e `observacao` chegaram a ser adicionados e foram **removidos em 2026-07-16**
> (decisão do produto) — a comparação contagem × saldo do sistema fica por conta da aplicação,
> lendo `produto_estoque` no momento do balanço.

### 3.3.5 Módulo `pedidos` / vendas (loja física + marketplace)

```sql
-- venda da loja física (legado)
venda(id_venda PK, id_empresa FK, id_cliente FK, data_venda,
      tipo_operacao)                 -- V venda / D devolução
-- venda ganhou id_funcionario (V018) e depois perdeu de novo (2026-07-16): vendedor/comissão
-- por item ficam em produto_movimento_detalhe.id_funcionario, não em venda.
-- venda também perdeu valor_total, observacao e criado_em (2026-07-16): total é derivado do
-- ledger; venda/venda_devolucao são as únicas tabelas do domínio sem criado_em, porque
-- data_venda/data_devolucao já cumprem esse papel (sem fluxo de rascunho→confirmação).
venda_devolucao(id_devolucao PK, id_empresa FK, data_devolucao,
                id_venda_credito, id_venda_debito,
                id_vale_mercadoria, vale_usado BOOL)
```
✅ **Resolvido (V020/V021):** tabelas de marketplace, ausentes no legado, criadas.
```sql
-- canal de venda (ML, Shopee, ...)
canal(id_canal PK, tipo ENUM('MERCADO_LIVRE','SHOPEE','AMAZON','ECOMMERCE'),
      nome, credenciais JSONB /* cifrado AES-GCM */, status, config JSONB)
-- de-para anúncio ↔ SKU (R6)
anuncio(id_anuncio PK, id_canal FK, id_variacao FK, id_externo,
        preco NUMERIC(12,2),
        status_sync ENUM('OK','PENDENTE','ERRO'), ultimo_erro TEXT,
        UNIQUE(id_canal, id_externo))
-- pedido de marketplace (fila única R5) — idempotência por (canal, id_externo)
pedido(id_pedido PK, id_canal FK, id_externo, status, comprador JSONB,
       total NUMERIC(12,2), frete NUMERIC(12,2), payload_bruto JSONB,
       reserva_expira_em, criado_em,
       UNIQUE(id_canal, id_externo))
pedido_item(id_pedido_item PK, id_pedido FK, id_variacao FK, id_anuncio FK,
            quantidade NUMERIC(14,3), preco_unit NUMERIC(12,2))
```
<span style="color:red">🔴 Unificar o modelo de "pedido": o legado só tem `venda` (física). Decidir se `venda` e `pedido` (canal) convergem para uma fila única de expedição (R5, estados `recebido→pago→em separação→enviado→entregue/cancelado`) ou permanecem separados com uma view unificada.</span>
<span style="color:red">🔴 A venda física (`venda`) hoje não tem itens próprios — os itens vêm de `produto_movimento_detalhe` (tipo 5). Confirmar se esse é o design desejado ou se `venda` ganha `venda_item`.</span>
> **Estratégia de reserva (Q2, fechada em 2026-07-10 — ADR-004):** reservar no **`recebido`**. Ao importar um pedido de canal, na **mesma transação** cria-se a linha em `pedido`/`pedido_item` e incrementa-se `produto_estoque.reservado` (débito lógico de disponível = `qtd_estoque − reservado`), registrando um `produto_movimento` de tipo `reserva` (P3). Transições da fila: `pago`/`em separação` **não** re-reservam (a reserva já existe); `enviado` converte reserva em **baixa** de `qtd_estoque` e zera a parcela `reservado`; `cancelado` **devolve** a reserva (R5). **Expiração:** pedidos parados em `recebido` (sem pagar) além de um prazo **configurável por canal** liberam a reserva automaticamente (via worker/outbox) — mitiga reserva-fantasma de boleto/PIX não concluído. Como ML/Shopee em geral só notificam **pós-pagamento**, na prática a janela `recebido→pago` é quase nula; a expiração cobre e-commerce próprio e boleto. 🔴 *Prazo default da expiração a confirmar (tunável, não bloqueante).*

### 3.3.6 Módulo `integracao` (outbox, webhooks)

✅ **Resolvido (V022):** ausente no legado, criado (P2: async + idempotente).
```sql
-- outbox: toda mutação de estoque/preço grava evento na MESMA transação
outbox_evento(id PK, id_tenant FK, tipo, agregado_id, payload JSONB, status, tentativas,
              proximo_retry, processado_em, erro, criado_em)
-- idempotência de webhooks recebidos
webhook_recebido(id PK, id_tenant FK, id_canal FK, webhook_id, recebido_em, processado_em, erro,
                  UNIQUE(id_canal, webhook_id))
```
Worker `@Scheduled` consome `outbox_evento` com `SELECT ... FOR UPDATE SKIP LOCKED`, retry exponencial e dead-letter visível no painel (R7). Polling de segurança a cada 15 min cobre webhooks perdidos.

### 3.3.7 Módulo `financeiro` (caixa, contas, conta corrente) — 🟡 **Quase todo no v1 (Q5 revisada 2026-07-16 — ADR-010/ADR-012)**

> ✅ **Q5 (2026-07-10 — ADR-010), revisada em 2026-07-16 (ADR-012, duas rodadas):** o dono do produto antecipou **crediário, caixa e contas a pagar** da Fase 2 para o v1 — mesmo movimento já feito com `cfg_plano_contas` (V016). Migration **V025** criou: `tipo_carteira`, `moeda`, `moeda_detalhe`, `contas_receber`/`contas_receber_detalhe`, `caixa_mestre`/`caixa_detalhe`. Migration **V026** criou `contas_pagar`. **Só continua fora do v1** (referência de modelagem apenas): `conta_corrente`, `conta_corrente_movimento`. Venda manual (R9) continua sem gravar automaticamente em `contas_receber`/`caixa_detalhe` — a ligação venda→recebível é *feature* futura, ainda não implementada no domínio Java (só o schema existe).
>
> **Diferenças do V025/V026 real vs. o pseudo-schema legado abaixo:** todas as 8 tabelas nascem com `id_tenant` (P8) e FKs compostas `(id_tenant, id_x)`; dinheiro/percentual em `NUMERIC` (P7, não `FLOAT`); `documento_recebido`/`documento_pago`/`caixa_fechado` viram `boolean` (não `VARCHAR(1)` S/N; `documento_pago` com `DEFAULT false`); `contas_receber_detalhe` é **1:1** com `contas_receber` (PK `(id_tenant, id_conta_receber)`, sem PK própria); `caixa_detalhe.tipo_operacao` é ENUM `tipo_operacao_caixa` (`RECEBIMENTO_VENDA`/`RECEBIMENTO_PARCELA_CREDIARIO`/`DEBITO_CAIXA`/`CREDITO_CAIXA`/`TROCO`, mapeado do legado `RV/RP/DC/CC/TR`); `caixa_detalhe.credito_debito` reaproveita o ENUM `credito_debito` (`C`/`D`) já criado em V013 para o ledger de estoque; `caixa_detalhe` ganhou `criado_em` (ausente no legado — necessário para "quando" de cada lançamento, P3); `moeda` **não** tem seed global — é semeada **por tenant** no signup (`SignupService.assinar()`, logo após `cfg_geral`); `contas_pagar.localizador` (nome do legado) virou **`id_conta_pagar`** (pedido explícito, quebra de propósito a consistência de nome com `caixa_detalhe.localizador`); `contas_pagar.nota_fiscal` é `integer` **nullable** sem `DEFAULT 0` (sem valor mágico) — a mesma padronização corrigiu `produto_movimento_mestre.nota_fiscal` (V019), que era `text`.

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
> ✅ **Resolvido (Q5, 2026-07-10 — ADR-010), revisado em 2026-07-16 (ADR-012):**
> `contas_pagar`/`conta_corrente(_movimento)` continuam **fora do v1**, Fase 2. R9 (venda
> manual) segue atendido por `venda` + baixa de estoque, sem ligação automática ao financeiro.
> Diagrama §3.1 ainda não tem o módulo `financeiro` desenhado (pendência de atualização do
> diagrama, não do schema).
>
> 🟡 **Exceções pontuais antecipadas da Fase 2 (crescendo desde 2026-07-16):**
> - `cfg_plano_contas` — criada em **V016** (junto de `identidade`/cadastros), preparação
>   para relatórios/DRE futuros. PK `(id_tenant, id_plano_contas)` (P8 — chave de negócio
>   não pode ser PK sozinha, precisa do tenant); `tipo_movimento` vira ENUM
>   `tipo_movimento_conta` (`'CRÉDITO'`/`'DÉBITO'`/`'NEUTRO'`, por extenso, V013);
>   `inclui_dre`/`inclui_fluxo_caixa` viram `boolean`. `fornecedor.id_plano_contas`
>   (§3.3.9) referencia essa tabela, `NOT NULL`.
> - `tipo_carteira`, `moeda`, `moeda_detalhe`, `contas_receber`/`contas_receber_detalhe`,
>   `caixa_mestre`/`caixa_detalhe` — criadas em **V025** (crediário + caixa). Ver diferenças
>   do real vs. o pseudo-schema abaixo na nota no início desta seção.
> - `contas_pagar` — criada em **V026**. PK `id_conta_pagar` (renomeada do `localizador` do
>   legado); `nota_fiscal integer` nullable, sem `DEFAULT 0`.
> - `conta_corrente`, `conta_corrente_movimento` continuam **sem migration**, só referência de
>   modelagem para a Fase 2.

### 3.3.8 Configuração global

```sql
cfg_geral(juros_crediario_dias, juros_crediario NUMERIC(5,2), multa_crediario_dias,
          multa_crediario NUMERIC(5,2), moeda_devolucao, percentual_desconto_venda NUMERIC(5,2),
          usa_variante_linha BOOL, usa_variante_coluna BOOL, nome_etiqueta)
cfg_categoria_cliente(id_categoria_cliente PK, nome_categoria)
```
<span style="color:red">🔴 `cfg_geral` no legado é tabela sem PK (singleton): no Postgres, garantir linha única (ex.: PK fixa `id = 1` + `CHECK (id = 1)`).</span>

✅ **Resolvido (V016):** `cfg_categoria_cliente(id_categoria_cliente PK, id_tenant FK, nome_categoria,
UNIQUE(id_tenant, nome_categoria))` — criada junto de `cliente` (mesmo arquivo/módulo, não em
"configuração global"), pois `cliente.id_categoria_cliente` referencia essa tabela como FK.

✅ **Resolvido (V023, ajustado 2026-07-16):** `cfg_geral` real não tem `moeda_devolucao`
(removido) nem `nome_etiqueta` (esse ficou em `empresa.cfg_nome_etiqueta`, não em `cfg_geral` —
ver §3.3.2). Ganhou `cfg_usa_variante_linha boolean NOT NULL DEFAULT true` e
`cfg_usa_variante_coluna boolean NOT NULL DEFAULT true` (prefixo `cfg_` no nome da coluna,
convenção nova a partir desta data).

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
> **Implementado (V016):** `cliente`/`fornecedor`/`funcionario` nasceram com `id_tenant` (não
> `id_empresa`) e um subconjunto simplificado de campos (`cpf_cnpj` único em vez de `cpf`; sem
> `bloqueado`/`contato_crm` — ficam para quando houver demanda). `fornecedor.id_plano_contas`
> (FK composta para `cfg_plano_contas`, ver §3.3.7) é **obrigatório** (`NOT NULL`) desde
> 2026-07-16 — sem linha padrão pré-cadastrada, então todo fornecedor precisa de um plano de
> contas já criado. `instagram`/`facebook` do
> legado entraram em 2026-07-16, junto com `whatsapp` e `tiktok` (novos, sem equivalente no
> legado) — as 4 colunas são `text` nullable, sem validação de formato no banco.
> `id_categoria_cliente` (FK para `cfg_categoria_cliente`, ver §3.3.8) é **obrigatório**
> (`NOT NULL`) — todo cliente precisa de uma categoria escolhida, sem valor padrão pré-cadastrado.
> `nascimento` e `genero` (M/F/O no legado) viraram `data_nascimento DATE` e
> `genero genero_cliente` (ENUM `MASCULINO`/`FEMININO`/`OUTROS`, tipo definido em V013) —
> colunas nullable, mas **obrigatórias para pessoa física** via
> `CHECK (NOT fisica_juridica OR (data_nascimento IS NOT NULL AND genero IS NOT NULL))`;
> cliente pessoa jurídica (`fisica_juridica = false`) pode deixar ambas em branco.
> `funcionario` ganhou `telefone` (2026-07-16). A constraint `funcionario_cpf_uk` virou
> `UNIQUE(id_tenant, id_funcionario)` — como `id_funcionario` já é a PK, isso não impõe nada
> além do que a PK já garante; **o CPF deixou de ser único por tenant** (decisão explícita).
> `cliente.complemento` (`text`, nullable) entrou em 2026-07-20 — pedido do dono do produto
> para a primeira tela de cliente (`web/`, `docs/telas/cliente.md`), entre `numero` e `bairro`
> no endereço (apto/bloco/sala etc.). Banco recriado do zero na mesma sessão (convenção do
> projeto para alteração de coluna em migration já aplicada — §7).

### 3.3.10 Pendências transversais do modelo

- <span style="color:red">🔴 **Preço por canal (P1/R-nice):** o legado tem um único `preco_venda` por produto. Para markup + comissão por canal, o preço de venda por canal fica em `anuncio.preco`; documentar a regra de precificação.</span>
- <span style="color:red">🔴 **Kits/composições (P1):** um anúncio consumindo múltiplos SKUs não é suportado pelo modelo atual — prever tabela `kit_componente` se entrar no roadmap.</span>
- <span style="color:red">🔴 **Auditoria imutável (P3):** parcialmente resolvido — `produto_movimento_mestre`/`produto_movimento_detalhe` já são imutáveis ao nível de banco (`niner_app` sem `UPDATE`/`DELETE`, V024) e cobrem "quem/quando/origem/saldo anterior→novo" para **estoque**. Falta o mesmo para **preço**: hoje não há histórico — avaliar `produto_preco_historico`.</span>
- <span style="color:red">🔴 **Cifragem de credenciais (ADR-005):** `canal.credenciais` em `JSONB` cifrado (AES-GCM, chave fora do banco).</span>

### 3.3.11 Módulo `plataforma` (control-plane) — tabelas GLOBAIS

Tabelas do **Plano de Controle** (o negócio da Vetor). São **globais**: **não** têm `id_tenant` como discriminador nem entram nas políticas RLS de tenant (P9). Dinheiro em `NUMERIC` (P7). Não confundir com o módulo `financeiro` do lojista (§3.3.7).

```sql
-- conta assinante = o tenant
tenant(id_tenant PK, nome_conta, slug UNIQUE, email_contato,
       status ENUM('TRIAL','ATIVA','INADIMPLENTE','SUSPENSA','CANCELADA'),
       criado_em, cancelado_em)
-- planos de preço (tiers) e seus limites
plano(id_plano PK, nome, descricao, ciclo_padrao ENUM('MENSAL','ANUAL'),
      preco_mensal NUMERIC(12,2), preco_anual NUMERIC(12,2), ativo BOOL,
      limite_canais INT, limite_produtos INT, limite_usuarios INT, limite_pedidos_mes INT)
assinatura(id_assinatura PK, id_tenant FK, id_plano FK,
           status ENUM('TRIAL','ATIVA','INADIMPLENTE','SUSPENSA','CANCELADA'),
           ciclo ENUM('MENSAL','ANUAL'), trial_expira_em TIMESTAMPTZ,
           inicio_vigencia, fim_vigencia, proxima_cobranca DATE,
           id_gateway_assinatura VARCHAR,          -- ref da recorrência no gateway
           criado_em, atualizado_em)
fatura(id_fatura PK, id_assinatura FK, id_tenant FK, competencia DATE,
       valor NUMERIC(12,2), vencimento DATE,
       status ENUM('ABERTA','PAGA','VENCIDA','CANCELADA','ESTORNADA'),
       id_gateway_cobranca VARCHAR, criado_em)
pagamento(id_pagamento PK, id_fatura FK, metodo ENUM('PIX','BOLETO','CARTAO'),
          gateway VARCHAR, id_gateway_transacao VARCHAR, valor NUMERIC(12,2),
          status ENUM('PENDENTE','CONFIRMADO','FALHOU','ESTORNADO'),
          pago_em TIMESTAMPTZ, payload_bruto JSONB)
-- idempotência do gateway (mesmo padrão de webhook_recebido dos marketplaces, §3.3.6)
webhook_gateway(id PK, gateway VARCHAR, evento_id UNIQUE, tipo, payload JSONB,
                recebido_em, processado_em, erro)
-- contadores para enforcement de limites do plano (R19)
uso_tenant(id_tenant PK/FK, periodo DATE,        -- competência mensal p/ pedidos/mês
           qtd_canais INT, qtd_produtos INT, qtd_usuarios INT, qtd_pedidos_mes INT,
           atualizado_em)
-- staff da plataforma (separado dos usuários do tenant, R18)
staff(id_staff PK, nome, email UNIQUE, senha_hash, ativo BOOL,
      papel ENUM('SUPER_ADMIN','SUPORTE','FINANCEIRO'))
-- impersonação auditada (R21/P3)
impersonacao_log(id PK, id_staff FK, id_tenant FK, iniciado_em, encerrado_em, motivo)
```
Notas:
- **Adapter de gateway (ADR-008, D3 adiada):** `id_gateway_*` e `pagamento.gateway` são preenchidos por um adapter abstrato; no v1 a cobrança pode ser **manual/registro** até integrar um provedor real (Asaas/Iugu/…).
- **Efeitos de cobrança idempotentes (P2):** marcar fatura paga, reativar/suspender assinatura passam pelo **outbox**; `webhook_gateway.evento_id UNIQUE` + `FOR UPDATE SKIP LOCKED` no worker.
- **Enforcement (R19):** `uso_tenant` é atualizado por eventos de domínio (produto criado, canal conectado, pedido importado); um *guard* no caminho de escrita compara `uso_tenant` vs `plano` e bloqueia com Problem Details ao estourar tier estrutural. Pedidos/mês nunca dropam (§R19).
- **Gate de login do ERP:** tenant `SUSPENSA`/`CANCELADA` → `/api/v1` nega; `INADIMPLENTE` → modo restrito (regra D10, em aberto).

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

# --- Plano de Controle e site público (v2.0) ---
# superfície pública (site/ — sem auth ou auth leve + rate limit)
POST   /api/publico/assinar                  signup: cria tenant + admin + trial (60d)  [R12]
GET    /api/publico/planos                    catálogo de planos e preços               [R11]
POST   /api/publico/assinaturas/checkout      escolhe plano + inicia pagamento          [R14]
# superfície do tenant (web/ — autogestão da própria assinatura, JWT de tenant)
GET    /api/v1/assinatura                     status, plano, uso vs. limites            [R15]
POST   /api/v1/assinatura/upgrade             troca de plano (proration)                [R15]
POST   /api/v1/assinatura/cancelar            cancela ao fim do ciclo                    [R15]
GET    /api/v1/faturas                         faturas do próprio tenant                 [R14]
# superfície da plataforma (admin/ — JWT de staff, opera em plataforma.*)
GET    /api/admin/tenants                     lista/filtra tenants (status/plano/uso)   [R17]
GET    /api/admin/tenants/{id}                ficha do tenant (plano, faturas, saúde)   [R17]
POST   /api/admin/tenants/{id}/suspender      suspende/reativa                          [R18]
POST   /api/admin/tenants/{id}/impersonar     token efêmero + log de auditoria          [R21]
GET    /api/admin/faturas?status=VENCIDA      inadimplência / régua de dunning          [R16]
POST   /webhooks/gateway                       notificação do gateway (idempotente)      [R16]
```

Convenções: versionamento no path; erros no formato Problem Details (RFC 9457); paginação por cursor em listagens; todos os endpoints documentados no OpenAPI antes da implementação (contrato faz parte da spec da feature). **Multi-tenant:** o tenant vem do claim `tid` do JWT (nunca do path/body) em `/api/v1/**`; `/api/admin/**` usa JWT de staff (`aud=plataforma`) e opera cross-tenant; `/api/publico/**` é anônimo/rate-limited.

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
  web:                    # ERP do lojista
    build: ./web          # node:22 build → nginx
    ports: ["5173:80"]
  admin:                  # backoffice da plataforma
    build: ./admin        # node:22 build → nginx
    ports: ["5174:80"]
  site:                   # site público de aquisição + trial
    build: ./site         # node:22 build → nginx
    ports: ["5175:80"]
volumes:
  pgdata:
```

> **Banco:** `POSTGRES_DB` deve ser **`niner_db`** (o `erp` acima é placeholder da v1). A API conecta como role `niner_app` (sem `BYPASSRLS`); migrations Flyway rodam como `niner_owner` (ver §3.1.1 / §3.5.1).
>
> **Base-URL da API em runtime (topologia stateless):** os 3 fronts **não** embutem a URL da API no bundle. Cada imagem serve um `config.js`/`env.js` (ou `/config` do nginx) lido em runtime com `API_BASE_URL`; trocar a variável e recarregar aponta o front para outra instância da API. Isso permite **2 servidores com 2 APIs** e failover/manutenção sem rebuild.

Produção: mesma composição + backup diário (`pg_dump` ou WAL-G), segredos via variáveis de ambiente/secret manager, TLS no proxy reverso (Caddy/Traefik/nginx). API stateless → escalar horizontalmente atrás de um balanceador (sem afinidade de sessão).

### 3.5.1 Migrations Flyway — sequência para `niner_db`

Materializadas em `db/migration/`. A **plataforma + tenant + infra de contexto de tenant vêm primeiro** (o `id_tenant` é FK das tabelas de domínio e a função de contexto é base das políticas RLS). Cada migration de domínio aplica as regras de conversão Firebird→Postgres (§3.3.1). Greenfield: **V001 é o baseline** (não há migração in-place; `db/*.txt` é só referência de modelagem). Reversibilidade por scripts de reversão manuais (§7 exige reversível). **Antes das migrations**, o `db/bootstrap/00_roles.sql` (fora do Flyway, superusuário) cria as roles `niner_owner` (dona, roda o Flyway) e `niner_app` (aplicação, **sem `BYPASSRLS`**) — P8.

**Control-plane + infra de tenant (criados — renumerados em ordem contígua):**
```
V001  schema plataforma
V002  contexto de tenant (função plataforma.tenant_atual(); convenção app.id_tenant)
V003  tipos ENUM do control-plane
V004  plataforma.tenant            V005  plataforma.plano
V006  plataforma.assinatura        V007  plataforma.fatura + pagamento
V008  plataforma.webhook_gateway   V009  plataforma.uso_tenant
V010  plataforma.staff + impersonacao_log
V011  grants de niner_app no schema plataforma
V012  seed dos planos (🔴 preços provisórios — D1)
```
**Domínio do lojista (criado — V013–V024, cada tabela nasce com `id_tenant`):**
```
V013  tipos ENUM de domínio (canal, pedido, movimento, outbox, genero_cliente...)
V014  identidade.empresa (+id_tenant; codigo_empresa único/tenant; cfg_nome_etiqueta NOT NULL)
V015  identidade.usuario (+id_tenant, UNIQUE(id_tenant,lower(email))) + usuario_rotina
V016  cadastros: cfg_categoria_cliente · cliente (id_categoria_cliente NOT NULL;
      data_nascimento/genero obrigatórios só p/ pessoa física; whatsapp/instagram/
      facebook/tiktok) · cfg_plano_contas (PK composta id_tenant+id_plano_contas; prep.
      DRE, Q5/ADR-010) · fornecedor (id_plano_contas NOT NULL) · funcionario (telefone;
      CPF não é mais único por tenant)
V017  catalogo: cfg_categoria_produto/variante_linha/variante_coluna · produto (sem imagem) ·
      produto_categoria · produto_barra (sku+ean, Q7) · produto_imagem (galeria, indice
      único por produto)
V018  vendas: venda (sem id_funcionario/valor_total/observacao/criado_em — vendedor/
      comissão/total em produto_movimento_detalhe) · venda_devolucao (sem criado_em)
V019  estoque: produto_estoque (reservado/disponivel, Q2) · produto_movimento_mestre
      (imutável) + produto_movimento_detalhe (corrigível; sem saldo_apos por linha) ·
      produto_balanco (id_balanco BIGINT; sem qtd_sistema/observacao) · trigger
      trg_produto_movimento_detalhe_estoque mantém produto_estoque.qtd_estoque
V020  canais: canal (credenciais cifradas) · anuncio (de-para SKU, R6)
V021  pedidos de canal: pedido (idempotente canal+id_externo) · pedido_item
V022  integracao: outbox_evento (COM id_tenant) · webhook_recebido
V023  cfg_geral (singleton POR tenant; cfg_usa_variante_linha/coluna; sem moeda_devolucao)
V024  RLS de domínio (final p/ V014–V023): ENABLE + FORCE ROW LEVEL SECURITY +
      USING (id_tenant = plataforma.tenant_atual()) em TODAS as tabelas de tenant + grants +
      REVOKE UPDATE/DELETE só em produto_movimento_mestre (imutabilidade, P3)
V025  financeiro (parcial, Q5 revisada 2026-07-16 — ADR-012): tipo_carteira, moeda,
      moeda_detalhe, contas_receber + contas_receber_detalhe (1:1), caixa_mestre +
      caixa_detalhe (ENUM tipo_operacao_caixa: RV/RP/DC/CC/TR do legado). RLS próprio no
      mesmo arquivo (V024 já tinha rodado).
V026  financeiro: contas_pagar (Q5 revisada de novo, 2026-07-16 — ADR-012). PK
      id_conta_pagar (renomeada de localizador); nota_fiscal integer nullable, sem
      DEFAULT 0. RLS próprio no arquivo. conta_corrente* segue como único módulo fora do v1.
```
Detalhe migration a migration (com ✅/🔴 de situação): `db/migration/README.md`.
Racional de RLS num arquivo final: garante que **nenhuma** tabela de tenant fica sem política — auditável num único ponto e testável ("toda tabela de tenant tem RLS" vira teste de P8). As tabelas de `plataforma` são globais (P9) e **não** entram no RLS de tenant.

## 3.6 Requisitos não funcionais

- **Desempenho:** p95 < 300 ms nos endpoints do painel; importação de pedido processada em < 10 s do webhook.
- **Confiabilidade:** retry exponencial (1 min → 2 → 4… máx 1 h) com dead-letter visível no painel (R7); polling de segurança a cada 15 min cobre webhooks perdidos.
- **Segurança:** credenciais de canal cifradas em repouso (AES-GCM, chave fora do banco); LGPD — dados de comprador retidos só o necessário; rate limit nos webhooks; validação de assinatura dos webhooks quando o canal oferecer.
- **Backup/restauração:** RPO 24 h (v1), teste de restore mensal.

## 3.7 Padrão visual e de UI (design system)

Toda tela do frontend segue o **padrão de referência** em [`docs/padroes/cadastro_fornecedor_campos_cinza.html`](docs/padroes/cadastro_fornecedor_campos_cinza.html) — o mockup do Cadastro de Fornecedor ("campos cinza"). Ele é o *golden file* da UI: quando uma decisão de layout/estilo não estiver aqui, ela está no HTML de referência; ao construir componentes React, portar esses padrões para tokens/componentes reutilizáveis (não copiar HTML por tela). O painel lateral "Personalizar cores" do mockup é apenas ferramenta de exploração de paleta — **não** faz parte do produto.

**Design tokens (CSS custom properties).** Todas as cores vêm de variáveis `--*`; nenhum hex literal em componente. Suporte obrigatório a **tema claro e escuro**: default por `@media (prefers-color-scheme)` e override explícito via atributo `data-theme="light|dark"` no elemento raiz (toggle do usuário vence a preferência do sistema). Cada tema mantém seu próprio conjunto de tokens. Paleta base:

| Token | Claro | Escuro | Uso |
|---|---|---|---|
| `--ground` | `#f5f4f0` | `#12181a` | fundo da página |
| `--surface` | `#ffffff` | `#1a2225` | fundo de cards/formulários |
| `--surface-2` | `#edece6` | `#202a2d` | barras/rodapés |
| `--field-bg` | `#e4e6e2` | `#2a3336` | fundo dos campos ("cinza") |
| `--field-text` | `#20262a` | `#e7ecec` | texto dos campos |
| `--label-color` | `#5c6660` | `#93a19e` | rótulos |
| `--ink` / `--ink-muted` | `#20262a` / `#5c6660` | `#e7ecec` / `#93a19e` | texto principal / secundário |
| `--accent` | `#1f6f6b` (teal) | `#4fbdb2` | ação primária, títulos de seção |
| `--line` / `--line-strong` | `#dee2dc` / `#b9beb5` | `#2a3538` / `#4a5457` | divisórias / bordas de campo |
| `--danger` | `#a63d29` | `#e2836a` | erros, marca de obrigatório |
| `--focus` | = `--accent` | = `--accent` | anel de foco |

**Tipografia.** Corpo em sans do sistema (`-apple-system, "Segoe UI", Roboto…`), 14px, `line-height 1.5`. Títulos (`h1`, `h2`) em **serif** (`Georgia, "Iowan Old Style"…`). Rótulos de seção e *eyebrows* em maiúsculas, `letter-spacing 0.08em`, cor `--accent`. Campos numéricos/documentos (CNPJ, CEP, telefone, valores) usam `font-variant-numeric: tabular-nums` (classe `.mono`).

**Layout.**
- **Topbar:** eyebrow (breadcrumb) + `<h1>` à esquerda; ações à direita.
- **Formulário:** card com `--surface`, borda `--line`, `border-radius: 12px` e sombra `--shadow`, dividido em **seções** (`.section`) separadas por linha. Cada seção tem cabeçalho `section-label` (uppercase/accent) + `section-hint` (texto de apoio).
- **Grid de 12 colunas** (`grid-template-columns: repeat(12,1fr)`, gap `16px 20px`); campos ocupam `col-2/3/4/6/7/12`. Colapsa para 1 coluna em `≤640px`.
- **Footer-bar:** `--surface-2`, alinhado à direita, com `id-chip` (identificador do registro) à esquerda e botões **Cancelar** (secundário) + **Salvar** (primário).

**Componentes.**
- **Campo:** `label` (com `*` em `--danger` quando obrigatório) acima de `input`/`select` de fundo `--field-bg`, borda `--line-strong`, `border-radius: 7px`, padding `9px 10px`, largura total. Foco: sem outline default, borda `--focus` + `box-shadow` de 3px com `color-mix`.
- **Botões:** `.btn` `font-weight 600`, `border-radius 8px`. Primário = `--btn-primary-bg`/`--btn-primary-text`; secundário = transparente com borda `--btn-secondary-border`. Hover por `filter: brightness(0.92)`.
- **Texto sempre em maiúsculas (2026-07-20):** todo campo de texto livre (nome, endereço, redes sociais etc.) é normalizado para **MAIÚSCULAS** no front (`onChange` força o valor, não importa o estado do teclado/Caps Lock do usuário) e reforçado no backend como defesa em profundidade. Única exceção: **e-mail** (caixa preservada). Campos não-texto (select, data, checkbox, valores monetários/mascarados) não se aplicam.
- **Foco automático:** ao abrir uma tela de inclusão ou edição, o primeiro campo do formulário recebe foco automaticamente (`autoFocus`), para o usuário já poder digitar sem clicar.

**Acessibilidade (obrigatório).** `:focus-visible` com `outline: 2px solid var(--focus)` em todo controle; `aria-label` em botões de ícone; `aria-pressed` em toggles; regiões de feedback com `aria-live="polite"`; contraste AA nos dois temas. Português em todos os rótulos e no vocabulário de domínio (§CLAUDE.md).

**Responsivo.** Breakpoints em `960px` (painéis laterais viram bloco) e `640px` (grid colapsa, paddings reduzidos). Imagens/tabelas largas rolam em contêiner próprio, nunca a página.

Convenção de nomes de campo no mockup segue os identificadores legados em MAIÚSCULAS (`RAZAO_SOCIAL`, `CNPJ`); no produto, os nomes de campos da API seguem o contrato REST (§3.4) — o mockup ilustra layout, não o contrato.

### 3.7.1 Ajuda da tela (manual de operação) + vídeo — **obrigatório em toda tela**

**Regra (R22):** **toda tela** do produto (ERP `web/`, backoffice `admin/`, site `site/`) tem um componente padrão de **Ajuda** que explica e detalha o uso daquela tela (um *manual de operação* contextual) e um ponto **preparado para direcionar a um vídeo explicativo**. Sem exceção — telas sem ajuda não passam no gate de aprovação de spec.

- **Componente `AjudaDaTela`** (parte do design system): botão/ícone de ajuda (ex.: `?`) fixo no cabeçalho da tela (junto do `eyebrow`/`h1` do §3.7), que abre um painel/drawer lateral com:
  - **Título + objetivo** da tela (o que ela resolve, para qual persona).
  - **Passo a passo** de operação (o "como fazer" — o manual), incluindo campos obrigatórios, validações e ações do rodapé (`footer-bar`).
  - **Dicas/erros comuns** e o que fazer.
  - **Botão "Assistir vídeo"** que direciona ao vídeo explicativo da tela.
- **Fonte do conteúdo (API-first, P4):** o texto da ajuda e a URL do vídeo **não** são hard-coded no front. Vêm de um catálogo de ajuda versionado, servido pela API — proposta de modelo:
  ```sql
  -- 🔴 a criar (migration futura): catálogo de ajuda por tela (global; conteúdo institucional, não é dado de tenant)
  ajuda_tela(chave_tela PK,        -- ex.: 'catalogo.produto.form'
             titulo, objetivo, passos JSONB,   -- passos = lista ordenada de instruções
             url_video,            -- link do vídeo explicativo (pode ser NULL até gravar)
             versao, atualizado_em)
  ```
  Cada tela declara sua `chave_tela`; o front busca `GET /api/v1/ajuda/{chave_tela}` (ou `/api/publico`, `/api/admin` conforme a superfície) e renderiza o painel. `url_video` **NULL** ⇒ o botão "Assistir vídeo" aparece como *"em breve"* (a tela já nasce **preparada** para o vídeo, mesmo antes de ele existir).
- **Acessibilidade:** o gatilho de ajuda tem `aria-label`; o painel é focável e fecha com `Esc`; o link de vídeo abre em nova aba com `rel="noopener"`.
- **Rastreabilidade spec-driven:** cada **Spec de Feature** (§5) descreve a ajuda da(s) sua(s) tela(s) — ver o campo *"Ajuda da tela"* no template. O critério de aceitação correspondente vira teste (P5): a tela expõe o gatilho de ajuda e carrega o conteúdo da `chave_tela`.

---

# 4. Fases e Tasks (macro)

**Fase 0 — Fundação (1–2 semanas):** repositório mono (api/ web/ admin/ site/), Docker Compose (db=`niner_db`), CI (build + testes + Testcontainers), esqueleto Spring Boot com módulos **incluindo `plataforma`** e as 3 superfícies (`/api/publico`, `/api/v1`, `/api/admin`), Flyway com a **infra multi-tenant** (roles `niner_app`/`niner_owner`, `TenantContext`, RLS `FORCE` — migrations V001–V091 §3.5.1), auth JWT com claim `tid` + `aud`, **signup/trial** self-service (R12/R20) e layout base dos fronts seguindo o design system (§3.7). *Gate:* teste de isolamento (P8) verde — um tenant nunca lê dado de outro.

> **Roadmap comercial** (waitlist → design partners → closed/open beta com cobrança → GA) em `docs/PLANO-DE-NEGOCIO.md`, alinhado a estas fases.

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

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)
[para cada tela nova/alterada: `chave_tela`; objetivo; passo a passo do manual; erros comuns; url_video (ou "a gravar"). A tela deve nascer preparada para o vídeo mesmo sem ele.]

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

ADRs já previstos: ADR-001 monolito modular; ADR-002 outbox sobre Postgres sem broker; ADR-003 biblioteca de UI; **ADR-004 estratégia de reserva de estoque: reservar no `recebido` + expiração configurável por canal (Q2 fechada 2026-07-10; §3.3.5);** ADR-005 cifragem de credenciais; **ADR-006 isolamento de tenant (banco único + `id_tenant` + Postgres RLS; §3.1.1); ADR-007 topologia do control-plane (uma API/3 superfícies + 3 apps React, API stateless, supera o non-goal §2.3, gatilhos de split); ADR-008 adapter de gateway de cobrança (provedor a definir — D3); ADR-009 auth/identidade multi-tenant (duas populações de usuário, claims JWT por `aud`, papéis de staff × RBAC do tenant); ADR-010 financeiro do lojista fora do v1 (Q5 fechada 2026-07-10, **revisado por ADR-012**; §3.3.7); ADR-011 framework do site público: **Astro (SSG)** — decidido 2026-07-10, prioriza SEO/Core Web Vitals para a landing/planos; `web`/`admin` seguem React+Vite; **ADR-012 crediário + caixa + contas a pagar antecipados da Fase 2 (revisão de Q5/ADR-010, 2026-07-16, em duas rodadas) — `tipo_carteira`/`moeda`/`contas_receber`/`caixa_mestre`/`caixa_detalhe` entram via V025; `contas_pagar` entra via V026; só `conta_corrente(_movimento)` continua fora (§3.3.7).**

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
- [ ] Nenhuma violação da Constituição (P1–P9)
- [ ] Multi-tenant: recurso isolado por `id_tenant` + política RLS (P8); nada de plataforma misturado com dado de lojista (P9); toda FK nova para outra tabela de domínio é **composta** `(id_tenant, id_x)`, nunca simples (§3.1.1, 2026-07-16)
- [ ] Toda tela nova/alterada tem ajuda (manual de operação) + acesso a vídeo, com `chave_tela` definida (R22 / §3.7.1)
- [ ] Questões bloqueantes respondidas
