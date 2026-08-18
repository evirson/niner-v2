# Spec: Gerenciador de Marketing (backoffice)        Status: Rascunho — aguardando aprovação
Autor: Evirson (dono do produto) · Data: 2026-08-18 · Módulo(s): `plataforma` (aquisição) · App: `admin/` · Fase: 3 — Aquisição

## Problema

Com o plano Gratuito sem prazo (ADR-015), o funil deixa de ser "trial → pago" com data marcada e
passa a ser **visitante → lead → conta gratuita → faixa paga**, com **meses** entre as pontas.
Decidir marketing nesse cenário exige responder perguntas que nenhuma ferramenta externa consegue
responder sozinha, porque elas cruzam **visita** com **receita**:

- De onde vieram as visitas desta semana, e quais campanhas geraram lead?
- Quantos leads viraram conta gratuita? Em quantos dias, em média?
- Quantas contas gratuitas chegaram perto da cota (sinal de conversão próxima)?
- **Quanto de MRR** cada origem/campanha produziu — e qual custou mais caro por real gerado?

O GA4 não sabe o que é `plataforma.assinatura`; a planilha não escala. Daí o ADR-017: dado
first-party no mesmo banco da receita.

## Solução proposta

Três telas dentro do backoffice `admin/` (que **ainda não existe** — este spec pressupõe o app
scaffolded), todas **staff-only** (`aud=plataforma`, papéis `SUPER_ADMIN`/`FINANCEIRO`;
`SUPORTE` só leitura):

**1) Funil (visão geral)** — período selecionável (7/30/90 dias, ou intervalo), mostrando a
escada com taxa de conversão entre etapas: `visitas → visitantes únicos → leads → signups →
contas ativas (com ≥1 venda) → faixas pagas`. Ao lado, quebra por **origem** (`utm_source` /
referrer / direto) e por **campanha**, cada linha com signups e **MRR gerado**.

**2) Leads** — grade paginada (padrão do projeto: 50 por página, ordenação por coluna com
allowlist no servidor) com nome, e-mail, WhatsApp, loja, origem/UTM, primeira visita, status e
tenant vinculado quando já converteu. Filtros por status, origem, campanha e período. Clique
abre a ficha: linha do tempo do `visitante_id` (páginas vistas, cliques de WhatsApp/Instagram,
signup) e campo de anotação + mudança de status (`NOVO → CONTATADO → QUALIFICADO → CONVERTIDO |
PERDIDO`).

**3) Conteúdo e canais** — páginas mais vistas, termos de entrada, cliques de WhatsApp e
Instagram por seção da landing (é o `data-rotulo` do briefing), e horário/dia de maior tráfego —
o que orienta quando publicar.

**Sinal de conversão dentro do produto:** uma quarta visão, *"Contas perto do limite"*, lista os
tenants gratuitos com uso ≥ 80% da cota no mês corrente. É a lista de quem está prestes a
precisar pagar — e o momento certo do contato comercial, sem depender de o lojista pedir.

## Contrato de API

```
GET /api/admin/marketing/funil?de=&ate=          → etapas + taxas + quebra por origem/campanha
GET /api/admin/marketing/leads?...&pagina=&limite=   → grade paginada (filtros acima)
GET /api/admin/marketing/leads/{id}              → ficha + linha do tempo do visitante
PUT /api/admin/marketing/leads/{id}              → { status, anotacao }
GET /api/admin/marketing/conteudo?de=&ate=       → páginas, cliques por rótulo, horários
GET /api/admin/tenants/perto-do-limite           → tenants gratuitos com uso >= 80%
```

Escrita anônima (o beacon) **não** vive aqui: é `POST /api/publico/eventos`, rate-limited, e nunca
dispara efeito de negócio (ADR-017).

## Critérios de aceitação (viram testes)

1. **Dado** um visitante que chegou por `utm_source=instagram`, navegou, clicou no WhatsApp e
   fez signup 5 dias depois em visita direta, **quando** o funil é consultado, **então** o signup
   é atribuído a `instagram` (UTM persistido na primeira visita, não na última).
2. **Dado** um lead que virou tenant e depois assinou uma faixa, **quando** o funil é consultado,
   **então** o MRR dessa assinatura aparece somado à origem do lead.
3. **Dado** um tenant gratuito com 85 de 100 vendas no mês, **quando** a lista "perto do limite"
   é consultada, **então** ele aparece; com 40 vendas, não aparece.
4. **Dado** um JWT de tenant (`aud=tenant`), **quando** chama qualquer rota `/api/admin/**`,
   **então** recebe 401/403 — a chain de staff rejeita token de tenant (R18/P9).
5. **Dado** um evento anônimo, **quando** é gravado, **então** não contém e-mail, telefone nem IP
   bruto (ADR-017).

## Impacto no banco

`V039` (§3.5.1): `plataforma.lead`, `plataforma.visita_site`, `plataforma.evento_marketing`.
Nenhuma tabela de tenant é tocada. Expurgo/agregação de `visita_site` ainda é questão aberta.

## Non-goals desta feature

- Disparo de e-mail/WhatsApp em massa (é ferramenta de campanha, não deste painel).
- Integração com Meta/Google Ads API.
- Atribuição multi-toque sofisticada — v1 usa **primeiro toque** (UTM da primeira visita), que é
  o que este funil longo comporta explicar.

## Questões abertas

- 🔴 Retenção de `visita_site`: expurgar após quantos meses, agregando o histórico em contagem
  diária? (Sem política, a tabela cresce sem teto.)
- 🔴 Filtro de bot/crawler: por user-agent conhecido no beacon, ou no gravador?
- 🔴 O painel deve mostrar **CAC** (exige lançar o gasto de campanha em algum lugar) ou fica só
  com volume e MRR por origem no v1?

## Métrica de sucesso

Decisão de marketing tomada olhando este painel — não uma planilha — dentro de 30 dias do
lançamento da landing nova.
