# Spec: Minha Conta (plano, uso e empresas)            Status: Implementada (2026-08-18)
Autor: Evirson (dono do produto) · Data: 2026-08-18 · Módulo(s): `plataforma` (uso/assinatura) + `identidade` (empresa) · Fase: 1 — Núcleo do ERP

## Problema

O ADR-015 troca o trial por tempo por um **plano Gratuito sem prazo, limitado a 100 vendas/mês**.
Isso cria duas perguntas que o lojista **não tem hoje onde responder**:

1. *"Quanto ainda posso vender antes de acabar a gratuidade?"* — sem um medidor visível, o
   primeiro sinal do limite seria a venda recusada no balcão, com cliente na frente. Inaceitável.
2. *"Como coloco meu segundo CNPJ aqui dentro?"* — `empresa` sempre suportou `tenant 1:N empresa`,
   mas **não existe `POST /api/v1/empresas`**: até hoje filial era inserida por SQL direto pela
   equipe (registrado em `docs/telas/empresa.md`). Com CNPJ ilimitado em todos os planos (D4
   revisada), isso vira operação de autoatendimento.

Não existe nenhuma tela no ERP que fale de **assinatura**: o `web/` inteiro é o plano do
inquilino. Esta é a primeira — e a fronteira precisa ficar explícita, porque o vocabulário se
confunde com o financeiro do lojista (Anexo A do plano de negócio): aqui é *o que a loja paga à
Vetor*, nunca *o caixa da loja*.

## Solução proposta

Tela única em `/minha-conta`, **ADMIN-only** (mesmo critério de `configuracao.geral` e
`identidade.empresa`), montada em quatro blocos numa página só — sem abas, sem paginação:

**1) Plano atual.** Nome da faixa, preço, ciclo e, no Gratuito, a frase que importa: *"Sem prazo
de validade — o que limita é o volume de vendas do mês."* Nada de contagem regressiva de dias.

**2) Uso do mês (o medidor).** Barra + números grandes: `X de 100 vendas usadas`, `faltam Y`, e a
data em que o contador zera (1º dia do mês seguinte). Três estados visuais:

| Estado | Quando | Cor / mensagem |
|---|---|---|
| Normal | `< 80%` da cota | `--accent`; só informativo |
| Atenção | `≥ 80%` e `< 100%` | `--warning`; "faltam Y vendas para o limite do mês" |
| Em tolerância | cota estourada, tolerância ainda disponível | `--danger`; "limite atingido — você ainda pode emitir Z vendas; assine uma faixa para não parar" |
| Bloqueado | tolerância esgotada | `--danger`; "emissão de venda bloqueada" + botão **Assinar** (a partir da fase 6, leva ao Mercado Pago) |

O medidor soma **todas as empresas do tenant** — e diz isso na tela, senão o lojista com 2 CNPJs
acha que a cota é por CNPJ.

**3) Histórico.** Últimos 12 meses (`plataforma.uso_venda_mes`), como barras simples, com a
**faixa recomendada** derivada do maior mês do período — é o que evita o cliente assinar a faixa
errada e voltar bloqueado no mês seguinte.

**4) Empresas (CNPJs).** Grade com código, razão social, CNPJ, cidade/UF e situação, botão
**Adicionar empresa** (modal enxuto) e o link para *Dados da Empresa* completar o resto. A grade
repete o padrão de cadastro (§3.7), sem paginação — "no máximo poucas dezenas", como já
documentado em `EmpresaService.listar()`.

**Modal de inclusão** pede só o indispensável: **Razão Social** (obrigatório), Nome Fantasia e
**CNPJ** (opcional, validado como **alfanumérico** — IN RFB 2.229/2024, reusando
`somenteAlfanumerico`/`cnpjValido`; jamais um limpador só-dígitos). O resto (endereço, IE, IM,
IBGE, CNAE) é preenchido depois na tela Dados da Empresa — mesma filosofia de "salvar incompleto
é permitido; quem cobra é a Conformidade Fiscal".

O que o servidor faz ao criar a empresa, numa transação: calcula `codigo_empresa` = próximo do
tenant (respeita `empresa_codigo_empresa_uk`), grava `matriz = false` a partir da segunda,
preenche `cfg_nome_etiqueta` com o modelo padrão (é `NOT NULL` sem default), cria o vínculo
`usuario_empresa` do ADMIN que criou e incrementa `uso_tenant.qtd_empresas`. **Não** replica plano
de contas, formas de pagamento nem perfis fiscais — todos são **por tenant** e já existem.
`fiscal_config_empresa` continua nascendo só quando o lojista ligar o fiscal naquela empresa (F12).

> ⚠️ A empresa nova só aparece como opção de operação **no próximo login** (o login em duas voltas
> já trata múltiplas empresas — `docs/telas/login-empresa.md`). A tela precisa dizer isso na hora
> em que a empresa é criada; trocar de empresa sem deslogar é feature separada, fora desta spec.

## Contrato de API

```
GET /api/v1/minha-conta                      (ADMIN)
200 {
  plano:    { nome, gratuito, precoMensal, precoAnual, ciclo, limiteVendasMes },
  uso:      { competencia: "2026-08", qtdVendas, limite, restantes,
              tolerancia, toleranciaRestante, situacao: "NORMAL|ATENCAO|TOLERANCIA|BLOQUEADO",
              zeraEm: "2026-09-01" },
  historico: [ { competencia: "2026-07", qtdVendas } ],   // 12 meses
  faixaRecomendada: { nome, limiteVendasMes, precoMensal, precoAnual } | null,
  empresas: [ { idEmpresa, codigoEmpresa, razaoSocial, nomeFantasia, cnpj, cidade, estado, ativo, matriz } ]
}

POST /api/v1/empresas                        (ADMIN)
{ razaoSocial*, nomeFantasia?, cnpj? }
201 { idEmpresa, codigoEmpresa, razaoSocial, nomeFantasia, cnpj, ... }
409 se o CNPJ já existe no tenant (empresa_cnpj_uk) · 422 se o CNPJ é inválido
```

Erros em Problem Details (RFC 9457). O tenant vem **sempre** do claim `tid` (nunca do body), e
toda query filtra `id_tenant` explicitamente no texto do SQL (P8) — inclusive as de `plataforma.*`,
que embora sejam globais são consultadas por `id_tenant` vindo do JWT.

## Critérios de aceitação (viram testes)

1. **Dado** tenant no Gratuito com 40 vendas no mês, **quando** abre Minha Conta, **então** vê
   "40 de 100", 60 restantes e situação `NORMAL`.
2. **Dado** tenant com 80 vendas, **quando** abre, **então** situação `ATENCAO`.
3. **Dado** tenant com 100 vendas e tolerância 20, **quando** emite a 101ª venda no PDV,
   **então** a venda é **aceita** e a situação vira `TOLERANCIA`.
4. **Dado** tenant com 120 vendas (cota 100 + tolerância 20), **quando** tenta emitir outra,
   **então** recebe **409** com `type` de limite e a venda **não** é gravada.
5. **Dado** tenant com 100 vendas em agosto, **quando** o relógio vira para setembro,
   **então** a primeira venda de setembro é aceita, `qtd_vendas_mes` volta a 1 e agosto aparece
   fechado com 100 em `uso_venda_mes`.
6. **Dado** tenant com duas empresas, **quando** cada uma emite 60 vendas, **então** o contador
   do tenant marca 120 (a cota é do tenant, não do CNPJ).
7. **Dado** uma venda emitida e depois **cancelada**, **quando** consulta o uso, **então** a cota
   **continua** consumida (ADR-015: cancelamento não devolve).
8. **Dado** uma importação de dados legada que insere 500 vendas históricas, **quando** consulta
   o uso, **então** o contador **não** se altera.
9. **Dado** ADMIN, **quando** cria empresa com razão social nova, **então** recebe 201 com
   `codigoEmpresa` = maior do tenant + 1, e a empresa nasce vinculada a ele em `usuario_empresa`.
10. **Dado** OPERADOR, **quando** chama `GET /api/v1/minha-conta` ou `POST /api/v1/empresas`,
    **então** recebe 403.
11. **Dado** dois tenants, **quando** o tenant A consulta Minha Conta, **então** o uso e as
    empresas do tenant B **não** aparecem (P8 — teste de isolamento explícito).

## Como ficou implementado (2026-08-18)

Duas diferenças em relação ao que esta spec previa, ambas deliberadas:

1. **Cota: uma chamada, não duas.** A spec descrevia `garantirPodeVender()` + `registrarVenda()`.
   O código faz as duas coisas em `LimiteVendasService.registrarVenda()`, porque entre checar e
   incrementar existiria uma janela em que duas vendas simultâneas passariam pelo mesmo último
   slot da cota. O `INSERT … ON CONFLICT DO UPDATE … RETURNING` incrementa e **trava a linha** do
   tenant até o commit; se o total estourar, a exceção derruba a transação da venda e o
   incremento vai junto no rollback.
2. **Sem assinatura viva ou plano sem limite ⇒ passa.** Falta de dado no control-plane nunca
   bloqueia a loja: `limite = NULL` é tratado como ilimitado.

Arquivos: `plataforma/uso/{LimiteVendasService,MinhaContaService,MinhaContaController,
UsoTenantService}.java`, `identidade/empresa/EmpresaService.criar`, `web/src/pages/plataforma/
MinhaConta.tsx`, migrations `V037`/`V038`. Testes: `CotaVendasTest` (12 casos, verdes).

## Impacto no banco

`V037`/`V038` (ver §3.5.1): `plataforma.parametro_comercial`, colunas novas em `plano`,
`uso_tenant.competencia_vendas/qtd_vendas_mes/qtd_empresas`, `uso_venda_mes`,
`assinatura.tolerancia_vendas` (override por cliente; NULL = usa o parâmetro global).

## Ajuda da tela (R22 / §3.7.1)

Texto curto explicando: (a) o que conta como venda para a cota; (b) que cancelar **não** devolve
cota; (c) que a cota é do tenant e soma todos os CNPJs; (d) o que acontece ao estourar (aviso →
tolerância → bloqueio só da emissão de venda); (e) que dado nenhum é apagado. Vídeo: `url_video`
NULL ⇒ "em breve".

## Non-goals desta feature

- Pagamento/checkout (fase 6, ADR-016) — nesta entrega o botão **Assinar** só existe quando as
  credenciais do Mercado Pago estiverem configuradas.
- Trocar de empresa sem deslogar.
- Editar dados fiscais da empresa aqui (é a tela *Dados da Empresa*).
- Excluir empresa (não existe caminho seguro com movimento gravado; desativar é o que existe).
- Cancelar a própria assinatura (backoffice, R15).

## Questões abertas

- 🔴 O aviso de 80% deve aparecer **também no PDV** (faixa no topo), ou só aqui? Recomendação:
  também no PDV a partir de 90% — é onde o operador está quando o limite chega.
- 🔴 Tolerância: mesma para todos os tenants (parâmetro global) ou negociável por cliente
  (`assinatura.tolerancia_vendas`)? A coluna já entra prevendo o segundo caso.

## Métrica de sucesso

Nenhum lojista descobre o limite pela venda recusada: 100% dos bloqueios precedidos de pelo menos
um acesso à tela com aviso de 80%/100% registrado.

---

## Revisão 2026-08-22 — cobrança: idempotência e rede fora da transação (auditoria)

### Item 7 — o lock era solto antes do processamento

O javadoc de `CobrancaWebhookProcessador` promete que a separação em dois beans impede que "dois
workers peguem o mesmo evento" — mas a transação de `pegarLote` **commita ao retornar**, soltando os
locks do `FOR UPDATE SKIP LOCKED`; `processar` roda depois em `REQUIRES_NEW`, sem lock.

Todo o `aplicar()` é idempotente por construção — **exceto o `UPDATE` da assinatura**, que empurrava
`proxima_cobranca` mais um ciclo **a cada reaplicação**: uma notificação duplicada dava um mês (ou um
ano, no plano anual) de graça, em silêncio.

**Como ficou.** O `UPDATE` da fatura (`AND status <> 'PAGA'`) virou a **trava de idempotência**: ele
casa exatamente uma vez por fatura, e só então a assinatura é promovida. A trava é a fatura, não uma
comparação de datas em `proxima_cobranca` — o valor novo depende do ciclo, e comparar data acerta
por acaso. Vale porque este é o **único** ponto do sistema que marca fatura como PAGA (conferido).

⚠️ **Reachability honesta:** com uma instância de API e `@Scheduled`, isso não acontecia hoje. Vira
real no dia em que existirem duas instâncias — cenário que o `CLAUDE.md` prevê.

### Item 23 — chamada ao Mercado Pago dentro da transação

`CobrancaService.iniciarPagamento` chamava `gateway.criarPix(...)` **dentro** da sua
`@Transactional`, violando o F2 que o módulo fiscal inteiro respeita. Se a transação desse rollback
**depois** de o PIX ser criado, ele existiria no gateway apontando para uma fatura inexistente: o
cliente pagaria, o webhook não acharia nada, e sobraria um `log.warn`. **Dinheiro recebido,
assinatura não promovida.**

**Como ficou.** `iniciarPagamento` deixou de ser transacional e passou a **orquestrar**: transação →
rede → transação. As duas metades vivem no bean novo `CobrancaFaturaTransacional`.

⚠️ **Bean separado é obrigatório, não estilo:** método `@Transactional` chamado de dentro do próprio
bean não passa pelo proxy do Spring e rodaria **sem transação nenhuma** — o `INSERT` da fatura em
autocommit, e a divisão não teria servido para nada. Mesmo padrão de `CobrancaWebhookJob` ×
`CobrancaWebhookProcessador`.

Com a divisão, a fatura já está **commitada** quando o PIX é criado. Se a segunda metade falhar, o
pior caso é uma fatura ABERTA sem código gravado — e o webhook ainda acha a fatura pela referência
`fatura-<id>`. Estritamente melhor que antes.

### Item 27 — ⏸️ estorno e chargeback NÃO revogam a assinatura

⚠️ **Dívida conhecida e aceita** (2026-08-22), com pedido explícito do dono do produto: *"documente e
avise sempre que formos revisar o ERP à procura de falhas."*

`refunded`/`charged_back` viram `ESTORNADO`, e o `if (situacao != CONFIRMADO) return;` — escrito
para FALHOU/PENDENTE, casos em que a fatura **nunca** foi paga — engole também esse. Só que no
estorno a fatura **está** paga. Fica:

| Registro | Como fica |
|---|---|
| `pagamento.status` | ESTORNADO ✔ (única coisa que muda) |
| `fatura.status` | **PAGA** ✗ |
| `assinatura.status` | **ATIVA** ✗ |
| `assinatura.proxima_cobranca` | **empurrada um ciclo à frente** ✗ |

Custo: lojista paga o plano **anual** por PIX, obtém o estorno semanas depois, e nenhuma fatura nova
é gerada por **doze meses** — com o backoffice mostrando tudo em dia.

⚠️ **O que reduz a urgência (medido, não suposto):** **nenhum status corta acesso hoje.** O login
(`SignupService.login`) confere slug, e-mail, senha, `usuario.ativo` e horário de acesso — **não**
olha `tenant.status` nem `assinatura.status`, e não há um único uso de `SUSPENSA`/`INADIMPLENTE` em
`api/src/main`. Os status são **rótulo no backoffice, não fechadura**. O prejuízo é financeiro
(cobrança que deixa de ser gerada), não de acesso indevido.

⚠️ **Quando for corrigir, são TRÊS passos:** (1) reabrir a fatura; (2) **recuar `proxima_cobranca`**
para o valor anterior — é este que se esquece, e é o que mais custa; (3) decidir o estado da
assinatura, que é **política comercial da Vetor**, não decisão técnica.
