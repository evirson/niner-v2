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
                                               (2026-08-15, usados pela Entrada de Produtos)
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
não há foto — ver `docs/infra/armazenamento-imagens.md`. `marca`/`referencia` (2026-08-15) vêm do
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
- Dado um item com `qtd` maior que o `disponivel`, quando a venda é efetivada, então a venda é
  gravada normalmente (201) e o saldo daquela variação fica **negativo** — estoque negativo é
  permitido em todo o sistema, nenhuma movimentação bloqueia por saldo insuficiente (2026-07-29).
  Teste: `estoqueInsuficienteNaoBloqueiaVendaEDeixaSaldoNegativo` em
  `api/src/test/java/com/vetor/niner/PdvCrudTest.java:431`.
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
