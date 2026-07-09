# Plano de Negócio — Niner (ERP SaaS multicanal para varejo)

**Empresa:** Vetor Sistemas · **Produto:** Niner · **Banco:** `niner_db` · **Versão:** 1.0 (pivô SaaS)
**Relação com a spec técnica:** este documento é **comercial** e complementa (não substitui) o `spec-driven-erp-varejo.md` (v2.0). Convenção: itens 🔴 = decisão pendente.

> **Conceito organizador — dois planos (planes), nunca conflar:**
> - **Plano de Controle ("Plataforma Niner")** — o negócio da Vetor: tenants, planos, assinaturas, faturas, cobrança, suporte. Tabelas globais (`plataforma.*`).
> - **Plano do Inquilino ("ERP do lojista")** — catálogo, estoque, pedidos, canais e o **financeiro interno do lojista** (caixa/crediário legado). Isolado por `id_tenant`.
> Regra de vocabulário: *assinatura/plano/trial/mensalidade/gateway* = **plataforma**; *caixa/crediário/contas a pagar-receber/conta corrente da loja* = **financeiro do lojista**. Ver o Anexo A.

---

## 1. Sumário executivo

Niner é um **ERP em nuvem (SaaS por assinatura)** da Vetor Sistemas para o **pequeno varejista brasileiro** que vende em loja física e em 2–5 marketplaces (Mercado Livre, Shopee, Amazon, loja própria). Entrega uma **fonte única de verdade de estoque e preço**, com sincronização automática entre canais e **zero overselling**. Aquisição **self-service** pelo site público com **trial gratuito de 14 dias sem cartão**; receita por **assinatura recorrente** mensal/anual em 3 planos.

## 2. Proposta de valor

> *"Cadastre o produto uma vez, venda em todos os canais, nunca venda o que não tem."*

- **Dor:** planilhas + N painéis de marketplace → venda sem estoque, cancelamento, queda de reputação, horas diárias de conciliação manual.
- **Ganho:** estoque central mestre; sync < 2 min (O2); fila única de pedidos multicanal; **0 cancelamento por ruptura** (O1); onboarding de canal em < 30 min pelo próprio lojista (O5); auditabilidade de estoque e preço (P3).
- **Diferencial vs. concorrentes (Bling, Tiny, Olist):** foco em varejo com **loja física + marketplace na mesma fonte de verdade**, anti-overselling como princípio (P1) e simplicidade de operação para equipe pequena.

## 3. Segmento / ICP (perfil do lojista alvo)

- **Micro e pequeno varejista** brasileiro, **1 CNPJ**, faturamento ~R$ 20 mil–500 mil/mês.
- Vende em **loja física + 1 a 5 marketplaces**.
- **50–20.000 SKUs**; 1–15 pessoas na operação.
- Hoje usa planilha, painel nativo dos canais ou um ERP genérico que não trata bem o multicanal.
- **Fora do ICP por ora:** grandes contas com múltiplos CNPJs/depósitos (P2), quem precisa de PDV completo, quem exige emissão fiscal nativa.

## 4. Modelo de receita

- **Assinatura recorrente SaaS** (mensal e anual), preço por **plano/tier** limitado por uso.
- Cobrança predominante em **cartão de crédito recorrente**; **PIX/boleto** como alternativa. 🔴 Gateway a definir (D3) — no início, cobrança manual via adapter.
- **Anual com desconto** (~2 meses grátis).
- **Sem cobrança por transação/pedido** no v1 (previsibilidade). Overage vira **gatilho de upgrade**, não taxa avulsa.
- Expansão de receita: upsell de plano + 🔴 futuros add-ons (conectores extras, usuários adicionais, multi-CNPJ).

## 5. Planos e preços 🔴 (valores placeholder — decisão D1)

| Recurso / Limite | **Essencial** | **Profissional** | **Escala** |
|---|---|---|---|
| Preço mensal | **R$ 99** | **R$ 249** | **R$ 599** |
| Preço anual (~2 meses grátis) | R$ 990/ano (~R$ 82/mês) | R$ 2.490/ano (~R$ 208/mês) | R$ 5.990/ano (~R$ 499/mês) |
| Canais online simultâneos | 1 (+ loja física) | 3 | 5+ |
| Conectores incluídos | ML **ou** Shopee | ML + Shopee | Todos (ML, Shopee, Amazon, e-commerce) |
| SKUs (produtos × variações) | 500 | 5.000 | 50.000 |
| Usuários | 2 | 5 | 15 |
| Pedidos importados/mês | 300 | 2.000 | 10.000 |
| Painel de saúde de integrações (R7) | ✔ | ✔ | ✔ |
| Suporte | e-mail + base de conhecimento | e-mail prioritário | prioritário + onboarding assistido |
| Multi-CNPJ | — | — | 🔴 add-on / P2 |

**Política de limites (R19):**
- **Estruturais (canais, usuários, SKUs):** bloqueio *hard* ao exceder — "faça upgrade para conectar o 2º canal".
- **Pedidos/mês:** **nunca dropar pedido** (quebraria O1/O4 e a proposta de valor). *Soft-cap*: importa tudo, alerta em 80%/100% e exige upgrade se estourar 2 ciclos seguidos. Auditabilidade > receita marginal.

## 6. Período de trial (avaliação)

- **14 dias, sem cartão antecipado** (self-service, baixa fricção — decisão D2). ✅
- Trial dá acesso ao **plano Profissional** (mostrar o produto forte).
- **Cartão pedido só na conversão** (fim do trial ou ativação de plano pago).
- Fim do trial sem conversão → **modo leitura/graça (7 dias)**: dados preservados, sync pausado, sem exclusão imediata (R20). Reativa ao assinar.

## 7. Funil de aquisição

`Site público → Cadastro (signup) → Trial ativo → Ativação (aha) → Conversão paga → Retenção/Expansão`

- **Cadastro (R12):** e-mail + senha + nome da loja → cria tenant em TRIAL.
- **Ativação (aha moment, ligado a O5):** dentro do trial o tenant (a) cadastrou **≥ 10 produtos**, (b) conectou **≥ 1 canal** via OAuth e (c) teve **≥ 1 pedido importado** *ou* **≥ 1 sync estoque→canal** confirmado. É a métrica-líder de conversão.
- **Conversão paga (R14):** escolha de plano + checkout + 1ª cobrança aprovada.
- **Retenção/Expansão (R15):** renovação + upgrade de plano.

## 8. Métricas SaaS a acompanhar

- **Receita:** MRR, ARR, ARPA, NRR (meta ≥ 100%).
- **Aquisição/conversão:** visitante→signup; signup→trial ativo; **trial→paid (meta ≥ 20%)**; tempo até ativação.
- **Ativação:** % de trials no aha moment ≤ 7 dias (meta ≥ 60%); tempo mediano até 1º canal conectado (< 30 min, reforça O5).
- **Retenção:** churn de logos e de receita (mensal, meta ≤ 4%); retenção 90 dias (≥ 80%, já em §2.6 da spec).
- **Unit economics:** CAC, LTV, LTV:CAC (meta ≥ 3), payback de CAC (≤ 12 meses).
- **Cobrança:** inadimplência mensal (meta ≤ 5%); recuperação por dunning (meta ≥ 50% das falhas de cartão).

## 9. Go-to-market (resumo)

- **Motion primário: Product-Led Growth** — trial self-service pelo site.
- **Inbound/SEO/conteúdo:** "como não vender sem estoque no Mercado Livre", "integrar Shopee e loja física".
- **Comunidades de sellers** (grupos ML/Shopee, YouTube de e-commerce); presença na loja de aplicações do Mercado Livre.
- **Parcerias com contadores/escritórios** (canal de indicação) e **programa de indicação (referral)** entre lojistas.
- **Outbound leve** para o ICP na fase de piloto (design partners).

## 10. Roadmap comercial (alinhado às Fases técnicas 0–4)

| Fase técnica | Marco comercial |
|---|---|
| **Fase 0 — Fundação** | Multi-tenancy no alicerce; landing page + captura de *waitlist*. Sem venda. |
| **Fase 1 — Núcleo ERP** | *Design partners* (2–3 lojistas), acesso gratuito/comodato. Site institucional. |
| **Fase 2 — Mercado Livre** | *Closed beta* com trial concedido; cobrança ainda manual/simbólica. Valida ativação. |
| **Fase 3 — Shopee** | *Open beta*: signup self-service ligado, **gateway de cobrança integrado**, primeiros pagantes. |
| **Fase 4 — Piloto→GA** | **Lançamento comercial:** 3 planos pagos ativos, funil completo, metas de MRR e trial→paid. |

## 11. Projeções e metas 🔴 (placeholder — decisão D9)

- Meta **GA + 6 meses:** N assinantes pagantes; MRR alvo; trial→paid ≥ 20%; churn mensal ≤ 4%. *(calibrar números.)*

---

## Anexo A — Financeiro do Lojista × Faturamento da Plataforma (distinção crítica)

Os dois **nunca** se misturam (P9).

| Dimensão | **Financeiro do Lojista** (Tenant Plane) | **Faturamento da Plataforma** (Control Plane) |
|---|---|---|
| Pergunta que responde | "Como vai o caixa/contas da **minha loja**?" | "Quanto o lojista deve à **Vetor** pela assinatura?" |
| Quem usa | Persona A/B (dono, operador) | Persona E/C (financeiro e super-admin da Vetor); Persona F vê as próprias faturas |
| Módulo/escopo | `financeiro` legado (§3.3.7): `caixa_*`, `contas_receber`, `contas_pagar`, `conta_corrente`, crediário — **Q5 em aberto** | `plataforma.*` (§3.3.11): `plano`, `assinatura`, `fatura`, `pagamento`, `webhook_gateway` |
| Fluxo de dinheiro | Clientes **do lojista** → caixa do lojista | Lojista → **Vetor** (via gateway) |
| Multi-tenant? | Sim, isolado por `id_tenant` | É *sobre* os tenants; global, staff-only |
| Gateway externo | Não (registro interno de caixa) | Sim (cartão recorrente/PIX/boleto) |
| Documento fiscal | NF-e/NFC-e das vendas → **non-goal** do produto | **NFS-e da assinatura** Vetor→lojista → obrigação da plataforma (D6) |

Regra prática: qualquer coisa com *assinatura/plano/trial/mensalidade/gateway* = **plataforma**; qualquer coisa com *caixa/crediário/contas a pagar-receber/conta corrente da loja* = **financeiro do lojista**.

---

## Anexo B — Decisões de negócio em aberto (D1–D10)

| # | Decisão | Situação / recomendação |
|---|---------|-------------------------|
| D1 | Preços dos 3 planos + desconto anual | 🔴 Confirmar (placeholders R$ 99 / 249 / 599; anual ~2 meses grátis) |
| D2 | Trial: 14 dias, sem cartão, expondo o Profissional | ✅ Decidida |
| D3 | Gateway de cobrança (PIX/boleto/cartão recorrente) | 🔴 **Adiada** — adapter abstrato; cobrança manual no início; candidatos BR: Asaas, Iugu, Vindi, Pagar.me |
| D4 | Multi-CNPJ por tenant como recurso de plano / P2 | ✅ Decidida (1 CNPJ/tenant no v1) |
| D5 | Nome comercial "Niner" + domínio do site | 🔴 Confirmar |
| D6 | NFS-e da assinatura (Vetor→lojista): emissor/município | 🔴 Aberta |
| D7 | Overage de pedidos/mês: nunca descartar, só gatilhar upgrade | ✅ Recomendada (R19) |
| D8 | Régua de dunning (avisos D+1/D+3/D+7; suspensão ~15d; graça 7d) | 🔴 Confirmar |
| D9 | Metas numéricas (MRR, trial→paid, churn) para GA+6m | 🔴 Aberta |
| D10 | Estado `INADIMPLENTE`: ERP em modo leitura/aviso vs bloqueio total | 🔴 Aberta (regra do gate de login) |
