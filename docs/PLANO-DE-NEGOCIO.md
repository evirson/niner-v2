# Plano de Negócio — Nainer (ERP SaaS multicanal para varejo)

**Empresa:** Vetor Sistemas · **Produto:** Nainer · **Banco:** `niner_db` · **Versão:** 1.0 (pivô SaaS)
**Relação com a spec técnica:** este documento é **comercial** e complementa (não substitui) o `spec-driven-erp-varejo.md` (v2.0). Convenção: itens 🔴 = decisão pendente.

> **Conceito organizador — dois planos (planes), nunca conflar:**
> - **Plano de Controle ("Plataforma Nainer")** — o negócio da Vetor: tenants, planos, assinaturas, faturas, cobrança, suporte. Tabelas globais (`plataforma.*`).
> - **Plano do Inquilino ("ERP do lojista")** — catálogo, estoque, pedidos, canais e o **financeiro interno do lojista** (caixa/crediário legado). Isolado por `id_tenant`.
> Regra de vocabulário: *assinatura/plano/trial/mensalidade/gateway* = **plataforma**; *caixa/crediário/contas a pagar-receber/conta corrente da loja* = **financeiro do lojista**. Ver o Anexo A.

---

## 1. Sumário executivo

Nainer é um **ERP em nuvem (SaaS por assinatura)** da Vetor Sistemas para o **pequeno varejista brasileiro** que vende em loja física e em 2–5 marketplaces (Mercado Livre, Shopee, Amazon, loja própria). Entrega uma **fonte única de verdade de estoque e preço**, com sincronização automática entre canais e **zero overselling**. Aquisição **self-service** pelo site público com **plano Gratuito sem prazo de validade** (até 100 vendas/mês); receita por **assinatura recorrente** mensal/anual em **faixas de volume de vendas** (ADR-015), cobradas via **Mercado Pago** (ADR-016).

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

- **Assinatura recorrente SaaS** (mensal e anual), preço por **faixa de volume de vendas emitidas/mês** — somando todas as empresas (CNPJs) do tenant.
- **Uma única dimensão medida.** Nenhum recurso é vendido à parte: fiscal (NFC-e/NF-e), canais, usuários, produtos, CNPJs e telas são **idênticos** no gratuito e no pago. *Paga-se volume, não funcionalidade* (ADR-015).
- **Cobrança via Mercado Pago** (ADR-016): **PIX avulso mensal** ou **recorrência automática** no cartão. Boleto fora do v1.
- **Anual com 15% de desconto** sobre 12 mensalidades.
- **Nunca bloquear no susto:** ao estourar a cota o lojista ainda emite uma **tolerância** de vendas (parâmetro da Vetor) antes de qualquer bloqueio, com aviso desde 80% da cota.
- Expansão de receita: subida de faixa conforme o lojista cresce — sem negociação, sem migração de plano manual.

## 5. Planos e preços — faixas por volume (ADR-015)

**Plano Gratuito — sem prazo de validade.** Até **100 vendas/mês** (o contador zera na virada do mês), com **todas** as funcionalidades liberadas, inclusive o módulo fiscal, e **CNPJs, usuários e produtos ilimitados**. Não existe data de expiração: quem vende pouco usa de graça para sempre.

**Faixas pagas — geradas por fórmula, não digitadas.** A faixa *n* cobre `passo × n` vendas/mês e custa:

```
mensal(n) = min( preco_maximo ,  preco_base × (1 + f + f² + … + f^(n-1)) )
anual(n)  = mensal(n) × 12 × (1 − desconto_anual)
```

- `f = 1` → crescimento **linear** (`n × preco_base`: 2ª faixa custa 2×, 3ª custa 3×…).
- `f < 1` → **atenuação**: cada faixa acrescenta menos que a anterior (ex.: `f = 0,8` → 1,00 / 1,80 / 2,44 / 2,95 × `preco_base`).
- `preco_maximo` é o **teto**: da faixa em que for atingido em diante, o preço não sobe mais.

**Parâmetros (tabela `plataforma.parametro_comercial`, editáveis no backoffice — mudar preço não é deploy):**

| Parâmetro | Significado | Valor |
|---|---|---|
| `vendas_gratuito_mes` | Cota do plano Gratuito | **100** |
| `tolerancia_vendas` | Vendas extras permitidas depois de estourar a cota, antes do bloqueio | 🔴 **a definir** |
| `preco_base` | Mensalidade da 1ª faixa (500 vendas/mês) | 🔴 **a definir** |
| `passo_vendas` | Tamanho da faixa | **500** |
| `fator_faixa` | `1,000` = linear · `< 1` = atenuação | 🔴 **a definir** |
| `preco_maximo` | Teto de mensalidade | 🔴 **a definir** |
| `vendas_maximo` | Última faixa gerada (acima disso: "fale conosco") | 🔴 **a definir** |
| `desconto_anual` | Desconto do pagamento anual | **15%** |

*Exemplo ilustrativo* com `preco_base = R$ 99`, `f = 1` e teto `R$ 990`: 500 → R$ 99 · 1.000 → R$ 198 · 1.500 → R$ 297 · 5.000 → R$ 990 (teto) · acima → R$ 990. Com `f = 0,8`: 500 → R$ 99 · 1.000 → R$ 178,20 · 1.500 → R$ 241,56. **Os números da tabela acima são o que decide** — este exemplo não é compromisso de preço.

**Política de limite (R19, reescrita):**
- **Vendas/mês é a única dimensão medida.** Estruturais (canais, SKUs, usuários, CNPJs) ficam **ilimitados** em todos os planos.
- **Cota conta venda emitida; cancelamento não devolve.** Não contam: importação de dados legada e devolução.
- **Escalada de aviso:** 80% da cota (aviso) → 100% (aviso forte, entra na tolerância) → tolerância esgotada (**bloqueia só a emissão de venda nova**; login, relatórios, recebimentos e financeiro continuam funcionando).

## 6. Plano Gratuito (substitui o trial por tempo — ADR-015)

- **Sem prazo, sem cartão, sem data de expiração.** O trial de 60 dias (D2) foi **descontinuado**: prazo cria uma data em que o lojista perde acesso ao que já digitou, no produto em que migrar cadastro leva semanas e o fiscal exige certificado, CSC e homologação.
- **Produto inteiro liberado**, inclusive NFC-e/NF-e — o fiscal é o gancho de aquisição do pequeno varejo, não um item de plano.
- **O que limita é volume:** 100 vendas/mês. Quem cresce, paga; quem não cresce, fica.
- **Conversão acontece no momento certo** — quando a loja já depende do sistema e estoura a cota, com upgrade em um clique dentro do próprio ERP (Mercado Pago, ADR-016).
- **Dados nunca são apagados por falta de pagamento**: a assinatura vencida bloqueia emissão de venda nova, não o acesso ao histórico (D10 segue em aberto para o caso `INADIMPLENTE` de plano pago).

## 7. Funil de aquisição

`Visitante do site → Lead → Cadastro (signup) → Conta Gratuita ativa → Ativação (aha) → Estouro de cota → Faixa paga → Expansão de faixa`

- **Visitante → lead (ADR-017):** todo pageview, clique de WhatsApp/Instagram e envio de formulário é medido com rastreamento **próprio** (first-party), amarrado por `visitante_id` até o signup.
- **Cadastro (R12):** nome da loja + seu nome + e-mail + senha → cria tenant **ATIVO no plano Gratuito** (sem cartão, sem prazo).
- **Ativação (aha moment, ligado a O5):** dentro do trial o tenant (a) cadastrou **≥ 10 produtos**, (b) conectou **≥ 1 canal** via OAuth e (c) teve **≥ 1 pedido importado** *ou* **≥ 1 sync estoque→canal** confirmado. É a métrica-líder de conversão.
- **Conversão paga (R14):** disparada pelo **estouro da cota** (não por uma data) — escolha da faixa + PIX/recorrência no Mercado Pago + 1ª cobrança aprovada.
- **Retenção/Expansão (R15):** renovação + upgrade de plano.

## 8. Métricas SaaS a acompanhar

- **Receita:** MRR, ARR, ARPA, NRR (meta ≥ 100%).
- **Aquisição/conversão:** visitante→signup; signup→trial ativo; **trial→paid (meta ≥ 20%)**; tempo até ativação.
- **Ativação:** % de trials no aha moment ≤ 7 dias (meta ≥ 60%); tempo mediano até 1º canal conectado (< 30 min, reforça O5).
- **Retenção:** churn de logos e de receita (mensal, meta ≤ 4%); retenção 90 dias (≥ 80%, já em §2.6 da spec).
- **Unit economics:** CAC, LTV, LTV:CAC (meta ≥ 3), payback de CAC (≤ 12 meses).
- **Cobrança:** inadimplência mensal (meta ≤ 5%); recuperação por dunning (meta ≥ 50% das falhas de cartão).

## 9. Go-to-market (resumo)

- **Motion primário: Product-Led Growth** — conta gratuita self-service pelo site, sem cartão e sem prazo.
- **Inbound/SEO/conteúdo:** "como não vender sem estoque no Mercado Livre", "integrar Shopee e loja física".
- **Comunidades de sellers** (grupos ML/Shopee, YouTube de e-commerce); presença na loja de aplicações do Mercado Livre.
- **Parcerias com contadores/escritórios** (canal de indicação) e **programa de indicação (referral)** entre lojistas.
- **Outbound leve** para o ICP na fase de piloto (design partners).
- **Medição própria do funil (ADR-017):** `lead`, `visita_site` e `evento_marketing` no control-plane, com UTM persistido da entrada até o signup. O **gerenciador de marketing** (no backoffice `admin/`) responde, sem ferramenta externa: de onde vieram as visitas, quais campanhas geraram lead, quantos leads viraram conta gratuita, quantas contas gratuitas viraram faixa paga e **quanto de MRR** cada origem produziu.
- **WhatsApp e Instagram são canais medidos**, não links soltos: cada clique vira evento, e é o principal sinal de intenção deste público.

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
| D1 | Modelo de preço | ✅ **Fechada (2026-08-18, ADR-015):** Gratuito até 100 vendas/mês + faixas por volume geradas por fórmula, anual −15%. 🔴 Falta só **preencher os parâmetros** (`preco_base`, `fator_faixa`, `preco_maximo`, `vendas_maximo`, `tolerancia_vendas`) — são dado, não código. |
| D2 | Trial por tempo | ⛔ **Superada (2026-08-18, ADR-015)** — não há mais trial: o plano Gratuito não expira, limita volume. |
| D3 | Gateway de cobrança | ✅ **Fechada (2026-08-18, ADR-016):** **Mercado Pago** — PIX avulso mensal + recorrência (preapproval), atrás da interface `GatewayCobranca`. Integração já dominada pela equipe (`ecommerce-revo`, `s7classificados`). |
| D4 | Multi-CNPJ por tenant | ✅ **Revisada (2026-08-18, ADR-015):** **ilimitado em todos os planos**, criado pelo próprio ADMIN no painel. Não é recurso de plano — a cota de vendas soma todos os CNPJs. |
| D5 | Nome comercial "Nainer" + domínio do site | 🔴 Confirmar |
| D6 | NFS-e da assinatura (Vetor→lojista): emissor/município | 🔴 Aberta |
| D7 | Overage | ✅ **Revisada (2026-08-18):** vendas/mês tem **tolerância configurável** antes do bloqueio; pedido de marketplace importado nunca é descartado (regra original mantida). |
| D8 | Régua de dunning (avisos D+1/D+3/D+7; suspensão ~15d; graça 7d) | 🔴 Confirmar |
| D9 | Metas numéricas (MRR, trial→paid, churn) para GA+6m | 🔴 Aberta |
| D10 | Estado `INADIMPLENTE`: ERP em modo leitura/aviso vs bloqueio total | 🔴 Aberta (regra do gate de login) |
| D11 | Rastreamento de aquisição | ✅ **Fechada (2026-08-18, ADR-017):** first-party próprio no control-plane; GA4/Meta Pixel só sob consentimento e nunca como fonte de verdade. |
| D12 | Identidade visual e domínio do site novo (landing de venda) | 🔴 Aberta — ver `docs/site/briefing-landing.md` |
