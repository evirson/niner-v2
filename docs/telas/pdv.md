# Spec: PDV — Frente de Caixa                          Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-07-28 (revisada várias vezes no mesmo dia — split-tender + desconto da venda; desconto informado pelo operador, limitado por um máximo; F5/F6 renomeados + F5 novo reservado; layout de resumo+categorias lado a lado; Cliente e Vendedor obrigatórios) · Módulo(s): `vendas` (novo) · Fase: 1 — Núcleo do ERP

## Problema

A tela de PDV nasceu como rascunho de layout (mockup `C:\FIX\TELA PDV.png`/`tela_modelo_1.png`)
incorporado ao ERP em 2026-07-27 rodando só em cima de um catálogo de demonstração local — sem
nenhuma leitura/gravação real. O pedido agora é torná-la funcional de ponta a ponta: buscar
produtos de verdade (com variação, preço e estoque por empresa), efetivar uma venda de verdade
(baixa de estoque, P1) e gerar o(s) recebível(is) financeiro(s) (contas_receber), fechando a
lacuna registrada em `docs/PROGRESSO.md` — *"R9 (venda manual) segue sem ligação automática ao
financeiro (schema existe, domínio Java ainda não liga venda→recebível)"*.

O schema já existe por inteiro (V018 `venda`, V019 `produto_estoque`/ledger + trigger
`fn_atualiza_estoque_movimento`, V025 `tipo_carteira`/`contas_receber`) — **nenhuma migration
nova é necessária**. O que falta é o domínio Java (módulo `vendas`, novo) e a tela consumindo
API de verdade em vez do catálogo fixo.

## Solução proposta

Módulo novo `vendas` (pacote `com.vetor.niner.vendas`), com dois endpoints de leitura (busca de
produto e leitura por código de barras, ambos reaproveitados pelas teclas F2 e a leitura direta
no campo Código de Barras) e um endpoint de escrita (`POST /api/v1/pdv/vendas`) que grava a
venda inteira numa única transação: ledger de estoque (baixa via trigger já existente) +
parcela(s) em `contas_receber`, a partir do Tipo de Carteira escolhido pelo operador.

O preço e a variação de cada item são **sempre resolvidos no servidor** a partir do
`idVariacao` recebido — a tela nunca envia preço, só quantidade e o que foi selecionado
(mesmo princípio de nunca confiar em valor calculado no cliente).

## Escopo desta versão (v1) — non-goals explícitos

- **Cliente e vendedor são obrigatórios (revisão 2026-07-28 — deixou de ser non-goal).** Ver
  seção própria abaixo ("Cliente e vendedor da venda"). `venda.id_cliente` e
  `produto_movimento_detalhe.id_funcionario` sempre preenchidos — não existe mais venda de
  balcão anônima nem vínculo pendente `usuario`↔`funcionario`: o vendedor é escolhido
  diretamente do cadastro de funcionários a cada venda, sem depender de login.
- **Uma única empresa.** Hoje `tenant 1:1 empresa` (V014, Q6) — o backend resolve a empresa
  automaticamente (`SELECT id_empresa FROM empresa WHERE id_tenant = tenant_atual() LIMIT 1`,
  mesmo padrão já usado em `FuncionarioService`), sem seletor de loja na tela.
- **Sem preço de oferta nem desconto manual por item.** Vende sempre por `produto.preco_venda`;
  `produto_oferta` grava `false`. (`valor_desconto`/`valor_acrescimo` **não** gravam mais `0` —
  ver "Split-tender + desconto da venda (F5)" abaixo, revisão de 2026-07-28: eles recebem o
  desconto da venda e o desconto/acréscimo de cada forma de pagamento, rateados por item.)
- **Sem cancelamento/estorno de venda, sem NF-e, sem impressão de cupom.**
- **Busca de produto sem paginação de verdade** — os 20 primeiros resultados por descrição, sem
  "próxima página" (lista pra digitar e refinar a busca, não pra navegar um catálogo inteiro).

## Regras de negócio

### Busca de produto (F2) e leitura por código de barras

Cada **variação** (`produto_barra`) é uma linha do resultado — não agrupado por produto. Só
produtos **ativos** (`produto.ativo = true`) aparecem. Estoque mostrado por empresa (todas as
empresas do tenant, `LEFT JOIN produto_estoque` — uma variação sem nenhum movimento ainda não
tem linha em `produto_estoque`, conta como `0`) mais o total somado.

A leitura por código de barras aceita **sku ou ean** (`produto_barra.sku = ? OR produto_barra.ean = ?`).
404 amigável se não encontrar ou se o produto estiver inativo.

**Sintaxe "quantidade\*código" (2026-07-29):** digitar `5*9001000000138` no campo de código de
barras já lança o item com quantidade 5, em vez de sempre 1 — pedido do dono do produto pra não
precisar ler/digitar o mesmo código várias vezes seguidas quando o cliente leva várias unidades
do mesmo produto. Sem `*`, o valor inteiro é o código e a quantidade é 1 (comportamento de
sempre — inclusive somando 1 se o item já estiver no carrinho). Dica exibida discretamente sob
o campo de código de barras. Reaproveitado também na Transferência de Produtos — mesma função
`interpretarCodigoBarras()` (`web/src/lib/pdv.ts`).

A validação de estoque **não** acontece na busca/leitura (é só informativa — pode mudar entre a
leitura e a efetivação); a validação que vale de verdade é a de dentro da transação de
`POST /vendas`.

**Quantidade decimal configurável (2026-07-29):** `cfg_geral.cfg_permite_qtd_decimal`
(Parâmetros do Sistema, `docs/telas/configuracao-geral.md`) decide se a quantidade de produto
aceita até 3 casas decimais ou é sempre inteira — vale pra exibição (estoque por empresa na
pesquisa de produto, "Qtd" do carrinho, "Qtd Itens" do rodapé) e também pra validação: com o
parâmetro desligado, `POST /vendas` rejeita (400) qualquer item com quantidade fracionária,
mesmo vindo direto da API sem passar pela tela.

### Efetivar venda (F5) — split-tender + desconto da venda (revisão 2026-07-28)

O operador pode cobrir a venda com **uma ou mais formas de pagamento** (split-tender): cada
linha escolhe um **Tipo de Carteira** (`GET /api/v1/tipos-carteira`, já existe), um **valor
pago** e, quando a categoria permitir mais de uma parcela, um **número de parcelas** dentro de
`[pcMinima, pcMaxima]` daquele tipo de carteira (400 se fora da faixa).

**Desconto da venda (revisão 2026-07-28 — deixou de ser automático).** `cfg_geral.percentual_desconto_venda`
passou a ser só o desconto **máximo** permitido, não mais aplicado sozinho. Se ele for `> 0`, a
tela mostra dois campos editáveis e sincronizados — **Desconto (%)** e **Desconto (R$)** — que
nascem **zerados**: o operador digita o desconto que quiser dar nesta venda (em qualquer um dos
dois campos, o outro se recalcula), nunca acima desse máximo (a tela clampa no `onBlur`; o
servidor valida de novo e rejeita com **400** se o `descontoVenda` enviado passar do máximo —
nunca confia só na tela). Os dois campos ficam **travados** assim que o primeiro pagamento é
lançado (evita recalcular saldo já coberto por uma linha já lançada) — o operador precisa
remover os pagamentos pra poder ajustar o desconto de novo. Enquanto o desconto for `0`, a tela
mostra só o Valor Total; acima de `0`, mostra também a linha de **Sub-total**. O **Sub-total**
(produtos − desconto da venda) é o **líquido a pagar**, e é isso — não o total bruto — que a
soma das linhas de pagamento precisa cobrir.

**Cobertura de cada linha.** Cada `tipo_carteira` pode ter `percDesconto` **ou** `percAcrescimo`
(nunca os dois positivos ao mesmo tempo). O quanto uma linha abate do saldo a pagar (a
"cobertura") não é necessariamente igual ao `valorPago`:

```
cobertura = valorPago + valorPago × percDesconto/100 − valorPago × percAcrescimo/100
```

Desconto faz a linha cobrir **mais** saldo do que o valor tendido (ex.: pagar R$500 em dinheiro
com 10% de desconto cobre R$550 de saldo — é um bônus por pagar naquela forma). Acréscimo cobre
**menos** (a diferença é custo da forma de pagamento, não abate dívida). A **soma das
coberturas** de todas as linhas tem que fechar exatamente o líquido a pagar (tolerância de
R$0,01); se não fechar, **400** e nada é gravado.

**Teto de valor pago numa forma de pagamento com desconto próprio (2026-07-28, fórmula final —
retificada mais de uma vez no mesmo dia até fechar).** "X% de desconto" é sempre sobre o
**valor pago**, não sobre o valor coberto (`descontoLinha = valorPago × percDesconto/100`,
igual à fórmula de cobertura acima). Isso significa que, se o operador digitar o valor cheio do
saldo, a cobertura (que inclui o bônus do desconto) passaria do saldo — por isso o `valorPago`
máximo é calculado ao contrário da mesma fórmula: fechar
`valorPago × (1 + percDesconto/100) = saldoRestanteAntesDaLinha` dá
`valorPagoMaximo = saldoRestanteAntesDaLinha ÷ (1 + percDesconto/100)`. Exemplo: saldo de R$500
com 10% de desconto → o máximo pagável nessa linha é R$454,55 (`500 ÷ 1,10`) — pagando esse
valor, a cobertura fecha exatamente os R$500 (`454,55 × 1,10 = 500,00`), sem sobra. É calculado
**na ordem em que as linhas de pagamento chegam** (mesma ordem em que a tela vai lançando,
mantendo o saldo corrente) — não é um teto fixo sobre o líquido total, é sobre o saldo que ainda
falta na hora daquela linha. Na tela, o campo "Valor Pago" **ajusta sozinho** pro máximo se o
operador digitar acima (com uma dica mostrando o valor máximo permitido); o servidor valida a
mesma regra e responde **400** se algo tentar passar do limite.

> ⚠️ **Nota de implementação (não reabrir sem motivo novo):** essa fórmula foi tentada de 3
> formas diferentes na mesma sessão antes de fechar nesta — (1) desconto sobre o valor pago com
> teto por subtração simples (`saldo × (1-%)`, deixava sobra de ~1% do saldo sem fechar
> sozinho); (2) "cálculo ao contrário" com desconto ainda sobre valor pago mas teto por divisão
> (quase certo, só a base do desconto que zoava outro exemplo); (3) desconto sobre o valor
> **coberto** em vez do valor pago (`cobertura = valorPago ÷ (1-%)`) — rejeitada explicitamente
> pelo dono do produto com o exemplo "paguei R$78 num carteira de 10%, o desconto tem que ser
> R$7,80 (10% de 78), não R$8,67". A fórmula final é a combinação certa: **desconto sobre o
> valor pago** (item 1/2) **+ teto por divisão** (item 2) — ver `PdvVendaService.resolverPagamentos`
> se for mexer aqui de novo.

- **Categoria `AVISTA` ou `CARTAO_DEBITO`:** a linha só aceita **1** parcela (400 se pedir mais —
  dinheiro/PIX/débito não parcelam) — grava **1** linha em `contas_receber` já com
  `data_recebimento`/`valor_recebido` preenchidos (a venda já foi paga no ato, na própria loja
  resolvida em `id_empresa_pagamento`).
- **Categoria `CARTAO_CREDITO` ou `CREDIARIO`:** grava **N** linhas em `contas_receber` **em
  aberto** (`data_recebimento = null`, `valor_recebido = 0`), mesmo quando N=1 — é assim que o
  sistema já trata cartão de crédito hoje (`ClienteHistoricoService`, resumo de crediário isola só
  `categoria_carteira = CREDIARIO`, mas cartão de crédito também nasce em aberto na mesma tabela).
  Vencimento da parcela N = `data_venda + (prazo_pagamento × N)` dias. Valor de cada parcela =
  `valorPago da linha / numeroParcelas`, com o resto do arredondamento absorvido pela **última**
  parcela (a soma das parcelas de uma linha sempre bate exatamente com o `valorPago` dessa linha
  — nunca perde nem sobra centavo). **`contas_receber` grava sempre o `valorPago` literal de cada
  linha** — nunca a cobertura — porque é o dinheiro/cobrança real que circula.

**Rateio do desconto/acréscimo nos itens.** O desconto promocional somado ao desconto/acréscimo
de cada linha de pagamento é **rateado entre os itens vendidos**, proporcional ao valor de cada
item (`preço × qtd`), com o resto do arredondamento absorvido pelo último item (mesmo truque da
parcela), e gravado em `produto_movimento_detalhe.valor_desconto`/`valor_acrescimo`. **Nunca**
em `contas_receber.valor_desconto` — esse campo é reservado para um recurso futuro sem relação
(desconto ao cobrar uma parcela de crediário muito atrasada), decisão explícita do dono do
produto. Por construção, a soma de `valorPago` de todas as linhas bate exatamente com a soma do
valor dos itens já líquido de desconto/acréscimo — verificado por derivação algébrica a partir
de um exemplo numérico concreto antes de implementar, e confirmado nos testes.

### Cliente e vendedor da venda (F6, 2026-07-28 — deixou de ser non-goal)

**Ambos obrigatórios** pra confirmar a venda — pedido direto do dono do produto, não são mais
opcionais como o desenho original (venda de balcão anônima) previa.

- **Cliente:** busca por **nome, CPF/CNPJ ou celular** (`cliente.telefone`, rotulado "Celular"
  na tela de Cliente) num modal novo (`PesquisaClienteModal.tsx`, mesmo padrão do F2 Pesquisa
  Produto) — `GET /api/v1/pdv/clientes?busca=`, endpoint novo (`PdvClienteController/Service`),
  só cliente **ativo**, até 20 resultados. Grava em `venda.id_cliente` (coluna que já existia no
  schema desde V018, só nunca tinha sido usada).
- **Vendedor:** busca por **nome** num modal novo (`PesquisaVendedorModal.tsx`) que reaproveita
  o endpoint **já existente** `GET /api/v1/funcionarios?nome=&status=ATIVOS` — nenhum endpoint
  novo precisou ser criado pro vendedor. Grava em `produto_movimento_detalhe.id_funcionario`
  (coluna que também já existia desde V019) — o mesmo `idFuncionario` é gravado em **todas** as
  linhas de item da venda (um vendedor por venda, não por item).
- Servidor valida os dois (existe, pertence ao tenant, está ativo) e rejeita com **400** se
  algum não existir/estiver inativo — a mesma dupla validação de sempre (nunca confia só na
  tela). Nenhuma migration nova precisou ser criada — as duas colunas já existiam, só nunca
  eram preenchidas pelo PDV.
- Não substitui nem depende do vínculo pendente `usuario`↔`funcionario` (a pendência de outra
  spec, mencionada nas revisões anteriores desta spec, continua em aberto — mas deixou de
  bloquear o PDV: o vendedor é escolhido manualmente a cada venda, não inferido do login).

### Layout da tela de Forma de Pagamento (F6, 2026-07-28 — mockup do dono do produto)

Reorganizada em blocos empilhados verticalmente, de cima pra baixo:

1. **Linha Cliente/Vendedor** — dois campos lado a lado, cada um mostrando o nome selecionado
   (ou "Nenhum cliente/vendedor selecionado") + botão "Selecionar" que abre o modal de busca
   correspondente.
2. **Resumo + categorias lado a lado** — resumo (Valor Total da Venda em fonte maior/destacada;
   Desconto Gerencial numa linha só com % e R$ juntos; Sub-Total; Desconto Promocional; Valor a
   Pagar; Valor Pago) à esquerda, e os 4 botões de categoria de forma de pagamento (À Vista/
   Cartão Débito/Cartão Crédito/Crediário) empilhados verticalmente à direita, sempre visíveis
   (não somem quando uma categoria é escolhida).
3. **Área de pagamentos** — caixa abaixo mostrando, alternadamente: a lista de formas de
   pagamento já lançadas (tabela, como já era); ou, quando uma categoria foi clicada, o
   formulário de escolher o tipo de carteira específico + valor pago + parcelas (o que antes
   substituía os botões de categoria, agora fica só nessa caixa — os botões continuam visíveis
   ao lado do resumo).
4. **Rodapé fixo** — Cancelar/Confirmar Venda, sempre visível (antes só aparecia fora do modo de
   edição de uma categoria).

"Valor a Pagar" e "Valor Pago" (2026-07-28, corrigido) são dinâmicos: caem/sobem conforme o
operador lança formas de pagamento — `Valor a Pagar = Sub-Total − soma das coberturas das linhas
já lançadas` (não só o desconto acumulado, que foi um bug corrigido no meio da sessão) e
`Valor Pago = soma do valorPago das linhas já lançadas`. Confirmar Venda exige `Valor a Pagar`
zerado **e** cliente **e** vendedor selecionados.

### Teclas de atalho (revisão 2026-07-28 — F5/F6 renomeados)

- **F6 Efetiva Venda** (era F5) — abre a tela de Forma de Pagamento.
- **F5 Devolver Produto** (novo, reservado) — só o botão/atalho existem, **sem funcionalidade**
  ainda (pedido explícito do dono do produto: "por enquanto não precisa codificar nada"). O
  `preventDefault()` do F5 físico já está implementado (mesma lição do bug de F-keys físicas
  documentado antes) pra não deixar o navegador recarregar a página quando a funcionalidade for
  implementada.
- F2 Pesquisa Produto, F3 Altera Quantidade, F4 Limpa Tela — inalterados.

### Baixa de estoque — sem checagem de saldo (revisado 2026-07-29)

Dentro da mesma transação, pra cada item: resolve preço/variação em `PdvVendaService.
resolverItens()`. **Não checa mais `produto_estoque.disponivel`** — pedido direto do dono do
produto, 2026-07-29, vale pra qualquer movimentação de produto do sistema (entrada ou saída,
não só PDV): venda é aceita e grava normalmente mesmo sem saldo suficiente, deixando
`produto_estoque.qtd_estoque` negativo. Antes disso (2026-07-28 até 2026-07-29) a regra era o
oposto — 409 e nada gravado se `disponivel < qtd` — texto histórico mantido só como referência
de como funcionava. Grava `produto_movimento_mestre` (`tipo_movimento = 'VENDA'`, vinculado à
`venda`) + uma linha de `produto_movimento_detalhe` (`credito_debito = 'D'`) por item; a trigger
`fn_atualiza_estoque_movimento` (já existente, V019) baixa `produto_estoque` sozinha (nenhuma
linha tem `CHECK` contra saldo negativo) — nenhuma lógica de baixa de estoque em Java.

## Contrato de API

```
GET  /api/v1/pdv/produtos?busca=              busca por descrição (ILIKE, até 20 resultados)
GET  /api/v1/pdv/produtos/codigo/{codigo}      leitura por sku OU ean — 404 amigável
GET  /api/v1/pdv/clientes?busca=              busca por nome/CPF-CNPJ/celular (novo, 2026-07-28)
POST /api/v1/pdv/vendas                        efetiva a venda (itens + forma de pagamento + cliente/vendedor)
```

Busca de vendedor reaproveita o endpoint já existente `GET /api/v1/funcionarios?nome=&status=ATIVOS`
— nenhum endpoint novo precisou ser criado pra isso.

`GET /api/v1/pdv/clientes` devolve (só cliente ativo, até 20 resultados):
```json
[{ "idCliente": 12, "nome": "MARIA SILVA", "cpfCnpj": "11144477735", "telefone": "11988887777" }]
```

Ambos os `GET` devolvem o mesmo formato de item (uma variação):
```json
{
  "idVariacao": 42,
  "descricaoProduto": "TÊNIS ESPORTIVO RUNNER",
  "variacaoLinha": "40",
  "variacaoColuna": "AZUL",
  "sku": "9001000000123",
  "precoVenda": 189.90,
  "estoquePorEmpresa": [{ "codigoEmpresa": 1, "nomeEmpresa": "LOJA MATRIZ", "qtd": 5.000 }],
  "estoqueTotal": 5.000
}
```

`GET /api/v1/config-geral/desconto-venda` (aberto a qualquer papel — diferente do resto de
`cfg_geral`, que é ADMIN-only): `{ "percentualDescontoVenda": 10.00 }` — desde 2026-07-28 é o
**máximo** permitido, não mais aplicado automaticamente; a tela usa isso pra decidir se mostra
os campos de Desconto (%/R$) e pra clampar o que o operador digitar.

`POST /api/v1/pdv/vendas` (revisado 2026-07-28 — split-tender; revisado 2026-07-28 — `descontoVenda`
passou a vir explícito no request, escolhido pelo operador, em vez de calculado sozinho a partir
do percentual):
```json
// request
{
  "itens": [{ "idVariacao": 42, "qtd": 2.000 }],
  "descontoVenda": 42.10,
  "pagamentos": [
    { "idCarteira": 1, "valorPago": 100.00, "numeroParcelas": 1 },
    { "idCarteira": 3, "valorPago": 279.80, "numeroParcelas": 1 }
  ],
  "idCliente": 12,
  "idFuncionario": 3
}
// response (201)
{
  "idVenda": 87,
  "valorTotalProdutos": 421.00,
  "descontoVenda": 42.10,
  "valorLiquido": 378.90,
  "pagamentos": [
    { "idCarteira": 1, "nomeCarteira": "DINHEIRO", "valorPago": 100.00,
      "parcelas": [{ "numeroParcela": 1, "dataVencimento": "2026-07-28T...", "valorParcela": 100.00, "paga": true }] },
    { "idCarteira": 3, "nomeCarteira": "VISA", "valorPago": 279.80,
      "parcelas": [{ "numeroParcela": 1, "dataVencimento": "2026-08-27T...", "valorParcela": 279.80, "paga": false }] }
  ]
}
```

Todos sob `/api/v1/**` (JWT de tenant, RLS ativo — P8). Erros em Problem Details (RFC 9457):
400 (item/variação/carteira/cliente/vendedor inexistente ou inativo, categoria×parcelas
incompatível, `descontoVenda` acima do máximo permitido, `valorPago` de uma linha acima do teto
da forma de pagamento com desconto próprio, soma das coberturas não fecha o saldo a pagar), 404
(código de barras não encontrado). Não existe mais 409 por estoque insuficiente (2026-07-29).

## Critérios de aceitação (viram testes)

- Dado um termo de busca, quando ele bate com a descrição de um produto ativo, então a variação
  aparece no resultado com preço, variação de linha/coluna e estoque por empresa + total.
- Dado um produto inativo, quando buscado, então não aparece.
- Dado um sku ou ean existente, quando lido, então devolve o mesmo formato; se não existir ou o
  produto estiver inativo, 404.
- Dado um item com `qtd` maior que o `disponivel`, quando a venda é efetivada, então 409 e
  **nada** é gravado (nem venda, nem movimento, nem parcela) — atomicidade.
- Dado Tipo de Carteira categoria `AVISTA`, quando a venda é efetivada, então grava 1 parcela já
  com `dataRecebimento` preenchida.
- Dado Tipo de Carteira categoria `CREDIARIO` com `numeroParcelas = 3`, quando efetivada, então
  grava 3 parcelas em aberto, vencimentos espaçados por `prazoPagamento` dias, soma dos valores
  batendo exatamente com o total.
- Dado `numeroParcelas` fora de `[pcMinima, pcMaxima]` do tipo de carteira, então 400.
- Dado categoria `AVISTA`/`CARTAO_DEBITO` com `numeroParcelas > 1`, então 400.
- Dado um `idCarteira` ou `idVariacao` que não existe (ou é de outro tenant), então 400.
- Dado uma venda efetivada com sucesso, então a leitura seguinte de estoque daquela variação já
  reflete a baixa (via trigger existente).
- Dado `cfg_geral.percentual_desconto_venda > 0` como máximo, quando o operador informa um
  `descontoVenda` dentro desse máximo, então ele é aceito, aplicado sobre o total dos produtos e
  rateado no `valor_desconto` do(s) item(ns) vendido(s).
- Dado um `descontoVenda` acima do máximo permitido (`cfg_geral.percentual_desconto_venda × valorTotalProdutos`),
  então 400 e nada é gravado.
- Dado `descontoVenda = 0` (padrão, mesmo com máximo configurado > 0), quando a venda é
  efetivada, então `valorLiquido = valorTotalProdutos`.
- Dado um tipo de carteira com `percDesconto`, quando uma linha de pagamento com esse tipo de
  carteira é usada, então a cobertura daquela linha é maior que o `valorPago` na proporção do
  percentual, e o desconto correspondente também é rateado nos itens.
- Dado um tipo de carteira com `percDesconto` e um saldo restante conhecido antes da linha,
  quando o `valorPago` da linha passa de `saldoRestante × (1 − percDesconto/100)`, então 400 e
  nada é gravado.
- Dado várias linhas de pagamento (split-tender) cuja soma de coberturas fecha exatamente o
  líquido a pagar, então a venda é efetivada com uma linha em `contas_receber` por parcela de
  cada linha de pagamento.
- Dado linhas de pagamento cuja soma de coberturas **não** fecha o líquido a pagar (pra mais ou
  pra menos, além da tolerância de R$0,01), então 400 e nada é gravado.
- Dado um `idCliente` ou `idFuncionario` inexistente (ou de outro tenant, ou inativo), então 400
  e nada é gravado.
- Dado um cliente buscado por nome, CPF/CNPJ ou celular, quando existe e está ativo, então
  aparece no resultado de `GET /api/v1/pdv/clientes`; se estiver inativo ou for de outro tenant,
  não aparece.
- Dado uma venda efetivada com sucesso, então `venda.id_cliente` e todas as linhas de
  `produto_movimento_detalhe.id_funcionario` da venda batem com o cliente/vendedor escolhidos.

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `vendas.pdv`** — F2 pesquisa produto (nome), F3 altera quantidade de um item já
  lançado, F4 limpa a venda em andamento, F5 reservado pra devolução de produto (ainda sem
  funcionalidade), F6 efetiva a venda (pede cliente, vendedor e forma de pagamento); leitura de
  código de barras no campo próprio + Enter. Vender mais do que o disponível na loja não é mais
  um erro (2026-07-29) — a venda é aceita e o estoque fica negativo. `url_video`: NULL.

## Impacto no banco

Nenhuma migration nova — schema já existia por inteiro (V018/V019/V025), inclusive
`venda.id_cliente` e `produto_movimento_detalhe.id_funcionario` (2026-07-28: passaram a ser
preenchidos pela primeira vez, colunas já existiam desde a criação dessas tabelas).

## Impacto nas integrações

Nenhum (v1 é só venda física de balcão, sem canal/marketplace envolvido).

## Non-goals desta feature

Ver seção "Escopo desta versão" acima — resume tudo que fica pra depois (multi-empresa,
desconto/oferta por item, estorno, NF-e, cupom, paginação da busca). Cliente e vendedor
**deixaram** de ser non-goal em 2026-07-28 — ver "Cliente e vendedor da venda" acima.

## Questões abertas

Nenhuma bloqueante — decisões de escopo já fechadas com o dono do produto em 2026-07-28.

## Métrica de sucesso

Uma venda de 3 itens, à vista, do zero até "Efetiva Venda" confirmada, em menos de 30 segundos
(fluxo de teclado: leitura → leitura → leitura → F5 → escolher forma de pagamento → confirmar).
