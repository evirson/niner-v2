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
- **Sem seletor de loja na tela.** A venda é sempre gravada na **empresa ativa da sessão**, lida
  do claim `eid` do JWT (`PdvVendaService.java:94`) — escolhida no login quando o usuário tem
  acesso a mais de uma empresa (`docs/telas/login-empresa.md`).
  ⚠️ **Corrigido em 2026-08-15:** este item dizia que o backend resolvia a empresa com
  `SELECT id_empresa FROM empresa WHERE id_tenant = tenant_atual() LIMIT 1`. Isso descrevia o
  comportamento de **antes de 2026-07-28** e contradizia `login-empresa.md`, que registra o
  retrofit do `PdvVendaService` para o claim `eid` e é explícita: *"nunca reintroduzir a busca por
  'a primeira empresa do tenant'"*.
- **Sem preço de oferta nem desconto manual por item.** Vende sempre por `produto.preco_venda`;
  `produto_oferta` grava `false`. (`valor_desconto`/`valor_acrescimo` **não** gravam mais `0` —
  ver "Split-tender + desconto da venda (F5)" abaixo, revisão de 2026-07-28: eles recebem o
  desconto da venda e o desconto/acréscimo de cada forma de pagamento, rateados por item.)
- **Sem NF-e.** ~~Sem cancelamento/estorno de venda, sem impressão de cupom.~~ — **superado**: o
  cancelamento ganhou tela própria (`docs/telas/cancelamento-venda.md`,
  `CancelamentoVendaController.java:20`) e o PDV imprime a **papeleta de venda** ao final
  (`PdvVendaController.java:36`, `docs/telas/papeleta-venda.md` — bobina térmica 80mm/42 colunas).
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

- **Categoria `AVISTA`, `CARTAO_DEBITO` ou `VALE_MERCADORIA`:** a linha só aceita **1** parcela
  (400 se pedir mais — dinheiro/PIX/débito não parcelam, e vale-mercadoria é um crédito único
  que se consome de uma vez) — `PdvVendaService.aceitaApenasUmaParcela`, linhas 375-378.
- **`AVISTA` (dinheiro/PIX) e `VALE_MERCADORIA` já nascem quitados** em `contas_receber`
  (`data_recebimento`/`valor_recebido` preenchidos, `id_empresa_pagamento` resolvido —
  `PdvVendaService:673-674`): num é dinheiro que já circulou na própria loja, no outro é um
  crédito que a loja já devia ao cliente e acabou de compensar, então nada fica a receber. **Revisado 2026-07-30:** `CARTAO_DEBITO` **deixou** desse grupo — mesmo aceitando
  só 1 parcela, a parcela em `contas_receber` agora nasce **em aberto**, igual cartão de crédito
  (ver abaixo), porque o prazo de liquidação da bandeira (`tipo_carteira.prazo_pagamento`, D+1
  típico) ainda não passou; entrar no caixa (ver seção "Caixa" abaixo) não é o mesmo que já estar
  recebido. Fica pendente até uma futura tela de conciliação de cartões baixar a parcela.
- **Categoria `CARTAO_DEBITO`, `CARTAO_CREDITO` ou `CREDIARIO`:** grava **N** linhas em
  `contas_receber` **em aberto** (`data_recebimento = null`, `valor_recebido = 0`), mesmo quando
  N=1. Vencimento da parcela N = `data_venda + (prazo_pagamento × N)` dias. Valor de cada parcela =
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

**Cadastro de cliente sem sair da venda (2026-07-31).** `PesquisaClienteModal.tsx` ganhou um
botão "+ Novo Cliente" ao lado do campo de busca. A primeira versão abria um formulário mínimo
(nome/categoria/gênero/CPF/celular); revisado no mesmo dia a pedido do dono do produto pra abrir
a **mesma tela de cadastro completa de cliente** (`ClienteForm.tsx`, a de `/clientes/novo` —
Identificação/Contato/Endereço/Outros, categoria com criação rápida embutida, CEP autopreenchido
via ViaCEP, todas as validações de sempre), dentro de um modal que rola por dentro
(`ClienteFormModal.tsx`, novo, em `pages/pdv/`). `ClienteForm` ganhou dois props opcionais —
`aoSalvarComSucesso`/`aoCancelar` — que, quando fornecidos, substituem a navegação pra
`/clientes` por callbacks (seleciona o cliente recém-criado e fecha o modal, sem sair da venda em
andamento); o ícone de engrenagem ("Configurar tela") some nesse modo, já que levaria pra fora da
venda. Fora daí, o comportamento da tela de Cliente é idêntico ao de sempre.

### Layout da tela de Pesquisa de Cliente (revisado 2026-07-31)

O campo de busca "Nome, CPF/CNPJ ou Celular" e o botão "+ Novo Cliente" ficam na mesma linha,
mesma altura (rótulo numa linha própria acima, input+botão juntos numa `linha-com-botao` —
mesmo padrão já usado pra Categoria nos formulários de cadastro). Antes o rótulo empurrava só o
campo de busca pra baixo, deixando o botão desalinhado por cima.

### Layout da tela de Forma de Pagamento (F6, 2026-07-28 — mockup do dono do produto)

Reorganizada em blocos empilhados verticalmente, de cima pra baixo:

1. **Linha Cliente/Vendedor** — dois campos lado a lado, cada um mostrando o nome selecionado
   (ou "Nenhum cliente/vendedor selecionado") + botão "Selecionar" que abre o modal de busca
   correspondente.
2. **Resumo + categorias lado a lado** — resumo (Valor Total da Venda em fonte maior/destacada;
   Desconto Gerencial numa linha só com % e R$ juntos; Sub-Total; Desconto Promocional; Valor a
   Pagar; Valor Pago) à esquerda, e os **5** botões de categoria de forma de pagamento (À Vista/
   Cartão Débito/Cartão Crédito/Crediário/Vale-Mercadoria — `CATEGORIAS_ORDEM` em
   `FormaPagamentoModal.tsx:29`) empilhados verticalmente à direita, sempre visíveis
   (não somem quando uma categoria é escolhida). O 5º nasceu com a Devolução de Produtos
   (`docs/telas/devolucao-produtos.md`), que é quem emite o vale.
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
`Valor Pago = soma do valorPago das linhas já lançadas`.

**Habilitação do "Confirmar Venda" (revisado 2026-07-31).** O botão habilita assim que
`Valor a Pagar` zera — **não** depende mais de cliente/vendedor já estarem selecionados (antes
dependia dos três). A falta de cliente e/ou vendedor só é cobrada ao **clicar** no botão: se
faltar um dos dois (ou os dois), a venda não é gravada e aparece um `Toast` de erro específico
("Defina o cliente…"/"Defina o vendedor…"/"Defina o cliente e o vendedor antes de confirmar a
venda."). Pedido direto do dono do produto — o operador pode fechar o pagamento primeiro e
resolver cliente/vendedor depois, mas nunca grava faltando um dos dois.

### Teclas de atalho (revisão 2026-08-03 — F5 Devolver Produto removido, Efetiva Venda voltou a ser F5)

- **F6 Puxar Orçamento** (2026-08-20) — abre o orçamento pelo número e traz os itens para o
  ledger, já com **cliente e vendedor preenchidos** no F5 (continuam editáveis: quem vai levar pode
  não ser quem pediu). ⚠️ Três coisas específicas deste caminho: o preço vem **congelado** do
  orçamento (o servidor o relê do banco, a tela não manda preço); a quantidade só pode ser
  **reduzida**, e zero significa "não levar este item"; e o F6 **só funciona com a venda vazia** —
  puxar por cima de itens já lançados misturaria preço congelado com preço de cadastro sem o
  operador perceber. Acrescentar produto que não estava no orçamento **é permitido** (venda comum,
  preço de cadastro) e não torna o orçamento "parcial". Ver `docs/telas/orcamento.md`.
- **F5 Efetiva Venda** (era F6, de 2026-07-28 até 2026-08-03) — abre a tela de Forma de
  Pagamento.
- ~~F5 Devolver Produto~~ — **removido em 2026-08-03**. Era um atalho/botão reservado desde
  2026-07-28, nunca teve funcionalidade nenhuma; a tela **Devolução de Produtos**
  (`docs/telas/devolucao-produtos.md`, `/devolucao-produto`, menu "Frente de Loja") cobre esse
  caso agora, então o slot F5 do PDV foi liberado e reaproveitado pela Efetiva Venda.
- F2 Pesquisa Produto, F3 Altera Quantidade, F4 Limpa Tela — inalterados. A grid de botões
  (`.pdv-linha-fkeys`) passou de `grid-template-columns: repeat(4, 1fr)` para `repeat(3, 1fr)`
  — com Devolver Produto removido, 3 botões numa grade pensada pra 4 ficavam à esquerda com um
  vão vazio à direita.
- O botão "Efetiva Venda" teve a altura reduzida em 2026-07-31 (ajuste puramente visual,
  `.pdv-tecla-venda` em `styles.css`) — pedido direto do dono do produto, sem mudança de
  comportamento.

### Limite de crédito no crediário (2026-08-07)

`cliente.limite_credito` existe desde a V016 (`db/migration/V016__cadastros.sql`) como campo
"pronto para quando o crediário existisse", mas nunca era checado até aqui.
`PdvVendaService.validarLimiteCredito()` roda depois que as coberturas já fecharam o saldo (não
antes) e só entra em ação quando a venda tem alguma linha `CREDIARIO`: soma o crediário **já em
aberto** do cliente (mesmo filtro de `ClienteHistoricoService.buscarResumoCrediario` —
`contas_receber` com `data_recebimento IS NULL`, de qualquer venda anterior) com o `valorPago`
das linhas `CREDIARIO` desta venda. Se `limite_credito > 0` (`<= 0` significa **sem limite**, não
bloqueia nada) e a soma passar do limite (com a mesma tolerância de R$0,01 do resto do PDV), a
venda é rejeitada — 400, nada é gravado. A mensagem de erro devolve os três valores (em aberto,
desta venda, limite) pro operador entender o motivo.

Na tela, esse erro (e qualquer outro erro de confirmação vindo do servidor — saldo que não
fecha, cliente/vendedor inválido) deixou de aparecer como `Toast` e passou a abrir um popup
dedicado (`AvisoModal.tsx`, `role="alertdialog"`) — mensagem importante o bastante pra exigir
leitura antes de o operador seguir, em vez de um aviso que pode passar despercebido no meio do
fluxo de pagamento.

### Vale-Mercadoria como forma de pagamento (2026-08-03)

Nova categoria de carteira no split-tender, ao lado de À Vista/Cartão Débito/Cartão Crédito/
Crediário — ver `docs/telas/devolucao-produtos.md` ("Resgate no PDV") para o desenho completo:
pede o **número do vale** (não um valor digitado), busca o valor de verdade no servidor, paga
na hora, e bloqueia vale já usado (409) ou maior que o saldo a pagar (400). A carteira "VALE
MERCADORIA" já existia desde o signup — só mudou de categoria (`AVISTA` → `VALE_MERCADORIA`).

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

### Caixa aberto obrigatório (2026-07-30)

`PdvVendaService.efetivarVenda` chama `CaixaService.idCaixaAbertoObrigatorio(idEmpresa,
idUsuario)` antes de gravar qualquer coisa — sem caixa aberto hoje pra esse usuário/empresa, 400
e nada é gravado. Na tela, isso nunca deveria acontecer: `Pdv.tsx` checa `GET /api/v1/caixa/
status` ao carregar e, se fechado, mostra um popup obrigatório (`AberturaCaixaModal.tsx`) antes
de liberar qualquer ação — a checagem do serviço é rede de segurança contra chamada direta à
API. Ver `docs/telas/abertura-caixa.md`.

### Venda lança em `caixa_detalhe` (revisado 2026-07-30 — antes fora de escopo)

Cada linha de pagamento **à vista** (`AVISTA`/`CARTAO_DEBITO`/`CARTAO_CREDITO`) grava um crédito
em `caixa_detalhe` (`tipo_operacao = 'RECEBIMENTO_VENDA'`, `credito_debito = 'C'`, `valor =
valorPago` da linha) no momento da venda — antes disso só o Recebimento de Crediário gravava em
`caixa_detalhe`, então o Fechamento de Caixa (`docs/telas/fechamento-caixa.md`) nunca mostrava
nenhum total de venda do PDV, só o saldo inicial. **Linha de `CREDIARIO` fica de fora de
propósito**: é dinheiro que ainda não circulou — só vira lançamento de caixa quando a parcela for
efetivamente recebida depois (`RecebimentoCrediarioService`), senão contaria em dobro. Isso é
independente do estado da parcela em `contas_receber` (seção anterior) — débito e crédito já
entram no caixa na hora, mas a parcela correspondente fica em aberto até a conciliação de
cartões.

## Contrato de API

```
GET  /api/v1/pdv/produtos?busca=&marca=&referencia=
                                               busca por descrição (ILIKE, até 20 resultados);
                                               `marca`/`referencia` são filtros adicionais
                                               (2026-08-12, usados pela Entrada de Produtos)
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
  "variacaoCor": "AZUL",
  "variacaoTamanho": "40",
  "sku": "9001000000123",
  "precoVenda": 189.90,
  "estoquePorEmpresa": [{ "codigoEmpresa": 1, "nomeEmpresa": "LOJA MATRIZ", "qtd": 5.000 }],
  "estoqueTotal": 5.000,
  "urlImagem": "https://.../tenants/1/produtos/42/abc.webp",
  "marca": "RUNNER",
  "referencia": "TN-4040"
}
```

`urlImagem` é a URL pública da **primeira foto da galeria** do produto (índice 0), `null` quando
não há foto — ver `docs/infra/armazenamento-imagens.md`. `marca`/`referencia` (2026-08-12) vêm do
produto e servem tanto de filtro de busca quanto de desambiguação visual no resultado.

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
da forma de pagamento com desconto próprio, soma das coberturas não fecha o saldo a pagar,
crediário desta venda somado ao já em aberto ultrapassa `cliente.limite_credito` — 2026-08-07),
404 (código de barras não encontrado). Não existe mais 409 por estoque insuficiente (2026-07-29).

## Critérios de aceitação (viram testes)

- Dado um termo de busca, quando ele bate com a descrição de um produto ativo, então a variação
  aparece no resultado com preço, cor/tamanho e estoque por empresa + total.
- Dado um produto inativo, quando buscado, então não aparece.
- Dado um sku ou ean existente, quando lido, então devolve o mesmo formato; se não existir ou o
  produto estiver inativo, 404.
- Dado um item com `qtd` maior que o saldo da empresa, quando a venda é efetivada, então a venda é
  gravada normalmente (201) e o saldo fica **negativo** — `cfg_permite_estoque_negativo` nasce
  **ligado** (V055): a loja típica não faz gestão de estoque e o caixa não deve travar por um
  cadastro que ninguém alimenta.
- ⚠️ **Novo em 2026-08-20:** desligando esse parâmetro (Parâmetros do Sistema → Estoque), a mesma
  venda é **recusada com 409** e o estoque fica intacto — a mensagem diz qual produto, quanto há e
  quanto a operação precisa. Quem barra não é o PDV: é a trigger `fn_atualiza_estoque_movimento`
  (V054), no único caminho por onde o saldo se mexe — ver `docs/telas/configuracao-geral.md`.
  Testes: `estoqueInsuficienteBloqueiaAVendaEDeixaOSaldoIntacto` e
  `estoqueInsuficienteEhAceitoQuandoOParametroPermiteNegativo`, em `PdvCrudTest`.
- Dado Tipo de Carteira categoria `AVISTA`, quando a venda é efetivada, então grava 1 parcela já
  com `dataRecebimento` preenchida.
- Dado Tipo de Carteira categoria `CREDIARIO` com `numeroParcelas = 3`, quando efetivada, então
  grava 3 parcelas em aberto, vencimentos espaçados por `prazoPagamento` dias, soma dos valores
  batendo exatamente com o total.
- Dado `numeroParcelas` fora de `[pcMinima, pcMaxima]` do tipo de carteira, então 400.
- Dado categoria `AVISTA`/`CARTAO_DEBITO` com `numeroParcelas > 1`, então 400.
- Dado uma venda com linhas de dinheiro, débito e crédito (2026-07-30), quando efetivada, então
  todas as três lançam crédito em `caixa_detalhe`, mas só a linha de dinheiro nasce quitada em
  `contas_receber` — débito e crédito ficam em aberto.
- Dado uma venda com linha de `CREDIARIO`, quando efetivada, então essa linha **não** lança nada
  em `caixa_detalhe` (só as demais formas de pagamento da mesma venda, se houver).
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
  quando o `valorPago` da linha passa de `saldoRestante ÷ (1 + percDesconto/100)`, então 400 e
  nada é gravado. **É divisão, não subtração** (`api/src/main/java/com/vetor/niner/vendas/PdvVendaService.java:491-492`):
  o desconto incide sobre o valor **pago**, então a cobertura da linha é `valorPago × (1 + %)`;
  o teto é o `valorPago` que faz essa cobertura fechar exatamente o saldo, ou seja o inverso da
  mesma fórmula. O teto por subtração (`saldoRestante × (1 − %)`) é justamente a tentativa **1**
  registrada como rejeitada na nota de implementação acima (deixava ~1% do saldo sem fechar) —
  não reintroduzir.
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
- Dado nenhum caixa aberto hoje pro usuário/empresa, quando tenta efetivar a venda, então 400 e
  nada é gravado (2026-07-30, `docs/telas/abertura-caixa.md`).
- Dado um cliente com `limite_credito > 0`, quando o crediário já em aberto somado ao crediário
  desta venda ultrapassa o limite, então 400 e nada é gravado (2026-08-07). Dado
  `limite_credito <= 0` (sem limite definido), então nenhuma venda em crediário é bloqueada por
  esse motivo, não importa o valor.

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `pdv.tela`** (`AjudaDaTela.tsx`, entrada criada só em 2026-08-07 — a tela mais
  usada do sistema tinha ficado sem ajuda documentada até então) — F2 pesquisa produto (nome),
  F3 altera quantidade de um item já lançado, F4 limpa a venda em andamento, F5 efetiva a venda
  (pede cliente, vendedor e forma de pagamento — inclusive Vale-Mercadoria, digitando o número
  do vale); leitura de código de barras no campo próprio + Enter. Devolução de produtos ganhou
  tela própria (`docs/telas/devolucao-produtos.md`), fora do PDV. Vender mais do que o
  disponível na loja não é mais um erro (2026-07-29) — a venda é aceita e o estoque fica
  negativo. Crediário respeita o limite de crédito do cliente quando ele tiver um definido
  (2026-08-07). `url_video`: NULL.

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

---

## ✅ 2026-08-21 — F5/F6 trocam, e o orçamento deixa de sequestrar a venda inteira

Sete ajustes pedidos pelo dono do produto depois de usar a tela com o orçamento no ar.

### As teclas trocaram de lugar

| tecla | antes | agora |
|---|---|---|
| **F5** | Efetiva Venda | **Buscar Orçamento** |
| **F6** | Puxar Orçamento | **Efetiva Venda** |

A ordem passou a ser a do balcão: primeiro se puxa o que o cliente já tinha orçado, depois se
fecha. Os dois botões ficam **lado a lado** no rodapé da coluna de teclas (a venda ocupa o dobro da
largura — é a ação final e a que o operador acerta sem olhar). Os nomes das funções no código
acompanharam (`f5BuscarOrcamento`/`f6EfetivaVenda`); tecla renomeada com função de nome antigo é
armadilha para quem ler depois.

### Cliente e vendedor ficam FIXOS quando a venda vem de orçamento

Antes vinham preenchidos mas editáveis. O orçamento foi emitido **para** aquele cliente e **por**
aquele vendedor, e é esse par que a comissão e o compromisso de preço acompanham — trocar faria a
venda contradizer o documento que ela cumpre. No lugar do botão "Selecionar" aparece a marca
*"do orçamento"*.

### ⛔ O mesmo produto pode ocupar DUAS linhas, com preços diferentes

É o ajuste mais profundo do lote. Regra do dono do produto: *"se o preço mudou, a empresa tem que
honrar o orçamento, mas produtos a mais não precisam honrar"*.

Então lançar um produto que já está na venda **junta pelo PREÇO, não pelo SKU**: preço igual soma na
mesma linha, preço diferente abre linha nova com o preço de hoje.

Isso obrigou três mudanças que não são visíveis na tela:

1. **A chave da linha deixou de ser o SKU** (`ItemLedger.idLinha`). Com o SKU como chave, alterar a
   quantidade de uma das linhas alterava as DUAS, e remover uma removia as duas.
2. **`ItemVendaRequest.doOrcamento`** — a marca é por LINHA, não por variação. O servidor mapeava o
   preço congelado por `idVariacao` e o aplicava a tudo daquele produto; e a validação "não pode
   levar mais do que foi orçado" somava as duas linhas e **recusava a venda inteira**. Hoje só as
   linhas marcadas entram na conta do orçado, só elas recebem o preço congelado, e só elas contam
   para decidir se a venda foi parcial.
3. **`dividirParaEnvio`** (front) — uma linha da tela pode virar dois itens no envio quando o preço
   não mudou: a parte que o orçamento cobre (`qtdOrcada`) e o excedente. É o que permite mostrar
   "3 × camiseta" numa linha só sem que o servidor recuse por excesso.

⚠️ **Guarda de contrato:** mandar `idOrcamento` sem nenhuma linha marcada é **recusado** (400). Sem
ela o defeito seria silencioso e caro — a venda sairia inteira a preço de cadastro, o orçamento
seria consumido, e nada na resposta indicaria que a loja deixou de honrar o preço prometido.
Coberto por `OrcamentoCrudTest.vendaDeOrcamentoSemNenhumaLinhaMarcadaEhRecusada`, e o caso principal
por `unidadeAMaisDoMesmoProdutoSaiPeloPrecoDeHoje` (2 × 10,00 honrados + 1 × 15,00 de hoje = 35,00,
com o orçamento fechando como VENDIDO, não parcial).

### O popup virou uma busca de verdade

Antes pedia o **número** do orçamento — que o operador raramente tem; ele tem o nome de quem está na
frente dele. Agora localiza por **número, cliente ou vendedor**, e a lista traz nº, cliente,
vendedor, validade, itens e total, com um "Abrir" por linha.

**Só os ABERTOS**, filtrado no servidor: vencido, cancelado ou já vendido não têm por que aparecer
numa lista cujo único propósito é virar venda. Abrir um da lista **continua** checando a situação —
entre a busca e o clique o documento pode ter vencido, e ler um vencido é justamente o que o marca
(R6).

### O aviso do orçamento subiu para a linha do título

A frase *"Venda a partir do orçamento nº N — preços travados, cliente e vendedor fixos"* ficava
perto do rodapé, junto das teclas. Passou para a barra do título, **centralizada** entre o nome da
tela e os botões de ação: preço travado e cliente fixo mudam o que o operador PODE fazer, então a
informação tem de estar onde ele já olha enquanto lança item — não num canto que ele só vê quando
vai fechar a venda.

⚠️ O selo é **irmão** de `.titulo-tela`, não filho: a barra é `justify-content: space-between`, e
centralizar exige ocupar o espaço entre o título e as ações (`flex: 1` + `text-align: center`).
Dentro do bloco do título ele apenas ficaria colado no `h1`, à esquerda.

---

## Revisão 2026-08-22 — orçamento só vira venda na empresa que o fez (auditoria, item 3)

`abrirParaVenda` devolvia o `idEmpresa` do orçamento e ninguém comparava com o claim `eid` da
sessão. Não era vazamento de tenant (P8 nunca esteve em risco — as duas empresas são do mesmo
tenant, e o RLS não separa empresas), mas o documento impresso dizia "empresa A", a venda caía na B,
e **o estoque baixava numa empresa diferente da que a grid do orçamento mostrou**.

Decisão do dono do produto: *"orçamento feito numa empresa só pode ser consumido por esta empresa."*
`OrcamentoService.abrirParaVenda` recusa com 409, dizendo o nome da empresa correta.

⚠️ A checagem ficou no **serviço do orçamento**, não no PDV, para valer para qualquer chamador
futuro — o PDV é só o primeiro.

## Revisão 2026-08-24 — as três rejeições da primeira NF-e 55, e o `catch` que escondia a quarta

Continuação direta da revisão anterior (PF em NFC-e, PJ em NF-e 55). Com o caminho do modelo 55
sendo exercitado pela primeira vez contra a SEFAZ, três defeitos apareceram em sequência — cada um
escondendo o seguinte — e **nenhum deles aparecia na suíte**, porque era código novo sem venda real.

### `cStat 253` — a chave dizia 65 e o XML dizia 55

`MontadorXmlNfce` montava a chave de acesso com a constante `MODELO_NFCE` **fixa**, em vez de
`nota.modelo().codigo()`. O modelo ocupa as posições **21–22** da chave, então o XML declarava
`mod 55` e a chave carregava `65`. O DV batia com a chave que o montador produziu — por isso nada
falhou localmente —, mas a SEFAZ **recompõe a chave a partir dos campos do XML** e chega a outro
número, e a mensagem que volta fala em **dígito verificador**, não em modelo.

⚠️ **Constante literal onde existe um campo de domínio é bomba-relógio.** Enquanto o produto só
emitia NFC-e, `MODELO_NFCE` estava correta em todos os lugares onde aparecia. O segundo modelo é
que revelou quais delas eram, na verdade, `nota.modelo()`.

### `cStat 733` — CFOP interno numa operação interestadual

O `idDest` já saía 2. O CFOP vinha da regra fiscal, que tinha um valor só. Resolvido com
`cfop_interestadual` (**V061**) — ver [fiscal-perfil.md](fiscal-perfil.md) para o motivo de os dois
CFOPs serem cadastrados em vez de derivados.

### O `enderDest` que faltava, e o dado que já estava chegando

NF-e 55 exige endereço completo do destinatário, incluindo `cMun`. A validação preventiva (F11)
recusava a venda dizendo *"Falta: município (código IBGE)"* — correta, e mesmo assim inútil para
quem não sabia onde achar o número. O ViaCEP **já devolvia** o código; ver
[cliente.md](cliente.md#revisão-2026-08-24).

### ⚠️ O `catch` do front anulava a validação preventiva inteira

`ComprovantePapeletaModal` trocava **qualquer** exceção pelo erro genérico
*"Não foi possível falar com o serviço fiscal. A venda está registrada."* — inclusive o **409 com a
mensagem do que falta**. Ou seja: todo o trabalho de escrever mensagens preventivas específicas
(F11) chegava ao operador como **falha de rede**, mandando o diagnóstico para o servidor, para a
internet, para o certificado — para qualquer lugar menos para o cadastro incompleto.

```tsx
} catch (e) {
  setResultadoFiscal(e instanceof ApiError && e.message
    ? { ...erroDeComunicacao(), situacao: 'NAO_EMITIDO', mensagem: e.message }
    : erroDeComunicacao())
}
```

O erro genérico continua valendo para falha **real** de transporte, que é o caso em que ele é
verdadeiro. **Front que engole mensagem de erro do back não degrada a experiência: apaga a feature.**

### Impressão

PJ imprime **DANFE A4** (`DanfeModal`, aberto quando `modelo === 55`); PF segue no cupom térmico.
O `ResultadoEmissao` passou a carregar o `modelo` para o front saber qual abrir sem inferir.

⚠️ **Nenhuma NF-e 55 foi autorizada pela SEFAZ até a data desta revisão** — o CSRT correto ainda não
estava cadastrado. Os três defeitos acima estão corrigidos e cobertos por teste; o que falta é a
confirmação de ponta a ponta.

---

## ⛔ 2026-08-29 (auditoria, 2ª leva) — o desconto do documento passou a ser conferido no SERVIDOR

O PDV sempre resolveu o **preço** congelado do orçamento/OS lendo do banco. O **desconto** chegava
cru em `req.descontoVenda()`, calculado no front (`Pdv.tsx`:
`osPuxada?.valorDesconto ?? orcamentoPuxado?.valorDesconto ?? 0`).

**O que isso permitia:** OS nº 12 aprovada com R$ 1.000 em peças e serviços e **R$ 100 de desconto**
— total impresso e assinado pelo cliente, **R$ 900**. Um `POST /pdv/vendas` com
`idOrdemServico: 12` e `descontoVenda: 0` fechava a venda em **R$ 1.000**, marcava a OS FATURADA
apontando para ela, e **nada** — nem tela, nem log, nem teste — registrava que a loja cobrou R$ 100
a mais do que aprovou. Bastava uma chamada direta à API, ou o front perder o estado.

⚠️ O javadoc de `OrdemServicoService.exigirDescontoDentroDoTeto` já afirmava que o desconto
*"viaja"* para o PDV: viajava **pelo front**, que P4 diz não ser barreira.

**Como ficou:**
- O piso é **proporcional ao que a venda levou** — o cliente pode levar menos do que o documento
  aprovou (regra que já existia), e exigir o desconto cheio sobre metade dos itens transformaria uma
  venda parcial legítima em 409.
- **Só o piso é cobrado.** Dar mais desconto continua livre; quem faz isso é a loja abrindo mão de
  margem, e o teto do tipo de carteira já limita o outro lado.
- ⛔ **O documento VENCE o teto percentual.** O piso é congelado no documento e o teto é lido na hora
  da venda: se o dono baixa o "% máximo de desconto" **depois** de o cliente assinar, os dois se
  cruzam e a venda fica impossível por qualquer caminho — 400 pelo desconto aprovado, 409 pelo teto
  — com as peças da OS **reservadas para sempre**. Honrar o papel que está na mão do cliente é a
  única saída que não inventa dinheiro nem prende o operador.

**Guardas:** `OrdemServicoTest.vendaDaOsNaoPodeIgnorarODescontoAprovado` e o par negativo
`descontoDaOsMenorPeloQueFoiLevadoEhAceito` — sem ele, uma versão que recusasse toda venda parcial
passaria no primeiro. ⭐ Sabotei a guarda e confirmei que o teste reprova (409 → 201) antes de dar
por boa.

### ⚠️ E o F5 era a última tela a ignorar `cfg_permite_qtd_decimal`
`PuxarOrcamentoModal` tinha `true` cravado em cinco chamadas: num tenant com quantidade decimal
**desligada**, a coluna mostrava `2,000` enquanto todo o resto do PDV mostra `2`, o operador
conseguia digitar `1,5`, e a recusa só vinha do **servidor**, no fechamento da venda, com o cliente
na frente.


---

## ⭐ 2026-09-02 — venda que ficou SEM nota agora emite pela tela (pendência 84)

**O buraco.** Os dois gatilhos de emissão do PDV exigem `!reimpressao` — a pergunta automática do
CPF e o botão manual. Reabrindo a papeleta pela **Pesquisa de Vendas**, nenhum aparecia, e
Documentos Fiscais não tem ação de emitir. Resultado: **venda efetivada cujo popup foi fechado
antes de emitir ficava sem documento e sem caminho pela tela**. Aconteceu de verdade com a venda
641.

**Como ficou.** Na papeleta reaberta, quando a venda **não tem** documento fiscal, aparece uma tarja
— *"Esta venda não tem nota fiscal emitida"* — e o botão **Emitir Nota Fiscal**, que abre a **mesma**
confirmação de CPF do fluxo normal (com observações da nota e a escolha do modelo).

⛔ **Isto não é reemitir**, e a regra *"reimpressão nunca reemite documento fiscal"* continua
inteira: a condição é `!dadosFiscais`. Venda **com** nota viva não mostra nada — conferido na tela
com a venda 638, que reabre como "Reimpressão de Nota Fiscal — NFC-e", sem tarja e sem botão — e o
servidor recusa nomeando o número da nota existente.

⚠️ **Guarda novo no servidor:** `exigirVendaNaoCancelada`. Até aqui o único caminho até a emissão
era o PDV logo depois de efetivar, onde a venda não pode estar cancelada; abrir a papeleta antiga
criou um caminho para uma venda de qualquer data, e **esconder o botão nunca foi proteção** (P4).
Emitir ali declararia à SEFAZ uma operação que foi desfeita.

✅ **Medido na tela:** a venda 648, que é **só de serviço**, oferece corretamente **NFS-e** — não
NFC-e.

---

**Revisão 2026-09-04 — o aviso "item lançado" ficou legível.**

O `.pdv-flash` (a pílula verde que confirma o lançamento de cada item) tinha texto `#08210f` sobre
`--sucesso`: contraste **3.18**, abaixo do mínimo de 4.5. Passou a branco: **5.34**.

⚠️ **Não era regressão da paleta azul** — media **3.16** antes dela, então era defeito antigo que só
apareceu porque os 13 pares de contraste foram calculados ao trocar as cores. ⭐ E importa mais do
que o número sugere: é o aviso que o operador lê **de relance, a metros do monitor, o dia inteiro**
— é assim que ele sabe que a bipada entrou.

⚠️ **Não conferido na tela** (pendência 92b): o aviso só aparece ao lançar um item no PDV.
