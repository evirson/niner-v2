# Spec: Papeleta de Venda (PDV)                     Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-06 · Módulo(s): `vendas` (PdvVendaService) · Fase: 2 — Crediário/Caixa (Q5/ADR-010)

## Problema

`docs/telas/pdv.md` efetiva a venda (F5), mas não entregava nenhum comprovante impresso pro
cliente — mesma lacuna que existia no Recebimento de Crediário antes de
`docs/telas/comprovante-recebimento-crediario.md`. Toda venda de loja física precisa de uma
papeleta impressa (bobina térmica) com os produtos, valores e forma(s) de pagamento.

## Solução proposta

Popup automático, aberto assim que o F5 efetiva a venda com sucesso — sem passo extra do
operador (mesmo mecanismo do comprovante de crediário: `ComprovantePapeletaModal.tsx` busca o
comprovante por `idVenda` via `GET /api/v1/pdv/vendas/{id}/comprovante`, dedicado, separado da
resposta de `POST /api/v1/pdv/vendas`). Pré-visualização em texto monoespaçado + dois botões:
**Imprimir** (diálogo nativo do navegador) e **Salvar PDF** (`jsPDF`, sem passar pelo diálogo).

## Decisões (confirmadas com o dono do produto)

1. **64 colunas, não as 42 já usadas no comprovante de crediário/vale** — mockup exato fornecido
   pelo dono do produto (código 13 + descrição 25 + qtd 3 + unitário 9 + total 10, com espaços
   separadores = 64). Fisicamente mais apertado que o padrão anterior (uma linha de 64 colunas
   em 80mm exige fonte bem menor, ~6px/impressão real); resolvido com **`font-family: 'Lucida
   Console'`** (pedido explícito do dono do produto) em vez de forçar um layout mais estreito —
   Lucida Console foi desenhada pela Microsoft especificamente pra ficar legível em tamanho
   pequeno (era a fonte padrão do console do Windows). O PDF (`gerarPdfComprovanteVenda`) **não
   consegue** usar essa fonte — é proprietária da Microsoft e o `jsPDF` só embute TTF empacotado
   no projeto — então cai pra `courier` (fonte nativa do `jsPDF`) num tamanho ainda menor
   (~5pt), só pra caber fisicamente; **o botão "Imprimir" é o caminho recomendado**, o PDF é mais
   um registro de backup.
2. **Descrição do produto em até 3 linhas fixas** (25 caracteres cada, quebra literal — não por
   palavra): concatena `descricaoProduto + variacaoLinha (se tiver) + variacaoColuna (se tiver)`
   num texto só, depois corta em blocos de 25 caracteres; as linhas de continuação não usadas
   ficam em branco (todo item sempre reserva o mesmo espaço vertical fixo).
3. **NCM inexistente ou inválido = venda em branco, nunca rejeitada** — não aplicável aqui
   diretamente (é regra da importação, `ProdutoImportador`), citado só porque a papeleta lê
   `produto.codigo_ncm` do mesmo jeito; não há validação de NCM na venda em si.
4. **Total de itens removido da linha "TOTAL A PAGAR"** (revisão 2026-08-06) — o campo `qtdTotal`
   nasceu na primeira versão, mas o dono do produto pediu a remoção; removido de ponta a ponta
   (backend, DTO, frontend), não só escondido na tela.
5. **Parcelas de CREDIARIO listadas por extenso** (revisão 2026-08-06, mockup próprio) — quando a
   venda tem pelo menos uma linha de pagamento CREDIARIO, a papeleta ganha um bloco extra no
   final ("PARCELAS A VENCER DE CREDIARIO", tabela PARC./VENCIMENTO/VALOR A PAGAR, 37 colunas,
   largura própria dentro da bobina de 64). Filtra por `tipo_carteira.categoria_carteira =
   'CREDIARIO'`, não por parcela em aberto — `CARTAO_DEBITO`/`CARTAO_CREDITO` também ficam em
   aberto até a conciliação (ver `PdvVendaService.efetivarVenda`), mas isso não é "crediário" pro
   cliente que está lendo a papeleta.
6. **Rótulo condicional por categoria da carteira** (revisão 2026-08-06) — "VALOR PAGO EM" pra
   dinheiro/cartão (dinheiro já circulou); **"VALOR A PAGAR EM"** pra CREDIARIO (ainda em aberto,
   ver o bloco de parcelas). `PagamentoComprovanteVenda.crediario: boolean` carrega essa
   distinção do backend pro frontend.

## Regras de negócio

### Fonte única de verdade pro layout

`web/src/lib/comprovante.ts#montarLinhasComprovanteVenda()` monta a papeleta como um array de
linhas de texto — reusado idêntico pela pré-visualização (`<pre>`), pela impressão
(`window.print()`) e pelo PDF (`jsPDF`). Mesmo padrão de `montarLinhasComprovante` (crediário) e
`montarLinhasFechamento` (Fechamento de Caixa), mas com constantes de largura/coluna próprias
(`LARGURA_VENDA = 64`, não reaproveita `LARGURA = 42` do resto do arquivo).

### `venda.id_caixa` — necessário pro "Operador" em venda 100% crediário

`venda` ganhou a coluna `id_caixa` (migration `V025`, editada — banco ainda em construção, sem
migration nova) porque **só linhas de pagamento que não são CREDIARIO gravam em
`caixa_detalhe`** (ver `PdvVendaService.efetivarVenda`); sem isso, uma venda inteiramente em
crediário não tinha nenhum jeito de reconstituir qual sessão de caixa (e portanto qual usuário)
processou a venda. `PdvVendaService.efetivarVenda` já resolvia `idCaixa` antes de gravar (pra
lançar em `caixa_detalhe`) — só faltava persistir na própria `venda`.

### Nenhum recálculo de regra de negócio no comprovante

`buscarComprovante` só lê o que já foi persistido: `subtotal`/`descontos`/`acrescimos` somados
direto de `produto_movimento_detalhe` (rateio já gravado por `efetivarVenda`), `pagamentos`
somados de `contas_receber.valor_receber` agrupado por carteira. `totalAPagar = subtotal −
descontos + acrescimos` bate matematicamente com a soma de `pagamentos` — verificado com venda
real de split-tender (PIX com desconto + DINHEIRO com desconto) e venda 100% CREDIARIO parcelada.

## Contrato de API

```
GET /api/v1/pdv/vendas/{idVenda}/comprovante
```

Sob `/api/v1/**` (JWT de tenant, RLS ativo — P8). 404 (`ResponseStatusException`) se a venda não
existir (ou for de outro tenant — RLS).

```json
{
  "idVenda": 6472,
  "nomeEmpresa": "LOJA TESTE NINER 1",
  "codigoEmpresa": 1,
  "dataVenda": "2026-08-06T21:09:16Z",
  "nomeCliente": "IRENE BARBOSA",
  "nomeVendedor": "PEDRO SOUZA LIMA",
  "nomeOperador": "Administrador Teste",
  "itens": [
    { "sku": "9001000000053", "descricaoProduto": "TEN FEM ALL STAR CHUCK TAYLOR REF: CT33430003",
      "variacaoLinha": "CAFE COM LEITE", "variacaoColuna": "33", "qtd": 1.0,
      "valorUnitario": 319.90, "valorTotal": 319.90 }
  ],
  "subtotal": 319.90,
  "descontos": 0.00,
  "acrescimos": 0.00,
  "totalAPagar": 319.90,
  "pagamentos": [{ "nomeCarteira": "CREDIARIO", "crediario": true, "valorPago": 319.90 }],
  "parcelasCrediario": [
    { "numeroParcela": 1, "totalParcelas": 6, "dataVencimento": "2026-09-05T21:09:16Z", "valorParcela": 53.31 }
  ]
}
```

## Critérios de aceitação (viram testes)

- Dado uma venda já efetivada, quando busca o comprovante, então devolve cabeçalho (empresa,
  cliente, vendedor, operador, data), itens com valor bruto (`qtd × unitário`), totais que
  reconciliam (`totalAPagar = subtotal − descontos + acrescimos`) e pagamentos cuja soma bate
  exatamente com `totalAPagar`.
- Dado uma venda com pagamento em CREDIARIO, quando busca o comprovante, então `pagamentos` traz
  `crediario: true` naquela linha e `parcelasCrediario` traz uma linha por parcela gerada.
- Dado uma venda sem pagamento em CREDIARIO, então `parcelasCrediario` vem vazio.
- Dado um `idVenda` que não existe (ou é de outro tenant), então 404.

Sem testes automatizados ainda (compilação/typecheck limpos + testes manuais via API real,
descritos acima, não suíte JUnit).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

Não se aplica — a papeleta é um popup automático dentro do fluxo do PDV
(`vendas.pdv`, sem `AjudaDaTela` própria — o próprio PDV também não tem uma ainda), não uma tela
própria com rotina de navegação.

## Impacto no banco

`venda` ganhou a coluna `id_caixa` (nullable, FK composta `(id_tenant, id_caixa)` →
`caixa_mestre`) — migration `V025__financeiro_caixa_crediario.sql`, editada (banco em
construção, sem migration nova ainda).

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- **Reimpressão depois de fechar o popup** — sem botão de "ver papeleta" na Pesquisa de Vendas;
  item registrado em Implementações Futuras ("Reimpressão de Papeleta de Venda").
- **Layout configurável (logo, campos extras, papel de tamanhos diferentes)** — só 80mm/64
  colunas, layout fixo.
- **Impressão direta via ESC/POS** — passa pelo driver do sistema operacional via diálogo de
  impressão do navegador.

## Questões abertas

Largura/fonte calculadas por conta física (mm/pt), não testadas numa impressora térmica real
ainda — pode precisar de ajuste fino no primeiro teste real (mesma ressalva já registrada em
`comprovante-recebimento-crediario.md`).

## Métrica de sucesso

Papeleta pronta pra imprimir em menos de 2 segundos depois da venda confirmada.
