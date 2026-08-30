# Relatório de DRE (Demonstração do Resultado) — `relatorios.dre`

> Status: **implementado em 2026-08-14** (backend + tela), aprovado pelo dono do produto. Três
> pontos mudaram durante a implementação, por descoberta no código — estão marcados com
> **[revisto na implementação]** abaixo.

## Problema

O lojista sabe quanto vendeu (Relatório de Vendas) e quanto tem a pagar/receber, mas não sabe
**se deu lucro**. Faltam as duas perguntas que definem a saúde do negócio:

- *"Vendi bem esse mês, mas sobrou dinheiro?"* — resultado econômico do período.
- *"Por que o caixa não fecha com o lucro?"* — a diferença entre vender e receber, que numa loja
  com crediário é enorme.

Um pequeno varejista costuma responder isso com planilha, e erra sempre nos mesmos dois pontos:
trata **compra de mercadoria como despesa do mês** (quando é estoque) e ignora **CMV, taxa de
cartão e comissão**, que são os custos que de fato comem a margem.

## Solução proposta

Uma tela de relatório (padrão de `docs/telas/relatorio-vendas.md`) que monta a DRE gerencial em
**dois regimes**, escolhidos por filtro:

1. **Competência (padrão oficial)** — receita/despesa no dia do fato gerador (a venda, o
   lançamento da despesa), independentemente de quando o dinheiro entra ou sai. É o lucro
   econômico real do período.
2. **Caixa (gerencial opcional)** — receita/despesa quando o dinheiro efetivamente entra ou sai.
   Mostra a disponibilidade financeira e, de propósito, **mistura** resultado de vendas passadas
   com recebimentos atuais (o crediário de março aparecendo em maio).

Os dois regimes usam **a mesma estrutura de linhas** e o mesmo plano de contas — muda apenas
*quando* cada valor é reconhecido. Ver "Semântica de data por regime".

## Estrutura da DRE (ordem fixa das linhas)

Derivada de `cfg_plano_contas.grupo_dre` + `sinal`, que já existem e já estão semeados:

```
  RECEITA BRUTA                         (grupo RECEITA_BRUTA)
− DEDUÇÕES                              (grupo DEDUCOES: impostos s/ venda, devoluções, descontos)
= RECEITA LÍQUIDA
− CUSTOS VARIÁVEIS                      (grupo CUSTO_VARIAVEL: CMV, comissões, taxas, frete, embalagem)
= MARGEM DE CONTRIBUIÇÃO
− DESPESAS FIXAS                        (grupo DESPESA_FIXA)
= RESULTADO OPERACIONAL (EBITDA)
− DEPRECIAÇÃO                           (grupo DEPRECIACAO)
± RESULTADO FINANCEIRO                  (grupo RESULTADO_FINANCEIRO)
− TRIBUTO SOBRE O LUCRO                 (grupo TRIBUTO_LUCRO)
= RESULTADO LÍQUIDO DO PERÍODO
```

Cada grupo abre nas contas analíticas que tiveram movimento no período (contas zeradas não
aparecem). **Margem de contribuição é apenas a linha de subtotal** — o painel de ponto de
equilíbrio é Non-goal desta versão.

## Origem dos números — relatório híbrido (decisão central)

Cinco linhas essenciais **não têm lançamento contábil** no sistema: existem no plano de contas,
mas nada escreve nelas. A DRE as **deriva do movimento real** no momento da consulta (decisão do
dono do produto, 2026-08-14 — a alternativa, gerar lançamentos a cada venda, mexeria no PDV,
no cancelamento e na devolução, e criaria risco de o lançado divergir do movimento):

| Linha | Conta | Origem derivada |
|---|---|---|
| Receita de vendas | `1.01.001` | `produto_movimento_detalhe.preco_venda × qtd` **+ `valor_acrescimo`** das saídas de venda (o acréscimo da forma de pagamento é receita, `DreService.java:214`) |
| Devoluções de vendas | `2.02.001` | **o próprio ledger** — `produto_movimento_mestre.tipo_movimento = 'DEVOLUCAO'` + detalhes `credito_debito = 'C'`, por `pmm.data_movimento` (`DreService.java:238-250`). A tabela `venda_devolucao` **não** é consultada pela DRE |
| Descontos concedidos | `2.02.002` | `produto_movimento_detalhe.valor_desconto` (item + rateio da forma de pagamento) |
| CMV | `3.01.001` | `produto_movimento_detalhe.preco_custo × qtd` das mesmas saídas |
| Taxas de cartão/PIX | `3.02.002` | `tipo_carteira.perc_desconto` aplicado sobre o valor de cada forma de pagamento |
| Comissões sobre vendas | `3.02.001` | mesma regra do Relatório de Comissões (hoje é só cálculo, nunca vira lançamento) |

**Todo o resto vem de lançamento**, somado por `id_plano_contas` → `grupo_dre` → `sinal`.

**[revisto na implementação] As linhas derivadas carregam o próprio grupo de DRE, em código** —
não perguntam o grupo ao plano de contas do tenant. Motivo descoberto no primeiro teste: na época
o `SignupService` semeava apenas **3 contas** (a árvore mínima da conta de compra) e o plano padrão
completo de 76 contas era um seed à parte que nem todo tenant aplicou. Se receita e CMV dependessem
de `1.01.001`/`3.01.001` existirem, a DRE de um tenant novo viria **zerada mesmo com vendas** — foi
exatamente o que aconteceu. O código da conta continua sendo a chave de exibição, e a descrição
cadastrada pelo lojista prevalece quando a conta existe no plano dele.

> **Atualizado em 2026-08-14:** o seed manual acabou — `SignupService.java:99-116` copia as **76
> contas** de `cfg_plano_contas_padrao` (tabela modelo global semeada em
> `db/migration/V016__cadastros.sql:345-472`) para todo tenant novo. A decisão acima **continua
> valendo assim mesmo**: as linhas derivadas não podem quebrar se o lojista renomear ou excluir a
> conta padrão.
>
> ⚠️ **Consequência para teste**: como o tenant já nasce com o plano inteiro, fixture que cria
> conta precisa usar as **faixas reservadas ao cliente** — **grupo `9` inteiro** e as **famílias
> `.90`–`.99`** de cada grupo (ex.: `9.01.001`, `2.90.000`) —, senão o `POST` colide com o padrão
> e devolve **409**. Foi isso que quebrou 62 testes na virada de 2026-08-14.

Por que o CMV vem do ledger e não da compra: `3.03.001 Compra de Mercadoria para Revenda` já está
com **`inclui_dre = false`** no seed — compra é estoque (ativo), não resultado. O custo entra na
DRE quando a mercadoria **sai vendida**, com o custo real gravado na própria linha da venda.
⚠️ Nunca mudar essa flag sem entender que isso passaria a contar o estoque duas vezes.

## Semântica de data por regime

| | Competência | Caixa |
|---|---|---|
| Venda (receita, CMV, desconto, taxa, comissão) | `venda.data_venda` | `contas_receber.data_recebimento` **[revisto na implementação]** |
| Devolução | `produto_movimento_mestre.data_movimento` do movimento `DEVOLUCAO` **[revisto na implementação]** — não `venda_devolucao.data_devolucao` (`DreService.java:238-250`) | **não entra** — o regime de caixa passa `BigDecimal.ZERO` nas linhas de devolução (`DreService.java:315-316`) |
| Despesa lançada | `contas_pagar.data_lancamento` | `contas_pagar.data_pagamento` |
| Movimento bancário avulso | — | `conta_corrente_movimento.data_movimento` |

**A escolha de `data_lancamento` (e não `data_vencimento`) para competência é deliberada**: o
vencimento é uma data de tesouraria, não do fato gerador. Uma conta de energia lançada em março e
vencendo em abril é despesa **de março**.

**[revisto na implementação] A entrada de dinheiro sai de `contas_receber.data_recebimento`, não
de `caixa_detalhe`.** O PDV grava uma linha de `contas_receber` para **toda** forma de pagamento
(dinheiro e PIX já nascem com `data_recebimento` preenchida; cartão e crediário ficam em aberto
até serem recebidos), e o Recebimento de Crediário preenche a data ao baixar a parcela. Ou seja,
essa única tabela cobre venda à vista e crediário, é a fonte natural do "quando o dinheiro
entrou", e evita ter de somar `caixa_detalhe` — que também é escrito pelo recebimento de crediário
e causaria dupla contagem.

Duas consequências do regime de caixa, ambas deliberadas:
- **CMV e comissão entram proporcionais ao recebido** (recebi 30% da venda → reconheço 30% do
  custo e da comissão). Reconhecer o custo pelo *pagamento ao fornecedor* traria de volta o erro
  "compra = despesa" que o `inclui_dre = false` evita.
- **Juros e multa recebidos** (`valor_recebido − valor_receber`) entram como **resultado
  financeiro**, não como receita de venda — o principal é a venda, o excedente é juro.

⚠️ **Risco de dupla contagem no regime de caixa (verificado no código, 2026-08-14):** dar baixa em
Contas a Pagar **não** gera lançamento em `caixa_detalhe` nem em `conta_corrente_movimento` — as
três tabelas são independentes. Então, se o lojista baixar a conta **e** lançar o mesmo pagamento
na Movimentação de Conta Corrente, o valor sai duas vezes. Regra desta versão: no regime de caixa,
**a saída é `contas_pagar.data_pagamento`**, e `conta_corrente_movimento` entra **apenas** com
contas de **receita** — o filtro do código é literalmente
`pc.grupo_dre IN ('RECEITA_BRUTA','RESULTADO_FINANCEIRO') AND ccm.credito_debito = 'C'`
(`DreService.java:378-398`). **`caixa_detalhe` nunca é consultado pela DRE**, em nenhum dos dois
regimes — a entrada de dinheiro vem de `contas_receber.data_recebimento` (ver acima). A tela avisa
isso na ajuda (R22), e a conciliação automática fica como evolução. Ver "Questões abertas".

## Filtros

| Filtro | Componente | Regra |
|---|---|---|
| Período | data inicial/final mascaradas `dd/mm/aaaa` | obrigatório; padrão = mês corrente |
| Regime | rádio/select | Competência (padrão) · Caixa |
| Empresa | `EmpresaMultiSelect` | **A tela inteira é ADMIN-only** — `DreService.gerar` chama `exigirAdmin(jwt)` na primeira linha e devolve **403** ("Apenas administradores podem consultar a DRE.") antes de qualquer resolução de empresa (`DreService.java:93, 110-114`). Ou seja, na prática só existe o caso "ADMIN vê todas"; OPERADOR nunca chega ao filtro |
| Comparar com (AH) | select | Nenhum · Período anterior (padrão) · Mesmo período do ano anterior |

## Padrão de tela de relatório (2026-08-14, segunda rodada)

A primeira versão da tela nasceu sem os botões que todo relatório do projeto tem — corrigido no
mesmo dia, a pedido do dono do produto ("é um relatório, então precisa seguir o padrão"):

- **Barra de ações do topo**, na mesma ordem dos demais: `?` (ajuda) · **Filtros** (só depois da
  primeira geração) · **Gerar PDF** (só quando há dados) · `✕`. Enquanto nada foi gerado, o corpo
  mostra "Use o botão Filtros para gerar a DRE".
- **Estrutura de conteúdo do padrão**: `.lista-corpo.relatorio-corpo-fixo` → `.relatorio-conteudo`
  (o alvo da captura) → `section-label` + `card table-wrap`.
- **PDF por captura visual** (`lib/relatorioDreCaptura.ts`, mesmo mecanismo de
  `relatorioComissoesCaptura.ts`): tema claro forçado **só no clone** do documento (sem flash na
  tela), altura dos ancestrais liberada no clone (senão o `.lista-corpo` corta o PDF na altura da
  tela), cabeçalho/rodapé nativos em toda página com "Página X de Y", empresa e "Nainer ERP".
- **Blocos que só existem no PDF**: "Filtros Aplicados" (regime, período, empresas, comparação) e
  o resumo de receita/resultado líquido — na tela eles já estão na barra de filtros e no rodapé
  fixo, que ficam **fora** do elemento capturado.
- **Retrato, não paisagem** (divergência consciente de Comissões/Contas a Receber): a DRE tem
  poucas colunas e muitas linhas; em paisagem a tabela estica e as linhas se espalham, e retrato é
  o formato que o contador espera receber. Verificado no arquivo gerado: A4 retrato
  (MediaBox 595×842), 1 página, 286 KB.
- **Regime e período vão no subtítulo do cabeçalho do PDF**, em todas as páginas: numa DRE
  impressa, saber se aquilo é competência ou caixa é a diferença entre entender e não entender o
  número — não pode ficar só na tela.

## AV e AH (escopo v1, escolhido pelo dono do produto)

- **AV — análise vertical:** cada linha como % da **receita líquida** (não da bruta — é a base que
  o mercado usa, e a única que sobrevive a mudanças de imposto/devolução). Receita líquida = 100%.
- **AH — análise horizontal:** variação em R$ e % contra o período de comparação escolhido.
  Períodos de tamanhos diferentes não são comparáveis: ao escolher "período anterior", o intervalo
  comparado tem **exatamente a mesma quantidade de dias**, terminando no dia anterior ao início.
- Sinal na cor: para linhas de despesa, **crescer é ruim** — a cor segue o efeito no resultado,
  não o sinal aritmético (`--danger` quando piora, `--sucesso` quando melhora).

## Regras de negócio

1. **Venda cancelada não entra em nenhum regime** (`venda.cancelada = true`), em nenhuma linha.
2. **Devolução é dedução da receita**, nunca receita negativa nem "despesa"; o estorno de estoque
   também reverte o CMV do período em que a devolução ocorreu.
3. **Compra de mercadoria nunca entra** (é estoque) — garantido por `inclui_dre = false`.
4. **Distribuição de lucro não é despesa; pró-labore é.** O grupo 7 do plano de contas
   (Movimentos Neutros e Sócios) existe justamente para sangria/suprimento/transferência/
   liquidação e **fica fora do resultado** (`grupo_dre = NAO_APLICA`).
5. **Crediário é o que mais separa os dois regimes**: a venda parcelada entra 100% na competência
   no dia da venda e pinga no caixa por meses. Não é erro do relatório — é o motivo de existirem
   dois regimes, e a ajuda da tela diz isso com essas palavras.
6. **Isolamento de tenant explícito** em toda query (`id_tenant = plataforma.tenant_atual()`,
   inclusive nos JOINs), conforme `docs/infra/isolamento-tenant-rls.md`.

## Impacto no contrato de API

```
GET /api/v1/relatorios/dre
    ?dataInicial=2026-08-01&dataFinal=2026-08-31
    &regime=COMPETENCIA|CAIXA
    &idsEmpresa=1,2                     (opcional; default = todas as permitidas)
    &comparar=NENHUM|PERIODO_ANTERIOR|ANO_ANTERIOR
```

Resposta: linhas na ordem fixa da estrutura, cada uma com `{ chave, rotulo, nivel, tipo
(GRUPO|CONTA|SUBTOTAL), valor, percentualAV, valorComparado, variacaoAbsoluta, variacaoPercentual }`,
mais um cabeçalho com o período, o período comparado e os totais.

## Impacto no banco

**Nenhuma migration.** Todos os dados necessários já existem. Tabelas efetivamente lidas por
`DreService`: `cfg_plano_contas` (`grupo_dre`/`sinal`/`inclui_dre`), `venda`,
`produto_movimento_mestre`, `produto_movimento_detalhe`, `funcionario`, `contas_receber`,
`tipo_carteira`, `contas_pagar` e `conta_corrente_movimento`. **`caixa_detalhe` e
`venda_devolucao` não entram** — a devolução vem do ledger (`tipo_movimento = 'DEVOLUCAO'`) e a
entrada de dinheiro vem de `contas_receber.data_recebimento` (`DreService.java:238-250, 378-398`).
Este é um relatório de leitura pura.

## Critérios de aceitação (viram testes)

- Dado um período com uma venda à vista de R$ 100 (custo R$ 40), quando consulto em **competência**,
  então Receita Bruta = 100, CMV = 40 e Margem de Contribuição = 60.
- Dado uma venda **no crediário** em março com parcela recebida em maio, quando consulto março em
  competência, então a receita aparece **integral** em março; quando consulto março em **caixa**,
  então a receita **não** aparece em março, e aparece em maio.
- Dada uma venda **cancelada**, quando consulto qualquer regime, então ela não afeta nenhuma linha.
- Dada uma devolução no período, quando consulto, então ela reduz a receita líquida (linha de
  Deduções) e reverte o CMV correspondente.
- Dada uma compra de mercadoria lançada em Contas a Pagar na conta `3.03.001`, quando consulto,
  então ela **não** aparece na DRE (`inclui_dre = false`).
- Dada uma despesa lançada em 20/03 com vencimento em 05/04, quando consulto **março em
  competência**, então ela aparece em março; quando consulto **março em caixa** e ela foi paga em
  abril, então **não** aparece em março.
- Dado um pagamento com cartão cuja carteira tem `perc_desconto = 3%`, quando consulto, então
  Taxas de Cartão traz 3% do valor pago naquela forma.
- Dada a comparação "período anterior", quando o período tem 31 dias, então o período comparado
  tem 31 dias e termina no dia anterior ao início.
- Dado um tenant B com movimento no mesmo período, quando o tenant A consulta, então nenhum valor
  de B aparece (isolamento).

## Ajuda da tela (R22 / §3.7.1) — obrigatório

`chave_tela`: `relatorios.dre`. Precisa explicar, em linguagem de lojista: a diferença entre os
dois regimes (com o exemplo do crediário), por que compra de mercadoria não aparece, o que é CMV,
e o aviso de não lançar em Conta Corrente um pagamento já baixado em Contas a Pagar.

## Impacto nas integrações

Nenhum. A DRE só lê o que já está persistido — não publica evento no outbox, não fala com canal
de venda, não escreve nada. É o relatório mais "somente leitura" do produto.

## Métrica de sucesso

O lojista consegue responder **"esse mês deu lucro?"** sem exportar nada para planilha, e a
diferença entre os dois regimes explica sozinha o desencontro entre lucro e dinheiro em caixa —
que é a dúvida que motivou a feature. Sinal de que está funcionando: a tela ser aberta perto do
fechamento do mês, e o regime de caixa ser consultado quando falta dinheiro apesar de ter vendido.

## Non-goals desta versão

- Painel de **ponto de equilíbrio** e análise de margem (a linha de subtotal existe; o painel não).
- **Visão 12 meses lado a lado** e **drill-down por linha** — as duas ficaram fora do v1 por
  escolha do dono do produto; a estrutura da resposta da API já é compatível com ambas.
- **Fechamento de período** (congelar o resultado de um mês) — a DRE é sempre recalculada.
- DRE contábil oficial (ECD/SPED) — este relatório é **gerencial**.
- Rateio de despesa entre empresas: a despesa aparece na empresa em que foi lançada.

## Arquivos

| Arquivo | Papel |
|---|---|
| `api/.../financeiro/dre/DreDtos.java` | contratos (`Regime`, `Comparacao`, `LinhaDre`, `DreResponse`) |
| `api/.../financeiro/dre/DreService.java` | apuração dos dois regimes, derivadas + lançamentos, AV/AH |
| `api/.../financeiro/dre/DreController.java` | `GET /api/v1/relatorios/dre` |
| `api/src/test/.../RelatorioDreCrudTest.java` | 7 testes dos critérios de aceitação |
| `web/src/lib/dre.ts` | tipos + chamada |
| `web/src/lib/relatorioDreCaptura.ts` | PDF por captura visual (A4 retrato) |
| `web/src/pages/relatorios/RelatorioDre.tsx` | tela (popup de filtros + grade + barra de ações) |
| `web/src/components/AjudaDaTela.tsx` | ajuda `relatorios.dre` |
| `web/src/lib/menu.ts`, `web/src/App.tsx` | item de menu (`adminOnly`) e rota sob `RequireAdmin` |
| `web/src/styles.css` | `.dre-linha-grupo` / `.dre-linha-conta` / `.dre-linha-subtotal` |

## Decisões tomadas (eram questões abertas)

1. **Permissão: ADMIN-only** (confirmado pelo dono do produto). A DRE expõe lucro, despesa e
   pró-labore. Vale nos três níveis: item do menu com `adminOnly`, rota sob `RequireAdmin` e
   **403 na API** (`DreService.exigirAdmin`) — a checagem de verdade é a da API.
2. **Outras receitas** seguem entrando por lançamento manual em conta corrente (suficiente no v1).

## Questões abertas
1. ~~**Dupla contagem no regime de caixa**~~ — **resolvida na origem em 2026-08-14**: a baixa de
   Contas a Pagar passou a gerar o movimento de caixa/conta corrente (Parte 1 de
   `docs/telas/fluxo-caixa.md`), então o pagamento tem um lugar único e canônico. A regra da DRE
   (despesa só de `contas_pagar`; `conta_corrente_movimento` só para contas de receita) **continua
   valendo e sem dupla contagem** — a DRE nunca soma as tabelas de dinheiro no lado da despesa.
2. ~~**Plano de contas padrão não é semeado no signup**~~ — **resolvido em 2026-08-14**: o signup
   passou a copiar o plano padrão completo (76 contas) do modelo global `cfg_plano_contas_padrao`
   (V016), então um tenant novo já tem contas de despesa e a DRE nasce completa. Ver
   `docs/telas/plano-contas.md`.

---

## Revisão 2026-08-22 — parcela migrada não é receita do Nainer (auditoria, item 22)

⚠️ **O que estava errado, no regime CAIXA.** O importador de Contas a Receber cria uma **venda
sintética** para pendurar as parcelas migradas (`contas_receber.id_venda` é obrigatório): uma casca
sem item, sem movimento de estoque e sem custo. O DRE caixa parte de `contas_receber` e busca o
custo por fora, com `LEFT JOIN` no ledger — que para essa venda não acha nada, e o `COALESCE` zera
o CMV.

Resultado: migração em março trazendo 300 parcelas recebidas em janeiro (R$ 45.000) fazia o DRE de
janeiro em regime caixa mostrar **R$ 45.000 de receita com CMV R$ 0,00** — margem de 100%, lucro que
nunca existiu no Nainer (a mercadoria foi comprada e vendida no sistema anterior).

E era **incoerente com o Fluxo de Caixa**: o próprio importador avisa que "nenhum lançamento de
caixa é criado para parcelas já pagas", então o dinheiro **não** entrava lá mas **entrava** aqui —
dois relatórios financeiros, o mesmo fato, respostas opostas.

**Como ficou (V059).** `venda` ganhou `origem` (`PDV`/`IMPORTACAO`) e `importado_em`, com CHECK
amarrando os dois. O DRE caixa exclui as parcelas com `data_recebimento` **anterior ao instante da
importação**.

⚠️ **O corte é por instante, não pela origem inteira** — decisão do dono do produto: parcela legada
que estava **em aberto** na migração e o cliente vier pagar depois, pelo Recebimento de Crediário,
**continua sendo receita**. Esse dinheiro entrou no caixa da loja de verdade; o CMV zerado ali é
honesto, porque o custo é de antes e não existe neste sistema.

O regime de **competência** nunca sofreu disso: ele faz `JOIN` (INNER) com o ledger, então a venda
sem movimento simplesmente não aparece.

---

## ⚠️ 2026-08-29 (auditoria, 2ª leva) — três correções que mudam NÚMERO

### A devolução passou a estornar a COMISSÃO
A consulta de devoluções **nem selecionava a coluna**: JOÃO vendia R$ 1.000 a 10%, o cliente
devolvia tudo no mesmo mês, e a DRE fechava com **R$ 100 de prejuízo** sobre uma operação que não
movimentou nada — enquanto o **Relatório de Comissões**, o número que a loja de fato PAGA, já dava
R$ 0,00.

⚠️ **O javadoc afirmava que não estornar era a regra** (*"igual à DRE; reverter seria regra de
negócio nova"*): estava alinhado, e ao relatório errado. Alinhamento entre dois relatórios não prova
nada quando nenhum dos dois é o pagador. ⚠️ A **taxa de cartão continua sem estorno**, de propósito:
a operadora já cobrou a taxa da transação original e não a devolve quando a mercadoria volta — é
fato do mundo, não simetria contábil.

### Resgate de vale-mercadoria saiu do regime CAIXA
No regime caixa a linha só pode entrar se **entrou dinheiro**, e o vale não é dinheiro: a receita já
foi reconhecida quando a venda original foi paga. Sem o corte, uma venda de R$ 90 em espécie,
devolvida e represtada com o vale, virava **R$ 180 de receita e CMV em dobro** para R$ 90 e uma
única saída de mercadoria. ⭐ É o mesmo argumento — e o mesmo precedente — do corte da parcela
migrada: *"o Fluxo de Caixa nunca recebeu esse dinheiro"*.

### OPERADOR ficou preso à empresa da sessão
Esta tela deixou de ser `admin_apenas` na V078 e `resolverIdsEmpresa` continuou devolvendo **todas
as empresas do tenant** para qualquer papel, com um javadoc afirmando *"qualquer outro papel não
chega aqui"*. O gerente da Filial abria a DRE e lia **receita, CMV, comissões e pró-labore da
Matriz** — e bastava mandar `idsEmpresa=1` na URL. ⚠️ O sinal estava à vista: o método recebia
`jwt` e **nunca o lia**. O irmão `FluxoCaixaService.resolverEmpresas` sempre fez o certo; os dois
discordavam e ninguém comparou.

**Guarda:** `RelatorioComissoesCrudTest.devolucaoEstornaAComissaoTambemNaDreENaLucratividade` cruza
os **três** relatórios sobre a mesma massa — é o formato que pegou o defeito, porque cada número
sozinho era plausível.
