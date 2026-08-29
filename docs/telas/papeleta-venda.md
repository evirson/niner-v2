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

1. ⚠️ **42 colunas, item em 2 linhas** — **revisto em 2026-08-14 depois da primeira impressão em
   bobina real**. A versão original era de 64 colunas numa linha por item (mockup do dono do
   produto: código 13 + descrição 25 + qtd 3 + unitário 9 + total 10 + separadores), apostando na
   `Lucida Console` para caber. Impresso de verdade, saiu **ilegível** — e a conta explica por
   quê: o papel tem 80mm, mas a **área de impressão é ~72mm**; descontada a margem sobram ~68mm,
   o que dá 1,06mm por caractere, ou seja fonte de ~6px.

   O layout novo, pedido pelo dono do produto, **quebra o item em duas linhas em vez de espremer
   a largura**: a 1ª traz `código de barras + o que couber do nome` (28 caracteres), a 2ª traz
   `qtd × unitário` recuado e o `total` alinhado à direita. Com 42 colunas — o padrão clássico de
   térmica 80mm, e o mesmo do comprovante de crediário/vale — cada caractere ganha ~1,6mm.
   Ganho colateral: nomes longos que antes quebravam (ex.: "SANDALIA RASTEIRA FEMININA") passaram
   a caber inteiros numa linha. O PDF acompanha a mesma montagem e subiu de ~5pt para **8pt**.
2. **Descrição do produto em até 3 linhas** (28 caracteres cada, quebra literal — não por
   palavra): concatena `descricaoProduto + variacaoCor (se tiver) + variacaoTamanho (se tiver)`
   num texto só, depois corta em blocos. Desde a revisão de 2026-08-14 **não há mais linhas em
   branco de enchimento** — com o item já ocupando 2 linhas, reservar espaço vertical fixo
   desperdiçaria papel em toda venda.
3. **NCM inexistente ou inválido = venda em branco, nunca rejeitada** — não aplicável aqui
   diretamente (é regra da importação, `ProdutoImportador`), citado só porque a papeleta lê
   `produto.codigo_ncm` do mesmo jeito; não há validação de NCM na venda em si.
4. **Total de itens removido da linha "TOTAL A PAGAR"** (revisão 2026-08-06) — o campo `qtdTotal`
   nasceu na primeira versão, mas o dono do produto pediu a remoção; removido de ponta a ponta
   (backend, DTO, frontend), não só escondido na tela.
5. **Parcelas de CREDIARIO listadas por extenso** (revisão 2026-08-06, mockup próprio) — quando a
   venda tem pelo menos uma linha de pagamento CREDIARIO, a papeleta ganha um bloco extra no
   final ("PARCELAS A VENCER DE CREDIARIO", tabela PARC./VENCIMENTO/VALOR A PAGAR, 37 colunas,
   largura própria dentro da bobina de 42). Filtra por `tipo_carteira.categoria_carteira =
   'CREDIARIO'`, não por parcela em aberto — `CARTAO_DEBITO`/`CARTAO_CREDITO` também ficam em
   aberto até a conciliação (ver `PdvVendaService.efetivarVenda`), mas isso não é "crediário" pro
   cliente que está lendo a papeleta.

## CSS de impressão — o que cada valor resolve (2026-08-14)

Três ajustes, todos descobertos imprimindo em bobina real e conferindo a foto do resultado. Valem
como referência para qualquer outro documento térmico do produto:

| Regra | Por quê |
|---|---|
| `width: 75mm` (era 80mm) | Prometer 80mm ao navegador quando a impressora marca ~72–75mm faz o **driver encolher tudo** pra caber — a fonte já pequena diminuía mais ~10%. |
| `left: 0` | A origem do CSS na impressão **já é o começo da área imprimível**, não a borda do papel. Um `left: 4mm` que existiu por uma rodada empurrou o conteúdo pra direita e cortou o fim das linhas ("TOTAL" saiu "TOTAI"). |
| `font-weight: bold` | Impressão térmica é **1 bit**: cada ponto sai preto ou não sai. Haste fina cai em meio-tom, o navegador dissolve em cinza e a cabeça marca só parte dos pontos — é o que faz a letra sair "falhada". Negrito faz cada haste cobrir um ponto inteiro. |
| `print-color-adjust: exact` | Impede o navegador de clarear o preto pra "economizar tinta" — regra sem sentido em térmica. |
| `font-family: Consolas, …` | Haste mais grossa e hinting melhor em corpo pequeno que a Lucida Console, e **mais estreita** (~0,55em contra 0,6em). |
| `font-size: 11.5px` | ⚠️ **Calibrado para a Consolas**: 42 × 0,55 × 11,5px ≈ 70mm, dentro dos 73mm úteis. Nas fontes de reserva (0,6em) o mesmo tamanho daria ~77mm e voltaria a cortar à direita — se a Consolas faltar no parque de máquinas, baixar o `font-size` junto. |

Se depois disso a impressão ainda sair fraca, **não é mais CSS**: é densidade da impressora
(`Density`/`Darkness`) ou o modo de meio-tom do driver — `Halftone` em modo texto/nenhum imprime
preto sólido, em vez de dissolver o cinza em pontos alternados.
6. **Rótulo condicional por categoria da carteira** (revisão 2026-08-06) — "VALOR PAGO EM" pra
   dinheiro/cartão (dinheiro já circulou); **"VALOR A PAGAR EM"** pra CREDIARIO (ainda em aberto,
   ver o bloco de parcelas). `PagamentoComprovanteVenda.crediario: boolean` carrega essa
   distinção do backend pro frontend.

### Os três comprovantes térmicos estão calibrados igual, PDF incluído (2026-08-14)

Papeleta de Venda, Comprovante de Recebimento de Crediário e Vale-Mercadoria saem na **mesma
bobina física** e, desde 2026-08-14, compartilham exatamente a mesma calibragem — o CSS acima para
a impressão e as mesmas constantes de jsPDF para o PDF/Blob do WhatsApp:

| Comprovante | Monta as linhas | Monta o PDF | Fonte / altura de linha |
|---|---|---|---|
| Papeleta de Venda | `montarLinhasComprovanteVenda` | `montarDocumentoComprovanteVenda` (`comprovante.ts:330-345`) | 8pt / 3,6mm |
| Comprovante de Crediário | `montarLinhasComprovante` | `montarDocumentoComprovante` (`:126-139`) | 8pt / 3,6mm |
| Vale-Mercadoria | reusa as funções da papeleta | `montarDocumentoComprovanteVale` (`:417-430`) | 8pt / 3,6mm |

O vale era a exceção: acompanhou a mudança para 42 colunas **no texto e na impressão** (usa as
mesmas funções de montagem de linhas), mas seu PDF ficou para trás em **5pt / 2,6mm** e saía
visivelmente menor que os outros dois — o docstring da função afirmava "mesma largura/fonte"
enquanto os números diziam outra coisa. Corrigido na mesma data; ver
`docs/telas/devolucao-produtos.md`. Como o desvio só existia no jsPDF, **não aparecia na impressão
térmica** — mais um caso da regra de que calibragem de bobina só se confere imprimindo, e PDF e
impressão precisam ser conferidos **separadamente**.

## Regras de negócio

### Fonte única de verdade pro layout

`web/src/lib/comprovante.ts#montarLinhasComprovanteVenda()` monta a papeleta como um array de
linhas de texto — reusado idêntico pela pré-visualização (`<pre>`), pela impressão
(`window.print()`) e pelo PDF (`jsPDF`). Mesmo padrão de `montarLinhasComprovante` (crediário) e
`montarLinhasFechamento` (Fechamento de Caixa), mas com constantes de largura/coluna próprias
(`LARGURA_VENDA = 42`, hoje idêntico ao `LARGURA = 42` do resto do arquivo — a papeleta nasceu
com 64 colunas e foi alinhada às 42 na revisão de impressão de 2026-08-14).

### Bug corrigido (2026-08-11) — jsPDF invertia largura/altura em comprovantes curtos

`montarDocumentoComprovante*` (as três variantes: crediário, venda, vale) criam o PDF com
`new jsPDF({ unit: 'mm', format: [80, altura] })`, onde `altura` é calculada a partir do número de
linhas do comprovante. O jsPDF, em orientação retrato (padrão quando `orientation` não é passado),
**troca largura por altura sempre que `largura > altura`** (é assim que garante que "retrato" seja
sempre mais alto que largo). Como a papeleta de venda normalmente tem itens/blocos suficientes pra
ultrapassar 80mm de altura, o bug nunca apareceu nela — mas o Vale-Mercadoria
([[project_devolucao_produtos_vale_mercadoria]], mesmas funções desde 2026-08-07) costuma ter só 1
item, ficando abaixo de 80mm de altura: a página saía com ~57mm de largura em vez de 80mm,
cortando as colunas da direita (TOTAL, valor do vale). Corrigido impondo um piso de 80mm na altura
calculada (`Math.max(LARGURA_MM, margem*2 + linhas.length*alturaLinha)`, constante `LARGURA_MM`
nova no topo de `comprovante.ts`) nas três funções — nunca reintroduzir `format: [80, altura]` sem
esse piso em nenhum comprovante novo que reaproveite este padrão.

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
  "telefoneCliente": "42998195577",
  "nomeVendedor": "PEDRO SOUZA LIMA",
  "nomeOperador": "Administrador Teste",
  "itens": [
    { "sku": "9001000000053", "descricaoProduto": "TEN FEM ALL STAR CHUCK TAYLOR REF: CT33430003",
      "variacaoCor": "CAFE COM LEITE", "variacaoTamanho": "33", "qtd": 1.0,
      "valorUnitario": 319.90, "valorTotal": 319.90 }
  ],
  "subtotal": 319.90,
  "descontos": 0.00,
  "acrescimos": 0.00,
  "totalAPagar": 319.90,
  "pagamentos": [{ "nomeCarteira": "CREDIARIO", "crediario": true, "valorPago": 319.90 }],
  "parcelasCrediario": [
    { "numeroParcela": 1, "totalParcelas": 6, "dataVencimento": "2026-09-05T21:09:16Z", "valorParcela": 53.31 }
  ],
  "cancelada": false
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

## Reimpressão (2026-08-06, mesmo dia — deixou de ser non-goal)

> ⚠️ **A tela dedicada abaixo saiu do projeto em 2026-08-18.** `ReimpressaoPapeletaVenda.tsx`,
> a rota `/reimpressao-papeleta-venda` e o grupo de menu "Frente de Loja › Reimpressões" foram
> removidos — ficaram redundantes com o botão "Reimprimir papeleta" que o popup de detalhe da
> Pesquisa de Vendas ganhou no mesmo dia. A lógica descrita abaixo (prop `reimpressao`,
> `montarLinhasComprovanteVenda` com o segundo parâmetro) **continua valendo por completo** —
> só a casca (tela de busca dedicada) mudou de endereço. Ver `docs/telas/pesquisa-vendas.md`
> pra onde o botão mora agora.

Tela nova, **Reimpressão de Papeleta de Venda** (`/reimpressao-papeleta-venda`,
`ReimpressaoPapeletaVenda.tsx`, grupo de menu "Frente de Loja › Reimpressões", junto com a
reimpressão de crediário abaixo) — 100% frontend, zero endpoint novo:

- Filtros: **Nº da venda** (ignora os demais quando preenchido) **ou** **data inicial + data
  final + cliente** (popup de busca, mesmo componente do PDV) — reaproveita
  `GET /api/v1/vendas/pesquisa` (Pesquisa de Vendas), que já tinha exatamente esses filtros.
- Grid com os dados da venda (empresa, nº, data, cliente, vendedor, valor, situação).
- Clicar numa linha abre `ComprovantePapeletaModal` com a prop `reimpressao` ligada — o mesmo
  componente do popup pós-F5, sem duplicar lógica de montagem do papel.
  `montarLinhasComprovanteVenda(c, reimpressao)` ganha um segundo parâmetro opcional: quando
  `true`, insere **"REIMPRESSÃO DE PAPELETA DE VENDA"** centralizado logo abaixo do nome da
  empresa e acrescenta **"Impresso em: dd/mm/aaaa hh:mm"** (data/hora da reimpressão, não da
  venda) no final, antes do último divisor.

Testado com venda real, inclusive uma venda sintética de importação (sem itens/vendedor/
operador) — os campos vazios caem certinho em "(não informado)".

### ⛔ Venda cancelada NÃO tem reimpressão (2026-08-20 — substitui o aviso de 2026-08-19)

**Regra atual:** o botão "Reimprimir papeleta" **não aparece** para venda cancelada, e
`GET /api/v1/pdv/vendas/{id}/comprovante` responde **409** — checagem no servidor porque o
endpoint atende qualquer usuário do tenant e a tela não é o único caminho até ele (P4).

**O que havia antes, e por que mudou.** Em 2026-08-19 a reimpressão passou a sair com
`**** VENDA CANCELADA ****` como primeira linha do cupom, para ninguém confundir com uma venda
válida. O dono do produto trocou o aviso pela recusa um dia depois, e o motivo é melhor que a
solução anterior: **papel impresso circula**. Um cupom de venda cancelada na mão de alguém é um
documento afirmando uma venda que não existe mais — e a única forma de ele não circular é não
imprimir. Avisar dependia de quem estivesse lendo; recusar não depende de ninguém.

⚠️ O campo `cancelada` **continua** em `ComprovanteVendaResponse`/`ComprovanteVenda`: o detalhe da
venda o usa para o selo "Cancelada" na tela. O que saiu foi só o bloco de linhas em
`montarLinhasComprovanteVenda` (`web/src/lib/comprovante.ts`), que hoje traz um comentário
explicando a remoção — sem ele, o próximo a ler o DTO reintroduz o aviso achando que faltava.

Nota que continua valendo: venda cancelada com NFC-e **nunca** chegaria como DANFCE aqui de
qualquer forma, porque `buscarDadosFiscais` só considera documento `AUTORIZADO`/`CONTINGENCIA` e o
cancelamento marca `documento_fiscal.situacao = CANCELADO`
(`docs/telas/cancelamento-venda.md`).

## Envio por WhatsApp (2026-08-07)

Terceiro botão no rodapé do popup (além de Imprimir/Salvar PDF), presente tanto no fluxo normal
(pós-F5) quanto na Reimpressão. Mesmo mecanismo do Comprovante de Pagamento de Crediário: clicar
em **"Enviar por WhatsApp"** abre `EnviarWhatsAppModal.tsx` (componente genérico, reaproveitado
dos dois comprovantes) com o celular do cliente (`telefoneCliente`, campo novo desta rodada) já
preenchido, mas editável — só ao confirmar ali é que a papeleta vira Blob (mesmo `jsPDF` de
"Salvar PDF"), sobe pro cache temporário da API e abre um link `wa.me` com a mensagem e o link de
download prontos. Quem de fato envia é o operador, na própria conversa do WhatsApp — não é
integração automática. Detalhe completo do mecanismo compartilhado (por que `wa.me`, como o
arquivo é guardado, o limite de 20 por tenant — somado com os outros 3 fluxos que usam o mesmo
cache, não 20 só pra papeleta —, gaps conhecidos): `docs/infra/compartilhamento-arquivo-temporario.md`.

## Layout do popup — cabeçalho e rodapé fixos (2026-08-07)

O popup (fluxo normal e Reimpressão) passou a ter o título e a faixa de botões sempre visíveis —
só a pré-visualização (`<pre>`) rola por dentro quando a papeleta é longa. Mesmo mecanismo já
usado em `CancelamentoVendaModal.tsx` (`.modal` em coluna flex com `overflow:hidden`; título e
rodapé com `flexShrink:0`; miolo com `overflow-y:auto; flex:1; min-height:0`).

## Emissão fiscal automática × manual (2026-08-19, `cfg_geral.cfg_emite_fiscal_apos_venda`)

Pedido do dono do produto: até aqui a emissão da NFC-e (§9.6 do estudo fiscal, bloco B7) sempre
disparava sozinha, sem parâmetro pra desligar. Ganhou um `checkbox` em Parâmetros do Sistema >
Fiscal — comportamento no popup (`ComprovantePapeletaModal.tsx`):

- **Ligado (default):** o popup **não** dispara nada sozinho e **não** mostra a papeleta com
  "DOCUMENTO SEM VALOR FISCAL" antes de perguntar — primeiro aparece uma tela de confirmação
  ("Confirmar Emissão da Nota Fiscal") com cliente, valor total e formas de pagamento, mais a
  pergunta "Deseja incluir o CPF do cliente nesta nota fiscal?" (ver seção seguinte). Só depois de
  o operador responder é que `emitirNfce(idVenda, incluirCpf)` é chamado; o resultado é tratado do
  jeito de sempre (Toast, invalida a query do comprovante, popup vira DANFCE quando autoriza), e aí
  sim a papeleta/DANFCE e os botões "Enviar por WhatsApp"/"Imprimir" aparecem.
- **Desligado:** a papeleta comum ("DOCUMENTO SEM VALOR FISCAL") aparece direto, sem pergunta
  nenhuma. Um botão **"Emitir Nota Fiscal"** no rodapé abre a **mesma** tela de confirmação de
  CPF sob demanda — com um "Cancelar" a mais (volta pra papeleta sem emitir), já que aqui é
  opcional. Só aparece quando `!dadosFiscais` (some depois de emitir com sucesso; reaparece se a
  emissão veio rejeitada/falhou, pra tentar de novo).
- A configuração é buscada via `GET /api/v1/config-geral/emite-fiscal-apos-venda` (flag leve, sem
  checagem de papel — qualquer operador efetiva venda), com `enabled: !reimpressao`: **em
  reimpressão a query nem roda**, então nem a pergunta nem o botão manual aparecem lá —
  reimprimir uma papeleta já emitida não é (e não pode virar) um jeito de reemitir documento
  fiscal.

### Pergunta de CPF antes de emitir (2026-08-19)

Pedido do dono do produto: a nota nunca mais decide sozinha, a partir do cliente vinculado à
venda, se o CPF entra ou não. `ComprovanteVendaResponse`/`ComprovanteVenda` ganharam
`documentoCliente` (CPF/CNPJ do cliente da venda, `null` sem cliente ou sem documento
cadastrado) e `emitirNfce`/`VendaFiscalAssembler.montar`/`VendaFiscalController` ganharam o
parâmetro `incluirCpf` (corpo `POST .../nfce` passou a exigir `{"incluirCpf": boolean}`):

- **Cliente com CPF/CNPJ cadastrado:** a tela mostra o documento mascarado
  (`mascararCpfCnpj`, PF/PJ inferido pelo tamanho do documento — 11 dígitos = CPF) e dois botões.
  ⚠️ **Revisado em 2026-08-25** (ver "A escolha troca o modelo" logo abaixo): o rótulo era fixo em
  "CPF" e os botões não diziam o que sairia. Hoje o rótulo acompanha o cliente
  ("CPF do cliente" × "CNPJ do cliente") e os botões nomeiam o documento:
  **"Sim, identificar — NF-e (modelo 55)"** (ou "— NFC-e", se PF) e
  **"Não, emitir para consumidor — NFC-e"**.
- **Cliente sem documento (ou venda sem cliente identificado com CPF):** a tela mostra só um
  aviso ("a nota vai sair para consumidor não identificado") e um botão único, "Confirmar e
  emitir" — nunca oferece a opção de incluir o que não existe.
- No backend, `VendaFiscalAssembler.buscarDestinatarioObrigatorio` recusa com 409 se
  `incluirCpf=true` mas a venda não tem cliente ou o cliente não tem documento — defesa em
  profundidade (P4), já que o frontend nunca deveria oferecer "sim" nesse caso.

Verificado ao vivo (venda real, SEFAZ-PR homologação): cliente com CPF respondendo "sim" → XML
com `<dest><CPF>...</CPF>...</dest>`; mesmo cliente respondendo "não" → XML **sem** bloco `dest`
nenhum (consumidor não identificado); cliente sem CPF cadastrado → tela de aviso único, mesmo
resultado sem `dest`. Emissão manual (parâmetro desligado) abre a mesma tela, com "Cancelar"
funcionando (some a pergunta, volta pra papeleta comum, nada é emitido).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

O popup em si (pós-F5) não se aplica — é automático dentro do fluxo do PDV, sem `AjudaDaTela`
própria; a ajuda relevante vive em `pdv.tela` (o fluxo que abre o popup, incluindo o parâmetro de
emissão automática/manual) e em `vendas.pesquisavendas.tela` (o botão "Reimprimir papeleta",
desde 2026-08-18 — ver nota no topo desta seção sobre a tela dedicada removida, cuja entrada
`vendas.reimpressaopapeleta.tela` também saiu do `AjudaDaTela.tsx` junto). O parâmetro em si tem
a sua própria em `configuracao.geral.form`.

## Impacto no banco

`venda` ganhou a coluna `id_caixa` (nullable, FK composta `(id_tenant, id_caixa)` →
`caixa_mestre`) — migration `V025__financeiro_caixa_crediario.sql`, editada (banco em
construção, sem migration nova ainda).

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- **Layout configurável (logo, campos extras, papel de tamanhos diferentes)** — só bobina de 80mm
  de papel (75mm de área imprimível) / 42 colunas, layout fixo.
- **Impressão direta via ESC/POS** — passa pelo driver do sistema operacional via diálogo de
  impressão do navegador.

## Questões abertas

Nenhuma bloqueante. ~~Largura/fonte calculadas por conta física (mm/pt), não testadas numa
impressora térmica real ainda — pode precisar de ajuste fino no primeiro teste real.~~ —
**resolvido em 2026-08-14**: a calibragem descrita neste arquivo (75mm de área imprimível,
`left: 0`, 42 colunas, Consolas em negrito, item em 2 linhas) veio **do teste real na bobina** —
foi ela que substituiu as 64 colunas ilegíveis do primeiro corte. O ajuste foi replicado em
`comprovante-recebimento-crediario.md`, que sai na mesma impressora.

## Métrica de sucesso

Papeleta pronta pra imprimir em menos de 2 segundos depois da venda confirmada.

### ⚠️ A escolha do popup TROCA o modelo do documento (2026-08-25)

Levantado pelo dono do produto ao investigar o `cStat 974`: *"se eu clicar em sim, incluir CPF vai
pra este modelo que está dando erro, se eu clicar em não, emitir para consumidor, vai pro modelo
65"*. Está certo, e a regra real tem **duas** condições, não uma
(`VendaFiscalAssembler.montar`):

```java
Destinatario destinatario = incluirCpf ? buscarDestinatarioObrigatorio(...) : null;
ModeloVenda modelo = destinatario != null && destinatario.pessoaJuridica()
        ? ModeloVenda.NFE      // 55
        : ModeloVenda.NFCE;    // 65
```

| Escolha | Cliente PF | Cliente PJ |
|---|---|---|
| "Sim, identificar" | NFC-e (65), com `<dest><CPF>` | **NF-e (55)**, com `<dest><CNPJ>` |
| "Não, emitir para consumidor" | NFC-e (65), sem `dest` | **NFC-e (65)**, sem `dest` |

⚠️ **Não é "PJ ⇒ NF-e 55" e ponto** — é *destinatário informado **e** PJ*. Responder "não" para um
cliente PJ emite NFC-e ao consumidor, sem o CNPJ do comprador.

**Por que virou mudança de tela.** O operador estava escolhendo entre **dois documentos
fiscalmente diferentes** sem nenhuma indicação disso. Pior no cenário que provocou a descoberta:
quem clicar em "não" só para escapar do erro do 974 emite NFC-e para uma empresa que provavelmente
precisa da nota para crédito — exatamente o que a regra PJ→55 (2026-08-24) existe para evitar. Por
isso os botões agora nomeiam o modelo, e para PJ entra uma linha explicando a consequência.

⛔ Esta tela **não segue o padrão do projeto** e será reformulada — decisão do dono do produto em
2026-08-25. As mudanças acima são corretivas, não o redesenho.

---

## ⚠️ NF-e 55 no popup: a papeleta é comum e o DANFE vem por botão (2026-08-29)

**O que o dono do produto viu.** Venda 623, cliente pessoa jurídica. A papeleta saía certo, mas
**emitir a nota fiscal modelo 55 no PDV** e **reimprimir a papeleta pela Pesquisa de Vendas**
deixavam a **tela preta**, com a URL parada em `/pdv` e `/pesquisa-vendas`.

### O defeito que apagava a tela

`qrCodeUrl` chega **`null`** na NF-e 55 — QR Code é da **NFC-e 65**, e a 55 não tem nenhum. O
`useEffect` do `ComprovantePapeletaModal` testava só a presença de `dadosFiscais` e mandava esse
`null` para `gerarQrCodeDataUrl`.

⚠️ **A lib mente sobre a causa.** `QRCode.toDataURL` é sobrecarregada — `(texto, opts)` **ou**
`(canvas, texto, opts)` — e decide pelo **tipo do 1º argumento**: o que não é string vira "canvas",
e a chamada estoura **síncrona** com `Cannot read properties of null (reading 'getContext')`. Uma
mensagem sobre `<canvas>` para um problema de **dado fiscal**.

⛔ **E o que transformou uma linha em "não sai nada" foi a ausência de error boundary.** Exceção
que escapa de um efeito desmonta a árvore inteira do React, e o que sobra é o fundo do `<body>` —
no tema escuro, **preto**, sem mensagem e sem stack. Hoje existe `components/LimiteDeErro.tsx`, com
a rota como chave de reset (sem a chave o erro ficaria **preso**, trocando a tela preta por um erro
eterno).

⚠️ **Por que o `tsc -b` não avisou:** o tipo declarava `qrCodeUrl: string` e `urlConsultaChave:
string`, e os dois são nulos na 55. **Campo que o servidor pode devolver nulo é `| null` no tipo,
senão o type-check vira carimbo.** Não foi regressão das rodadas de auditoria de 29/08: nasceu em
**24/08**, quando a venda a PJ passou a sair em NF-e 55 — e só aparece nesse caminho, porque PF
continua em NFC-e, que tem QR.

### O segundo defeito, achado ao corrigir o primeiro

Com a tela viva, apareceu o que ela mostrava: a reimpressão de uma venda **PJ** montava o **DANFCE
térmico**, com o cabeçalho *"DANFE NFC-e — Documento Auxiliar da Nota Fiscal Eletrônica de
Consumidor Final"* e o aviso *"NFC-e não permite aproveitamento de crédito de ICMS"* — **sobre uma
NF-e modelo 55**. Um papel na mão do cliente afirmando o que não é. A linha "Consulte pela chave de
acesso em:" saía seguida de **nada**, porque `urlConsultaChave` também é nulo na 55.

Causa: o front só sabia que *"tem documento fiscal"*. **`modelo` não vinha no contrato** —
`DadosFiscaisComprovante` agora carrega `modelo` e `idDocumentoFiscal`, lidos de
`documento_fiscal`. ⛔ Derivar o modelo da chave de acesso ou assumir 65 seria a mesma armadilha da
constante literal que já custou um `cStat 253` aqui.

### O desenho (decisão do dono do produto, 2026-08-29)

Na **NF-e 55**, reimprimir mostra a **papeleta comum, não fiscal** (título "Reimpressão de Papeleta
de Venda") e o DANFE vem pelo botão **"Ver DANFE"** no rodapé, que abre o `DanfeModal` em A4 — o
**mesmo desenho que o PDV já fazia ao emitir**: papeleta na térmica, DANFE no A4, e a papeleta
nunca substituiu a nota.

Na **NFC-e 65** nada muda: o cupom continua sendo o documento, com QR Code e URL de consulta, e o
botão "Ver DANFE" **não aparece** — ele prometeria um DANFE que não existe.

### Medido, não inferido

- Reimpressão da venda **623** (NF-e 55): antes, tela preta + `TypeError` no console. Depois,
  papeleta comum, botão "Ver DANFE" abrindo o DANFE A4 com protocolo, console **limpo**.
- Controle negativo, venda **603** (NFC-e 65): continua "Reimpressão de Nota Fiscal — NFC-e", com
  QR Code, URL de consulta e **sem** o botão "Ver DANFE".
- ⚠️ **Não medido pelo caminho dele:** emitir a NF-e 55 **pelo PDV** exige uma venda nova. É o
  mesmo componente e a mesma linha, mas essa prova está faltando.
