# Spec: Comprovante de Pagamento de Crediário       Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-07-30 · Módulo(s): `financeiro` (recebimentocrediario) · Fase: 2 — Crediário/Caixa (Q5/ADR-010)

## Problema

`docs/telas/recebimento-crediario.md` efetiva o recebimento, mas não entregava nenhum
comprovante pro cliente — nem impresso, nem em PDF. Toda loja física com crediário precisa
imprimir um comprovante no ato do pagamento, numa impressora térmica de bobina (rolo contínuo),
80mm de largura.

## Solução proposta

Popup automático, aberto assim que o recebimento é efetivado com sucesso — sem passo extra do
operador. Pré-visualização em texto monoespaçado (42 colunas, largura segura pra bobina de
80mm) + dois botões: **Imprimir** (diálogo nativo do navegador, escolhe a impressora térmica ou
"Salvar como PDF" do próprio sistema) e **Salvar PDF** (gera o arquivo direto, biblioteca
`jsPDF`, sem passar pelo diálogo — os dois pedidos explicitamente separados, não um cobrindo o
outro).

## Decisões (confirmadas com o dono do produto)

1. **Dois botões separados** (Imprimir / Salvar PDF), não um botão só delegando pro diálogo
   nativo do navegador — `jsPDF` adicionado como dependência nova do `web/` (única exceção ao
   "sem biblioteca nova" de outras features, porque gerar PDF direto é o próprio pedido).
2. **Popup automático após efetivar**, sem botão pra reabrir depois — se o operador fechar sem
   imprimir, precisa ir em Estorno de Crediário pra achar o lote de novo (a visualização de lá
   não tem os botões de impressão, é só leitura das parcelas).
3. **Largura real (42 colunas), não a tabela larga do primeiro mockup** — o mockup original
   (~70 colunas) não caberia fisicamente numa bobina de 80mm em fonte legível (máx. ~42-48
   colunas em Font A). Reorganizado em blocos por parcela mantendo as mesmas informações.
4. **Caracteres separadores: `—` (travessão) e `•` (marcador), não `-`/`.` puros nem
   caracteres de desenho de caixa (─/═/Unicode U+2500+)** — travessão e marcador são "gráficos"
   o suficiente e pertencem ao WinAnsiEncoding (CP1252), que a fonte padrão do `jsPDF` desenha
   sem precisar embutir uma fonte TTF nova; caracteres de desenho de caixa ficariam certos na
   tela mas quebrados no PDF (fora do encoding padrão dos "standard 14 fonts" do PDF).

## Regras de negócio

### Fonte única de verdade pro layout

`web/src/lib/comprovante.ts#montarLinhasComprovante()` monta o comprovante como um array de
linhas de texto — reusado idêntico pela pré-visualização na tela (`<pre>`), pela impressão
(`window.print()`, CSS isola só o elemento) e pelo PDF (`jsPDF`, fonte courier). Garante que os
três saem sempre iguais; qualquer ajuste de layout muda um lugar só.

### Ordem do conteúdo (revisada 2x após o primeiro corte)

Cabeçalho (empresa, "COMPROVANTE DE PAGAMENTO DE CREDIÁRIO", cliente) → um bloco por parcela
(venda, PC, vencimento, valor da parcela, multa/juros, valor a pagar daquela parcela) → **Total
a Pagar** (soma de todas as parcelas) → **Forma(s) de Pagamento** (uma linha por tipo de
carteira usado, soma agrupada) → **Total Pago** (soma dos pagamentos) → **Data Pagamento** +
**Identificação** (`id_caixa-id_lote_recebimento`) **por último**. As duas últimas revisões
(dono do produto): o totalizador de pago entra *depois* das formas de pagamento, não antes; e
data/identificação viram o rodapé do comprovante, não uma seção do meio.

### Multa/juros congelados, nunca recalculados

`multaJuros` de cada parcela no comprovante é a diferença entre `contas_receber.valor_recebido`
(gravado na hora do recebimento, RN013 de `recebimento-crediario.md`) e `contas_receber.
valor_receber` (valor original, nunca muda) — reflete exatamente o que foi cobrado naquele
momento, mesmo que a parcela tivesse ficado mais em atraso depois (o que não faz sentido pra uma
parcela já paga, mas reforça que não há recálculo).

### Impressão pra bobina física de 80mm (não só a pré-visualização)

`@page { size: 80mm auto; margin: 0; }` (CSS global, único uso de impressão no app hoje) — sem
isso o navegador tenta encaixar a impressão no tamanho de página padrão do sistema (A4/Carta) em
vez do rolo contínuo. Fonte de impressão reduzida (9px/~6,75pt) com 3mm de margem lateral: 42
colunas ocupam ~60mm de conteúdo, com folga real dentro dos 80mm físicos. O PDF usa página
`[80mm, altura dinâmica]`, margem de 4mm, fonte courier 8pt — mesma folga. **2026-08-11:** a
altura dinâmica agora tem um piso de 80mm — sem ele, um comprovante curto o bastante fazia o jsPDF
inverter largura/altura (bug achado no Vale-Mercadoria, que costuma ter só 1 item; corrigido nas
três variantes de `comprovante.ts`, detalhe em `docs/telas/papeleta-venda.md`).

## Contrato de API

```
GET /api/v1/recebimento-crediario/{idLoteRecebimento}/comprovante
```

Sob `/api/v1/**` (JWT de tenant, RLS ativo — P8), aberto a ADMIN e OPERADOR (mesma decisão do
resto do módulo). 404 (`ResponseStatusException`) se o lote não existir (ou for de outro
tenant — RLS).

```json
{
  "idLoteRecebimento": 16,
  "idCaixa": 12,
  "nomeEmpresa": "Loja Teste Manual",
  "nomeCliente": "CLAUDIO CALIXTO",
  "telefoneCliente": "41984133869",
  "dataPagamento": "2026-07-30T15:06:13Z",
  "parcelas": [
    { "idVenda": 20, "numeroParcela": 1, "totalParcelas": 1, "dataVencimento": "...",
      "valorParcela": 300.00, "multaJuros": 6.00, "valorAPagar": 306.00 }
  ],
  "valorTotalAPagar": 306.00,
  "pagamentos": [{ "nomeCarteira": "DINHEIRO", "valorPago": 306.00 }]
}
```

## Critérios de aceitação (viram testes)

- Dado um lote de recebimento já efetivado, quando busca o comprovante, então devolve
  cabeçalho (empresa, cliente, data, `id_caixa`), uma linha por parcela com `multaJuros`/
  `valorAPagar` batendo com o que foi cobrado, e uma linha por forma de pagamento com a soma
  correta (mesma carteira usada em mais de uma parcela soma numa linha só).
- Dado um `idLoteRecebimento` que não existe (ou é de outro tenant), então 404.

Cobertos por `RecebimentoCrediarioCrudTest` (+2 testes:
`comprovanteTrazCabecalhoParcelasEFormasDePagamento`, `comprovanteDeLoteInexistenteResponde404`).
Suíte completa do projeto: 213/213 verdes (2026-07-30).

## Reimpressão (2026-08-06 — deixou de ser non-goal)

Tela nova, **Reimpressão de Recebimento de Crediário**
(`/reimpressao-recebimento-crediario`, `ReimpressaoRecebimentoCrediario.tsx`, grupo de menu
"Frente de Loja › Reimpressões") — 100% frontend, reaproveita o endpoint que o Estorno de
Crediário já usava pra achar lotes (`GET /api/v1/recebimento-crediario/estornos`), só sem a ação
de estornar:

- Filtros: **nome do cliente** (obrigatório — mesma regra do Estorno, evita busca sem limite) +
  data inicial/final do recebimento (opcionais).
- Grid com um lote por linha (data, cliente, qtd. de parcelas, valor total, formas de pagamento
  usadas) — mesmas colunas do Estorno, sem a ação de estornar.
- Clicar numa linha abre `ComprovanteRecebimentoModal` com a prop `reimpressao` ligada.
  `montarLinhasComprovante(c, reimpressao)` ganha um segundo parâmetro opcional: quando `true`,
  troca o título "COMPROVANTE DE PAGAMENTO / DE CREDIARIO" por **"REIMPRESSÃO DE PAPELETA DE /
  RECEBIMENTO DE CREDIARIO"** e acrescenta **"Impresso em: dd/mm/aaaa hh:mm"** (data/hora da
  reimpressão) no final, depois da linha "Identificacao".

Testado com um lote real (2 parcelas, R$ 371,40).

## Envio por WhatsApp (2026-08-07)

Terceiro botão no rodapé do popup (além de Imprimir/Salvar PDF), presente tanto no fluxo normal
(pós-efetivar) quanto na Reimpressão. Clicar em **"Enviar por WhatsApp"** abre um popup de
confirmação (`EnviarWhatsAppModal.tsx`, componente genérico e reutilizável) com o celular do
cliente (`telefoneCliente`, campo novo desta rodada) já preenchido, mas editável — só ao confirmar
ali é que o PDF é gerado (mesmo `jsPDF` de "Salvar PDF", como Blob em vez de baixar), sobe pro
cache temporário da API e abre um link `wa.me` com a mensagem e o link de download já prontos.
Quem de fato envia é o operador, clicando em "Enviar" na conversa do WhatsApp — não é uma
integração automática. Detalhe completo do mecanismo (por que `wa.me` em vez da API oficial, como
o arquivo é guardado, o limite de 20 por tenant, os gaps conhecidos):
`docs/infra/compartilhamento-arquivo-temporario.md`.

## Layout do popup — cabeçalho e rodapé fixos (2026-08-07)

O popup (fluxo normal e Reimpressão) passou a ter o título e a faixa de botões sempre visíveis —
só a pré-visualização (`<pre>`) rola por dentro quando o comprovante é longo. Mesmo mecanismo já
usado em `CancelamentoVendaModal.tsx` (`.modal` em coluna flex com `overflow:hidden`; título e
rodapé com `flexShrink:0`; miolo com `overflow-y:auto; flex:1; min-height:0`).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

O popup em si (pós-efetivar) não se aplica — é automático dentro do fluxo de Recebimento de
Crediário (`financeiro.recebimentocrediario.tela`), sem rotina de navegação própria. A tela de
**Reimpressão** tem a sua: `financeiro.reimpressaorecebimentocrediario.tela`. As duas entradas
foram atualizadas (2026-08-07) pra mencionar o botão de WhatsApp.

## Impacto no banco

Nenhum — só leitura de tabelas já existentes (`contas_receber_lote`, `contas_receber`,
`caixa_detalhe`, `cliente`, `empresa`, `tipo_carteira`).

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- **Layout configurável (logo, campos extras, papel de tamanhos diferentes)** — só 80mm, layout
  fixo.
- **Impressão direta via ESC/POS (bytes crus pra impressora)** — passa pelo driver do sistema
  operacional via diálogo de impressão do navegador, não por comando de impressora.

## Questões abertas

Nenhuma bloqueante. Fonte de impressão/margens ajustadas por cálculo (não testadas numa
impressora térmica física ainda) — pode precisar de ajuste fino depois do primeiro teste real.

## Métrica de sucesso

Comprovante pronto pra imprimir em menos de 2 segundos depois do recebimento confirmado.
