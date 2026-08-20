# Spec: Orçamento de Venda                                     Status: Aprovada
Autor: Claudio Calixto (dono do produto) + Claude · Data: 2026-08-20 · Módulo(s): `vendas` (orcamento) · Fase: 2 — Vendas/Financeiro

## Problema

O consumidor liga ou vai à loja, pergunta o preço de alguns produtos e **não fecha na hora**. Hoje
o vendedor anota num papel. O cliente some, volta três dias depois com o papel amassado, e não há
como saber quem atendeu, o que foi combinado, nem se aquele preço ainda vale.

## Solução proposta

Uma rotina de **orçamento** no grupo Frente de Loja: emite um documento com validade, imprime
(bobina e A4), envia por WhatsApp, e — quando o cliente volta — é puxado dentro do PDV e vira
venda, já com cliente e vendedor preenchidos.

⚠️ **Orçamento não é venda nem documento fiscal.** Não movimenta estoque, não reserva nada, não
gera contas a receber, não passa pelo caixa, não vai à SEFAZ e **não consome cota do plano**
(ADR-015: a única dimensão cobrada é venda emitida; cobrar pela captação desincentivaria a venda
que de fato é cobrada).

---

## Regras de negócio (todas fechadas com o dono do produto em 2026-08-20)

### R1 — O orçamento é imutável

Depois de emitido, **nada** muda na tela de Orçamento: nem itens, nem quantidades, nem valores,
nem validade. Quem quiser mudar **cancela e emite outro**.

É a mesma filosofia da venda, que também é imutável e se corrige cancelando. O custo consciente:
corrigir uma quantidade digitada errada custa o número do orçamento (o cliente que recebeu o nº 431
recebe um nº 432).

⚠️ Isto **reverte o item 10 do pedido original** ("possibilidade de alterar o orçamento"), por
decisão explícita do dono do produto na mesma sessão. A necessidade que o item 10 endereçava — o
cliente levar menos do que orçou — é resolvida pela R2, sem tornar o documento mutável.

### R2 — No PDV, só dá para diminuir quantidade

Ao puxar o orçamento no PDV, o operador pode **reduzir** a quantidade de qualquer item
(10 canetas → 8). **Reduzir a zero significa não levar aquele item.**

Nunca é possível: aumentar quantidade, alterar preço, alterar desconto ou acrescentar item ao
orçamento. (Acrescentar produto **à venda** é livre — é o PDV; esses itens simplesmente não fazem
parte do orçamento.)

### R3 — Preço congelado

O `preco_venda` de cada item é gravado na emissão e é ele que vale na efetivação, **relido do
banco** — nunca aceito do que a tela enviar.

É o que faz a data de validade significar alguma coisa: *"este preço vale até tal dia"*. Preserva
também a regra do PDV de que a tela nunca manda preço; o que muda é a fonte (o orçamento, em vez da
tabela do produto).

⚠️ A tela de consulta **mostra quando o preço atual do cadastro divergir** do congelado — informa,
não decide. Quem decide honrar é o operador.

**A descrição do produto NÃO é congelada** — resolve por JOIN, como a papeleta e o DANFE já fazem.
Preço é compromisso comercial; descrição é dado de cadastro, e congelá-la criaria uma segunda
verdade.

### R4 — Desconto: mesmo teto da venda

O orçamento pode ter desconto no total, limitado pelo **mesmo** `cfg_geral.percentual_desconto_venda`
que limita a venda, validado já na emissão. Guardado em reais.

Na efetivação o valor viaja para o campo de desconto do PDV e as regras dele valem a partir daí
(teto revalidado sobre o novo subtotal, rateio entre os itens). Consequência conhecida e aceita na
venda parcial:

| Desconto do orçamento | Cliente leva 8 de 10 | Resultado |
|---|---|---|
| R$ 10,00 de R$ 50,00 (20% — no teto) | subtotal R$ 40,00 | teto corta para R$ 8,00 → fica proporcional |
| R$ 5,00 de R$ 50,00 (10% — abaixo do teto) | subtotal R$ 40,00 | passa no teto → **carrega inteiro** |

Desconto de forma de pagamento (Tipo de Carteira) é assunto do PDV e não tem relação com o
orçamento.

### R5 — Os cinco estados

| Estado | Como se chega | Sai dele? |
|---|---|---|
| `ABERTO` | emissão | vira qualquer um dos outros |
| `VENDIDO` | efetivado levando **tudo** | **final** |
| `VENDIDO_PARCIAL` | efetivado levando **parte** | **final** |
| `CANCELADO` | operador cancelou, com motivo | **final** |
| `VENCIDO` | passou da validade | **final** |

⚠️ **`VENDIDO_PARCIAL` é estado final.** O cliente que volta para buscar o resto faz uma **venda
nova, sem vínculo nenhum com o orçamento** — não há saldo por item, não há segunda venda ligada ao
orçamento. Decisão explícita: *"como o cliente voltou, faz uma venda nova e pronto"*.

⚠️ **`VENCIDO` é estado próprio, separado de `CANCELADO`.** O pedido original dizia "cancela
automaticamente com o motivo CLIENTE NÃO VOLTOU"; virou estado próprio porque as duas perguntas são
diferentes num relatório: *quantos o vendedor cancelou* × *quantos morreram esperando o cliente*.
Com um estado só, elas se misturam.

Orçamento vencido **não vira venda, não reativa, não revalida**. Faz outro.

### R6 — Vencimento: os dois caminhos

- **Preguiçoso:** ao consultar/puxar um orçamento cujo `data_validade` já passou, ele é marcado
  `VENCIDO` na hora.
- **Job:** varredura periódica marca o resto.

Os dois juntos, não um ou outro — é o que `ArquivoCompartilhadoService` já faz com os PDFs. Só
preguiçoso deixaria orçamento nunca consultado eternamente "aberto", poluindo lista e relatório; só
job teria uma janela em que a tela mostra aberto algo já vencido.

### R7 — Produto inativado depois da emissão

**Não vende.** A tela de consulta marca a linha (*"produto inativado — não pode ser vendido"*)
**antes** de o operador tentar com o cliente na frente, e ele reduz aquele item a zero (R2) e fecha
a venda com o resto.

Produto é inativado por um motivo; vendê-lo por uma porta lateral desfaria a decisão de quem
inativou. É a única regra do PDV que o orçamento **não** afrouxa.

### R8 — Estoque: não interfere em nada

O orçamento **não reserva estoque** e **não bloqueia por saldo**. A tela mostra o saldo ao lado de
cada item, como informação.

⚠️ Barrar aqui criaria uma **segunda verdade** sobre a regra de estoque, morando fora da trigger
`fn_atualiza_estoque_movimento` — exatamente o que a V054 decidiu evitar. A trava acontece na hora
certa: na efetivação, no PDV, onde a trigger age (e onde
`cfg_geral.cfg_permite_estoque_negativo` decide).

### R9 — Papéis

ADMIN e OPERADOR emitem, consultam e cancelam. Orçamento não move dinheiro nem mercadoria — travar
em ADMIN faria o vendedor chamar o gerente para corrigir uma quantidade.

Orçamento emitido numa empresa **pode** ser consultado e efetivado em outra do mesmo tenant; a
venda sai na empresa da sessão, não na do orçamento.

### R10 — Numeração global

`id_orcamento` é `IDENTITY` do banco, como `venda.id_venda`. É o número que aparece ao lojista.

⚠️ Consequência aceita: como `venda` já faz, **o número pula entre tenants** (1, 7, 35…). Decisão
explícita do dono do produto por numeração global, em vez de sequencial por loja.

### R11 — Validade padrão é parâmetro

`cfg_geral.cfg_dias_validade_orcamento` (Parâmetros do Sistema → aba Vendas) sugere a data na
emissão; o operador pode sobrescrever caso a caso. Preço e prazo são dado, não código.

---

## Modelo de dados (migration V058)

### `orcamento`

| Coluna | Tipo | Nota |
|---|---|---|
| `id_orcamento` | `integer IDENTITY` PK | é o número que o lojista vê (R10) |
| `id_tenant` | `smallint NOT NULL` | → `plataforma.tenant` |
| `id_empresa` | `integer NOT NULL` | FK composta `(id_tenant, id_empresa)` |
| `id_cliente` | `integer NOT NULL` | FK composta |
| `id_funcionario` | `integer NOT NULL` | o **vendedor** |
| `id_usuario` | `integer NOT NULL` | quem **digitou** (P3) |
| `data_orcamento` | `timestamptz NOT NULL DEFAULT now()` | |
| `data_validade` | `date NOT NULL` | ⚠️ `date`, não `timestamptz` — ver §Armadilhas |
| `valor_desconto` | `numeric(12,2) NOT NULL DEFAULT 0` | R4 |
| `observacao` | `text` | condições de pagamento, prazo de entrega |
| `situacao` | `situacao_orcamento NOT NULL DEFAULT 'ABERTO'` | enum novo, R5 |
| `id_venda` | `integer` | FK composta → `venda`; preenchido na efetivação |
| `data_efetivacao` | `timestamptz` | |
| `data_cancelamento` | `timestamptz` | |
| `id_usuario_cancelamento` | `integer` | FK composta |
| `motivo_cancelamento` | `text` | |
| `criado_em` / `atualizado_em` | `timestamptz NOT NULL DEFAULT now()` | |

⚠️ **`situacao` é coluna gravada, e isso é uma reversão consciente.** O desenho inicial derivava o
estado (`cancelado` + `id_venda IS NULL`), o que funcionava para três estados. Com
`VENDIDO_PARCIAL`, derivar exigiria comparar item a item o orçamento com a venda **a cada linha de
listagem** — caro e frágil. Com cinco estados terminais, a coluna é a resposta certa.

**Sem `cancelamento_automatico`**: `VENCIDO` já é estado próprio (R5), o booleano seria redundante.

### `orcamento_item`

| Coluna | Tipo | Nota |
|---|---|---|
| `id_orcamento_item` | `integer IDENTITY` PK | ordem = ordem de inserção, `ORDER BY` por ele |
| `id_tenant` | `smallint NOT NULL` | |
| `id_orcamento` | `integer NOT NULL` | FK composta |
| `id_variacao` | `integer NOT NULL` | FK composta → `produto_barra` |
| `qtd_produto` | `numeric(14,3) NOT NULL` | mesma escala do ledger |
| `preco_venda` | `numeric(12,2) NOT NULL` | **congelado** (R3, P7) |

**Sem tabela de histórico** — o orçamento é imutável (R1), não há alteração para registrar.

### Enum e parâmetro

```sql
CREATE TYPE situacao_orcamento AS ENUM ('ABERTO','VENDIDO','VENDIDO_PARCIAL','CANCELADO','VENCIDO');
ALTER TABLE cfg_geral ADD COLUMN cfg_dias_validade_orcamento smallint NOT NULL DEFAULT 15;
```

⚠️ Criar um tipo novo e usá-lo na **mesma** migration é permitido — o que não pode é
`ALTER TYPE … ADD VALUE` e usar o valor novo na mesma transação (lição da V052/V053).

### RLS — obrigatório e não automático

O guarda-corpo da V024 rodou **naquele momento** e não roda de novo. Cada tabela nova faz o bloco
por conta própria:

```sql
ALTER TABLE orcamento ENABLE ROW LEVEL SECURITY;
ALTER TABLE orcamento FORCE  ROW LEVEL SECURITY;
CREATE POLICY orcamento_rls ON orcamento
  USING (id_tenant = plataforma.tenant_atual())
  WITH CHECK (id_tenant = plataforma.tenant_atual());
GRANT SELECT, INSERT, UPDATE, DELETE ON orcamento TO niner_app;
```

⚠️ O `GRANT` precisa de **caso novo em `PrivilegiosNinerAppTest`** — o Testcontainers conecta como
superusuário, então `GRANT` faltando é invisível para o resto da suíte (foi assim que o
Cancelamento de Entrada passou com 7 testes verdes e quebrou ao vivo).

---

## Contrato de API (`/api/v1`, JWT + RLS)

| Método | Rota | O que faz |
|---|---|---|
| POST | `/orcamentos` | emite (201). Servidor resolve preço por `idVariacao`; a tela **nunca** manda preço |
| GET | `/orcamentos` | pesquisa paginada — filtros: período, cliente, vendedor, situação, número |
| GET | `/orcamentos/{id}` | detalhe; ⚠️ marca `VENCIDO` na hora se a validade passou (R6) e sinaliza item com produto inativo (R7) e preço divergente (R3) |
| POST | `/orcamentos/{id}/cancelar` | motivo obrigatório |
| GET | `/pdv/orcamentos/{id}` | o que o PDV puxa: itens com preço congelado, cliente, vendedor, e as marcas de R3/R7 |

A efetivação **não** é endpoint próprio: acontece dentro de `POST /api/v1/pdv/vendas`, que passa a
aceitar `idOrcamento` + as quantidades levadas.

---

## Telas

1. **Emissão** (`/orcamentos/novo`) — cliente (com cadastro embutido via `ClienteFormModal`),
   vendedor, validade (sugerida por R11), itens por código de barras ou pesquisa, desconto, total.
2. **Pesquisa** (`/orcamentos`) — padrão de `PesquisaVendas`: paginação de 50 por número de página,
   colunas ordenáveis com allowlist no servidor, filtros com teto de 365 dias.
3. **Detalhe** — somente leitura (R1), com os avisos de R3/R7, e os botões Imprimir (bobina/A4),
   WhatsApp e Cancelar.
4. **No PDV** — campo "Puxar orçamento nº", modal espelhando `SelecaoItensVendaModal.tsx` com as
   quantidades reduzíveis (R2).

Menu: grupo **Frente de Loja**. Entrada em `AjudaDaTela` (R22) e `BotaoFecharTela` obrigatórios.

---

## Critérios de aceitação (viram testes)

1. **Dado** um orçamento com validade futura, **quando** puxado no PDV e efetivado com todas as
   quantidades, **então** a venda é criada, `situacao = VENDIDO`, `id_venda` preenchido.
2. **Dado** o mesmo orçamento, **quando** efetivado com quantidade menor em algum item, **então**
   `situacao = VENDIDO_PARCIAL` e a venda registra só o que foi levado.
3. **Dado** um orçamento, **quando** o PDV tenta efetivar com quantidade **maior** que a orçada,
   **então** 409 — R2 só permite diminuir.
4. **Dado** que o preço do produto mudou depois da emissão, **quando** o orçamento é efetivado,
   **então** a venda usa o preço **congelado** do orçamento, lido do banco.
5. **Dado** um orçamento com `data_validade` de ontem, **quando** consultado, **então** vira
   `VENCIDO` na hora e não pode ser efetivado (409 com o motivo).
6. **Dado** um orçamento cujo produto foi inativado, **quando** consultado, **então** o item vem
   marcado como indisponível; **quando** efetivado com ele, **então** 409.
7. **Dado** um orçamento efetivado, **quando** se tenta efetivar de novo, **então** 409.
8. **Dado** um orçamento cancelado, **quando** se tenta efetivar, **então** 409 dizendo quem
   cancelou e quando.
9. **Dado** desconto acima do teto de `cfg_geral.percentual_desconto_venda`, **quando** se emite,
   **então** 400.
10. **Dado** um orçamento do tenant A, **quando** o tenant B consulta/cancela/efetiva, **então**
    não encontra (P8).
11. **Dado** um orçamento vencido, **quando** o job roda, **então** ele é marcado `VENCIDO` — e o
    job enxerga os tenants (regressão do bug de job sem `TenantContext`).
12. **Dado** uma venda vinda de orçamento, **quando** a venda é cancelada, **então** o orçamento
    **continua** `VENDIDO`/`VENDIDO_PARCIAL` — não reabre (decisão explícita).

---

## ⚠️ Armadilhas deste repositório que esta feature encosta

**1. Job sem `TenantContext` não enxerga tenant nenhum — e não dá erro.** `niner_app` nunca tem
`BYPASSRLS`; consulta de domínio sem tenant no contexto devolve **zero linhas em silêncio**. O job
de vencimento "não acharia nada para cancelar" todo dia, para sempre. Padrão obrigatório: listar
tenants em `plataforma.tenant` (global) e entrar em `TenantContext.comTenant(...)` **antes de
qualquer consulta de domínio**. Copiar `ArquivamentoXmlJob`, inclusive o `try/catch` por tenant.

**2. `@Scheduled` e trabalho transacional em beans separados.** Método `@Transactional` chamado de
dentro do próprio bean não passa pelo proxy e roda **sem transação**. Padrão do projeto:
`CobrancaWebhookJob` (só `@Scheduled`) × `CobrancaWebhookProcessador` (`@Transactional`).

**3. O orçamento venceria às 21h.** A sessão do Postgres roda em `Etc/UTC`; `data_validade <
CURRENT_DATE` cancelaria no último dia de validade às 21:00 de Brasília — idêntico ao bug do caixa
que sumia às 21h. Comparar contra `(now() AT TIME ZONE <fuso da loja>)::date`. ⚠️ No job **não há
JWT**, então o fuso vem da empresa do orçamento (`FusoDaLoja.da(idEmpresa)`, que lê a UF).
`ComparacaoDeDataNoFusoCertoTest` **reprova o build** se isso for escrito errado.
E formatar também erra: `timestamptz` volta como `OffsetDateTime` em UTC — a data impressa passa
por `FusoDaLoja`.

**4. P8 — filtro `id_tenant` explícito em TODA query**, inclusive nos `EXISTS` e nos `JOIN … ON`.
Não é endurecimento posterior; é desde a primeira linha.

**5. Marcar o orçamento como efetivado é parte da operação, NÃO efeito colateral.** A regra "efeito
secundário roda depois do commit" vale para **medição e log** (nasceu do signup respondendo 201 com
conta inexistente). Aqui é vínculo de negócio: se a gravação de `id_venda` falhar, a venda **deve**
falhar junto — senão fica uma venda feita e um orçamento aberto que o cliente usa de novo.
**Dentro** da transação de `efetivarVenda`.

**6. `@page` global do projeto é `size: 80mm auto`** (bobina térmica). O A4 precisa de `@page`
nomeado próprio (`@page orcamento-a4 { size: A4 portrait; margin: 0 }`), senão sai espremido na
bobina — o DANFE já tropeçou nisso.

**7. Bobina são 42 colunas**, item em 2 linhas, Consolas em negrito, `print-color-adjust: exact` —
e **nada disso aparece na tela, no PDF ou em teste automatizado; só imprimindo**
(`docs/telas/papeleta-venda.md`).

**8. FK composta com `id_tenant` em todas as referências** — FK simples não valida que o
`id_empresa` referenciado é do mesmo tenant. As chaves únicas necessárias já existem
(`empresa_id_empresa_uk`, `venda_id_venda_uk`, etc.); nenhuma migration antiga precisa ser tocada.

**9. `rs.getObject(coluna, Long.class)` numa coluna `integer` não lê** — o driver recusa e o erro
chega ao usuário como *"Registro em uso por outro cadastro"* num GET. Usar `rs.getLong` +
`wasNull()`.

**10. Front:** data é texto mascarado `dd/mm/aaaa` (nunca `<input type="date">`); decimais por
`mascararMoeda`/`completarMoeda`; texto livre em maiúsculas (inclusive o motivo do cancelamento);
erro/sucesso por `Toast.tsx`; tela de lista nasce com `autoFocus`. Type-check é `npx tsc -b`.

**11. Link de WhatsApp expira em 24h** e some no restart da API (cache em memória, teto de 20
arquivos por tenant). Para um orçamento de 15 dias de validade, o texto precisa deixar claro que o
link é **cópia temporária**, não o orçamento.

---

## Non-goals desta versão

- Alterar orçamento (R1) e revalidar vencido (R5) — decisões explícitas, não pendências.
- Saldo por item / orçamento gerando mais de uma venda (R5).
- Reabrir orçamento quando a venda é cancelada.
- Versionamento do documento.
- Relatório de conversão (quantos viraram venda, por vendedor) — candidato natural à próxima fase,
  é a métrica de sucesso da rotina.
- `cfg_tela_campo` (visibilidade/obrigatoriedade por tenant) — o orçamento não tem campos opcionais
  o bastante para justificar.

## Métrica de sucesso

Percentual de orçamentos que viram venda, e quantos morrem por vencimento. Os dois números saem
direto de `situacao` — é por isso que `VENCIDO` é estado próprio (R5).
