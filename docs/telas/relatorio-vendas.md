# Spec: Relatório de Vendas                        Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-07-31 · Módulo(s): `vendas`/`relatorios` · Fase: 2

## Problema

Não existe hoje nenhuma tela de relatório gerencial no sistema — o grupo de menu **Relatórios**
é um placeholder vazio desde a reorganização do menu lateral (2026-07-31). O dono do produto
quer a primeira tela do grupo e, ao mesmo tempo, usar essa entrega para **definir o padrão de
tela de filtro de relatório** que as próximas telas do grupo vão reaproveitar: painel de filtros
→ KPIs → composição do faturamento → gráficos → grid totalizável com drill-down analítico.

## Solução proposta

Tela `/relatorio-vendas`, disponível a **qualquer papel** (mesmo padrão de acesso da Pesquisa de
Vendas), somente consulta. Um painel de filtros no topo (período, empresas, vendedor, totalizador)
controla uma única chamada de API que devolve tudo de uma vez: 4 cards de KPI, a composição do
faturamento em 5 blocos, 7 gráficos (Recharts, nova dependência do `web/`) e uma grid final que
totaliza pela dimensão escolhida — clicar numa linha agrupada abre um popup com o detalhamento
analítico daquele grupo (mesmo padrão de popup de `DetalheVendaModal.tsx`).

## Decisões de escopo

Todas confirmadas com o dono do produto durante o planejamento — registradas aqui porque não
havia uma tela desse tipo antes para servir de precedente.

1. **Biblioteca de gráficos:** adicionada `recharts` como nova dependência do `web/` (mesmo
   espírito de ter adicionado `jspdf` para o comprovante de crediário) — não existia nenhuma
   biblioteca de gráfico no projeto até esta tela.
2. **Vendedor por venda = 1 (não rateado por item).** Uma venda pode ter itens de vendedores
   diferentes no ledger, mas o PDV hoje só aceita **um** `idFuncionario` por venda inteira (é
   um campo único no corpo da requisição de efetivação) — na prática já é sempre uniforme.
   Totalização/ranking por vendedor usam esse valor único, mesmo critério de exibição já usado
   na grid de Pesquisa de Vendas (`MAX(pmd.id_funcionario)`).
3. **Operador de Caixa** não existe como campo direto em `venda`. Resolvido via
   `venda → caixa_detalhe (tipo_operacao='RECEBIMENTO_VENDA') → caixa_mestre.id_usuario`, com
   `LEFT JOIN LATERAL ... LIMIT 1` (evita multiplicar linhas do ledger quando há mais de um
   lançamento de caixa por venda — split-tender). Vendas antigas, de antes do PDV lançar
   `caixa_detalhe` (correção de 2026-07-30), aparecem com operador `—`.
4. **Top 10 (Marcas/Vendedores/Clientes)** ranqueados por **valor líquido vendido**, não por
   quantidade. Top 10 Marcas tem granularidade de **item** (`produto.marca`, `COALESCE` para
   "Sem marca") — diferente dos outros dois, que são por venda — porque uma única venda pode
   ter produtos de marcas diferentes.
5. **Gráfico "Recebimentos por Tipo de Carteira":** soma de `contas_receber.valor_receber` por
   `tipo_carteira`, das vendas **dentro do período filtrado** (por `data_venda`, não por data de
   recebimento) — mostra o mix de forma de pagamento escolhido na venda, mesmo que uma parcela
   de crediário ainda não tenha sido recebida. Mostra **todos** os tipos de carteira com algum
   recebível no período, sem limite de 10. **Agrupado por `(nome_carteira, categoria_carteira)`,
   não só nome** (ajuste de 2026-07-31, pedido do dono do produto) — a mesma bandeira pode existir
   uma vez por categoria (ex.: "HIPER" em Cartão Débito e em Cartão Crédito, saldos diferentes) e
   precisa aparecer como barras separadas. **Rótulo condicional pela categoria, calculado no
   front** (`rotulosPorCarteira()` em `web/src/lib/relatorioVendas.ts` — reaproveitada tanto pelo
   gráfico quanto pela captura do PDF; não é o `rotuloCarteira()` do Fechamento de Caixa, que é
   sempre "NOME — Categoria" por extenso):
   `CARTAO_DEBITO`/`CARTAO_CREDITO` sempre mostram "NOME Débito"/"NOME Crédito" (sem a palavra
   "Cartão", pra caber numa linha só) — mesmo quando só existe uma bandeira daquele nome no
   gráfico (ex.: "VISA Crédito", mesmo sem "VISA Débito" no período), porque é informação
   relevante por si, não só desambiguação de nome repetido; `AVISTA`/`CREDIARIO` mostram só o
   nome ("DINHEIRO", "CREDIARIO"), sem a categoria.
6. **Vendas canceladas excluídas de tudo** (`v.cancelada = false` direto no filtro base) —
   diferente da Pesquisa de Vendas, que mostra e sinaliza; aqui não faz sentido aparecer em
   KPI/gráfico/totalizador agregado.
7. **Devoluções sempre mostram zero.** A seção existe (KPI + composição do faturamento) e a
   query está correta contra o ledger (`produto_movimento_mestre.tipo_movimento = 'DEVOLUCAO'`),
   mas **nenhuma tela do sistema cria esse tipo de movimento ainda** (enum existe desde V013,
   nunca usado em `api/`). Fica pronta pra quando o módulo de devolução existir.
8. **Período máximo: 400 dias** (folga sobre 365 pra cobrir "Últimos 12 Meses" em ano bissexto),
   mesma mensagem de erro no estilo de Pesquisa de Vendas/Cancelamento de Venda.
9. **Presets de período resolvidos no front** (aritmética de data pura, mesmo precedente já
   usado em `PesquisaVendas.tsx` com `primeiroDiaDoMesISO()`) — o backend só recebe
   `dataInicial`/`dataFinal` prontos, igual a toda tela existente.
10. **Empresa: ADMIN em multi-select** (novo componente `EmpresaMultiSelect.tsx`, primeiro do
    projeto), nenhuma selecionada = "Todas as empresas". **OPERADOR fixo na empresa ativa da
    sessão** (claim `eid`), sem seletor — backend ignora `idsEmpresa` enviado por um OPERADOR
    (mesmo padrão de Pesquisa de Vendas/Fechamento de Caixa).
11. **KPIs "como se fossem botões"** — blocos grandes e visualmente distintos (`.relatorio-kpi-card`),
    não uma linha de tabela; nesta v1 são só informativos, sem navegação por trás do clique.

## Filtros de pesquisa

| Campo | Tipo | Obrigatório | Comportamento |
|---|---|---|---|
| Período de Vendas | Combo | Sim | Personalizado / Mês Atual (padrão) / Mês Anterior / Últimos 3/6/12 Meses — presets calculam e preenchem as datas (campos desabilitados); só "Personalizado" libera edição |
| Data inicial / final | Data (texto mascarado `dd/mm/aaaa`) | Sim | Só editável com preset "Personalizado" |
| Empresas | Multi-select (`EmpresaMultiSelect`) | Não | Só ADMIN. Nenhuma marcada = todas |
| Vendedor | Lookup (`PesquisaVendedorModal`, já existe) | Não | Lista `funcionario` ativos |
| Totalizar Por | Combo | Sim | Não Totalizar (padrão) / Data da Venda / Cliente / Vendedor / Operador de Caixa / Empresa |

### Validações

- Data inicial > data final → *"Data inicial não pode ser maior que a data final."*
- Período > 400 dias → *"Período de consulta não pode exceder 400 dias."*
- Sem data válida → seção de resultado não busca (front); 400 se forçado via API.

## Resumo do Período (KPIs)

| Card | Linha principal | Linha secundária | Cor do valor |
|---|---|---|---|
| Ticket Médio | Valor (`SUM(valorLíquido)/nVendas`) | Nº Vendas | `--accent` |
| % Médio de Desconto | `SUM(desconto)/SUM(bruto)*100` (ponderado) | Valor Desconto | `--danger` |
| Devoluções | `valorDevolução/SUM(bruto)*100` | Valor Devolução | `--danger` |
| Itens Vendidos / Média | `SUM(qtdItens)` | Média de itens por venda | `--accent` |

## Composição do Faturamento

Valor Bruto · Descontos · Acréscimos · Devoluções · **Venda Líquida** = `Bruto − Descontos +
Acréscimos − Devoluções`.

**Cores (2026-08-01, pedido explícito: "colocar cores nos valores... em harmonia com as cores do
projeto")** — só tokens já existentes no design system (nenhuma cor nova), pela semântica de
ganho/perda no faturamento: Descontos e Devoluções em `--danger` (reduzem o líquido), Acréscimos
em `--sucesso` (aumenta o líquido), Valor Bruto sem cor especial (base neutra), Venda Líquida em
`--accent` (já tinha destaque de fundo/borda, ganhou também o texto). Mesmo critério nos KPIs
acima. Ver `.relatorio-kpi-valor.cor-*` em `web/src/styles.css`.

## Gráficos (7)

1. **Valor Vendido por Dia** — linha, um ponto por dia do período (zero-preenchido, sem buracos).
2. **Top 10 Marcas** — barra horizontal, granularidade de item.
3. **Top 10 Vendedores** — barra horizontal.
4. **Top 10 Clientes** — barra horizontal (cliente nulo → "Consumidor Final").
5. **Recebimentos por Tipo de Carteira** — barra horizontal, todos os tipos com recebível no
   período, uma barra por `(nome_carteira, categoria_carteira)` — ver "Decisões de escopo" #5.
6. **Por Hora da Venda** — coluna, 24 buckets (00–23), zero-preenchido.
7. **Por Dia da Semana** — coluna, Segunda…Domingo, zero-preenchido.

Cada gráfico é uma série só, sem necessidade de legenda, com tooltip ao passar o mouse. **Cor
(revisado 2026-08-01):** os 7 cards alternam entre `--accent` e `--info` (Valor Vendido por Dia,
Top Vendedores, Recebimentos por Carteira e Por Dia da Semana em `--accent`; Top Marcas, Top
Clientes e Por Hora em `--info`) — só pra dar variedade visual entre os cards, não semântica; por
isso `--danger`/`--sucesso` (que carregam "perda"/"ganho" nos KPIs acima) ficam de fora daqui.

## PDF

Botão **"Gerar PDF"** no topo da tela (`topbar-acoes`, ao lado da Ajuda — só aparece depois que o
relatório carrega). Ao clicar, baixa o PDF direto (sem pré-visualização — a pré-visualização é a
própria tela, o usuário já está olhando pra ela).

**Pedido explícito do dono do produto: "gere como está na tela, com os gráficos"** — diferente do
padrão textual monoespaçado usado no Fechamento de Caixa/Comprovante de Crediário (que reconstrói
os dados como tabela ASCII). Aqui o PDF é uma **captura visual** do próprio conteúdo renderizado
(`html2canvas` rasteriza o bloco de KPIs/composição/gráficos/grid tal como está na tela — vira
uma imagem única; `jsPDF`, folha A4 **paisagem**, recorta
essa imagem em páginas deslocando o Y a cada nova página). Os 7 gráficos saem como desenho de
verdade (linha/barras), não como tabela de números — só assim faz sentido pedir "com os gráficos".
Formato JPEG a 92% (não PNG) — a imagem completa em `scale: 2` facilmente passava de 30-40MB em
PNG sem perdas; em JPEG cai pra menos de 1MB sem perda visível de nitidez em texto/gráficos.

**Armadilha séria encontrada:** `html2canvas` sempre clona o `<html>` inteiro pro contexto de
renderização (não só o elemento passado pra função) — qualquer CSS incompatível **em qualquer
lugar da página**, mesmo fora da área capturada, derruba a captura inteira. O projeto usava
`color-mix()` em várias classes (badges, menu ativo, telas de PDV); o Chrome devolve o valor
computado de `color-mix()` via `getComputedStyle` como `color(srgb ...)` (sintaxe CSS Color
Level 4), que o parser do `html2canvas` (mais antigo) não entende e lança exceção. **Duas camadas
de correção:**
1. `color-mix()` **eliminado do projeto inteiro** (não só desta tela) — trocado por
   `rgba(var(--accent-rgb), alfa)` usando trincas R,G,B novas (`--accent-rgb`,
   `--accent-ink-rgb`, `--danger-rgb`) definidas junto dos tokens de tema (`:root` claro/escuro).
2. `ignoreElements` no `html2canvas()` pra pular o menu lateral, o cabeçalho e qualquer modal
   aberto (`.app-header, .app-nav, .modal-overlay`) — nem tudo que existe na página precisa ser
   capturado, e reduz a chance de esbarrar em CSS problemático em telas futuras.

**Vale pra qualquer captura de tela futura no projeto** (não só PDF): não usar `color-mix()` em
nenhuma classe nova — usar as trincas RGB (`--accent-rgb`/`--accent-ink-rgb`/`--danger-rgb`) com
`rgba()` desde o início.

**Revisão (mesmo dia, em seguida):** 3 pedidos do dono do produto sobre a paginação.
1. **A grid de vendas sempre começa numa página nova**, mesmo que os gráficos terminem no meio de
   uma página — a captura deixou de ser um único elemento; agora são dois refs separados
   (`topoRef` = KPIs+composição+gráficos, `gridRef` = grid), capturados e paginados
   independentemente (`desenharElementoPaginado()` em `relatorioVendasCaptura.ts`), com
   `doc.addPage()` forçado entre os dois blocos.
2. **Linha de total no fim da grid** (`<tfoot>`, mesma convenção já usada nas grids de item de
   `DetalheVendaModal.tsx`) — soma de Qtd Produtos/Valor Venda/Acréscimos/Descontos/Valor Líquido
   na grid analítica, soma de Nº de Vendas/Valor da Venda na agrupada. Fica visível na tela
   também (não só no PDF), consistente com o resto do app.
3. **"Página X de Y" em toda página**, texto sobreposto por cima da imagem depois que todas as
   páginas já foram desenhadas (`doc.getNumberOfPages()` + `doc.setPage(i)` + `doc.text(...)`
   no canto inferior direito). **Superado pela revisão de 2026-08-01 abaixo** — saiu do rodapé,
   foi pro cabeçalho.

**Revisão 2026-08-01 — filtros aplicados + cabeçalho/rodapé em toda página, seguindo o modelo do
ERP legado (`RELATORIO_COMISSOES.PDF`, relatório de Comissões do sistema Mitryus).** Três pedidos
em sequência do dono do produto:

1. **Descrição dos filtros aplicados, antes de qualquer dado.** Vira uma seção "Filtros Aplicados"
   (Período, Empresa(s), Vendedor, Totalizar por) dentro de `topoRef`, capturada junto pelo mesmo
   `html2canvas` — sai no mesmo tamanho de fonte do resto da tela, sem precisar de uma página só de
   texto nativo (1ª tentativa, native `jsPDF` text numa página de rosto, **rejeitada**: "ficou
   muito grande numa folha", refeita nesse estilo compacto). **Só existe no PDF, nunca na tela** —
   a seção só entra no DOM enquanto `gerandoPdf === true` (`{gerandoPdf && (...)}` em
   `RelatorioVendas.tsx`), então o `handleGerarPdf` precisa esperar 2 `requestAnimationFrame`
   depois do `setGerandoPdf(true)` antes de chamar a captura — senão o React ainda não comitou o
   nó novo e o `html2canvas` roda contra o DOM antigo, sem a seção.
2. **Título + paginação + data/hora no cabeçalho de toda página** (não só a 1ª, e não mais no
   rodapé) — texto nativo `jsPDF`, desenhado por cima da imagem depois que todas as páginas já
   existem (mesma ideia de antes, só que virou cabeçalho: título em negrito à esquerda, "Página X
   de Y" + data/hora de geração à direita, régua fina abaixo).
3. **Rodapé em toda página:** empresa **logada na sessão** à esquerda (`eu.empresa.nome` — fixa,
   não muda com o filtro de empresa, que já aparece em "Filtros Aplicados" e pode ser "Todas as
   empresas" pro ADMIN) e `"Niner ERP"` fixo à direita.

**Como o cabeçalho/rodapé abrem espaço sem cortar conteúdo:** `desenharElementoPaginado()` reserva
`ALTURA_CABECALHO_MM` (16mm) e `ALTURA_RODAPE_MM` (10mm) subtraindo do cálculo de altura útil por
página (a paginação da imagem passa a fatiar em `alturaPagina − cabeçalho − rodapé`, não mais
`alturaPagina` inteira) e desloca a imagem pra baixo por `ALTURA_CABECALHO_MM` — depois de colar a
imagem, repinta as duas faixas com a cor de fundo do PDF (`COR_FUNDO_PDF` — constante fixa desde a
revisão de 2026-08-02 abaixo; antes disso era calculada de `getComputedStyle(...).getPropertyValue
('--ground')` na página real) pra tapar qualquer sobra da fatia anterior/seguinte que vaze pra
dentro delas, e só então desenha o texto por cima. `desenharCabecalhoERodape()` roda por último,
depois que todas as páginas (topo + grid) já existem, igual já fazia a numeração antiga.

**Backup de segurança:** antes dessa revisão, o dono do produto pediu explicitamente pra "memorizar
antes de fazer" — os 3 arquivos afetados (`RelatorioVendas.tsx`, `relatorioVendasCaptura.ts`,
`styles.css`) foram copiados pra um diretório de scratchpad da sessão antes de qualquer edição, pra
reverter sem depender de memória de conversa se o resultado não agradasse. Não sobrevive entre
sessões (é `/tmp` de sessão) — se precisar reverter numa sessão futura, usar `git diff`/histórico.

**Revisão 2026-08-02 — PDF sempre em tema claro, mesmo com o app em dark.** Pedido explícito do
dono do produto: a impressão em tema escuro gasta muito mais tinta. Como o app **não tem toggle de
tema** (o dark hoje só vem de `prefers-color-scheme: dark` do SO/navegador; `styles.css` já definia
`:root[data-theme='light']`/`[data-theme='dark']` sem nada nunca setar esse atributo), a 1ª versão
forçava `document.documentElement.setAttribute('data-theme', 'light')` na página **real** antes de
capturar — funcionava, mas causava um "flash" visível: o app inteiro (menu, cabeçalho, tudo)
piscava pra claro por 1-2s e voltava pro dark depois. **Corrigido no mesmo dia:** `html2canvas`
sempre clona o `<html>` inteiro num iframe fora de tela antes de rasterizar (é por isso que existe
o `ignoreElements`); a lib expõe `onclone?: (document, element) => void`, chamado só nesse clone,
antes de renderizar. `OPCOES_CAPTURA.onclone` (`relatorioVendasCaptura.ts`) seta
`data-theme='light'` **só no documento clonado** — a página visível nunca muda, zero flash, e o
clone (que é o que vira a imagem) sai claro do mesmo jeito, porque o CSS carregado no clone é o
mesmo (`:root[data-theme='light']` tem especificidade maior que a media query). Como o PDF passa a
ser claro por construção, `corFundo`/`corTexto` deixaram de ser calculados via `getComputedStyle` da
página real (que pode estar dark) e viraram constantes fixas (`COR_FUNDO_PDF`/`COR_TEXTO_PDF`,
mesmos valores de `:root[data-theme='light']`) — `gerarPdfCapturaRelatorioVendas()` perdeu esses 2
parâmetros, `handleGerarPdf()` não toca mais em `document.documentElement`.

**Mesmo dia — margem lateral de 3mm na imagem.** A imagem capturada (KPIs/gráficos/grid) encostava
nas bordas esquerda/direita da página (x=0 até a largura da página), enquanto o cabeçalho/rodapé em
texto nativo já tinha ~10mm. `desenharElementoPaginado()` ganhou `MARGEM_LATERAL_MM = 3`: a imagem é
desenhada com `larguraImagem = larguraPagina - 2*MARGEM_LATERAL_MM` a partir de `x=MARGEM_LATERAL_MM`
(era `x=0`, largura cheia) — os `doc.rect(...)` de preenchimento de fundo continuam full-width, então
essa faixa de 3mm de cada lado fica visível como margem sem precisar de lógica extra. Cabeçalho/
rodapé nativos não mudaram (pedido foi só sobre "o relatório que está sendo gerado", a imagem).

## Grid totalizada + drill-down

- **`totalizarPor = NAO_TOTALIZAR`:** a grid já mostra a lista analítica de vendas do período
  direto (Empresa | Nº Venda | Data/Hora | Cliente | Vendedor | Operador | Qtd Produtos | Valor
  Venda | Acréscimos | Descontos | Valor Líquido) — sem agrupamento, sem clique necessário, em
  **ordem cronológica** (`ORDER BY v.data_venda` na query-base, `RelatorioVendasService.
  buscarLinhasBase()` — antes não tinha `ORDER BY` nenhum e a ordem saía arbitrária do `GROUP BY`
  do Postgres; corrigido 2026-08-01, vale também pro drill-down por grupo, que usa a mesma lista).
- **Qualquer outro totalizador:** a grid mostra Nome do Totalizador | Nº de Vendas | Valor da
  Venda, uma linha por grupo, ordenada por valor decrescente. Clicar numa linha abre
  `DrilldownTotalizadorModal.tsx` (popup, mesmo padrão de `DetalheVendaModal.tsx`) com a lista
  analítica (mesmas 10 colunas acima) só das vendas daquele grupo — reaplica os mesmos filtros
  do topo + a chave do grupo, num segundo endpoint (`/detalhe`).
- Todo agrupamento/soma acontece **no backend** (P4 — nada de business logic no front); o front
  só renderiza o que a API já devolveu pronto.

## Regras de negócio

### Permissão de empresa por papel

Mesmo padrão de Pesquisa de Vendas/Fechamento de Caixa: ADMIN filtra livremente (aqui,
multi-select); OPERADOR sempre consulta só a empresa ativa da sessão, o backend ignora
`idsEmpresa` diferente enviado por um OPERADOR.

### Valor líquido do item/venda

`valor_item = qtd_produto * preco_venda − valor_desconto + valor_acrescimo` (mesma fórmula de
Pesquisa de Vendas/Cancelamento de Venda/Histórico do Cliente). Por venda: `valorBruto =
SUM(qtd*preco_venda)`, `valorLíquido = valorBruto − valorDesconto + valorAcrescimo`.

### Vendedor e Operador de Caixa são "1 por venda"

Ver "Decisões de escopo" #2 e #3. Ambos podem ser `null` (venda sem itens, ou sem lançamento de
caixa vinculado — vendas antigas ou dados manuais de teste).

## Contrato de API

```
GET /api/v1/relatorios/vendas?dataInicial=&dataFinal=&idsEmpresa=1&idsEmpresa=2&idFuncionario=&totalizarPor=
  → { kpis: { ticketMedioValor, ticketMedioNVendas, percentualMedioDesconto, valorDesconto,
              percentualDevolucao, valorDevolucao, itensVendidos, mediaItensPorVenda },
      composicaoFaturamento: { valorBruto, descontos, acrescimos, devolucoes, vendaLiquida },
      graficos: { porDia: [{rotulo,valor}], topMarcas: [...], topVendedores: [...],
                  topClientes: [...], porCarteira: [{nomeCarteira,categoriaCarteira,valor}],
                  porHora: [...], porDiaSemana: [...] },
      totalizador: { tipo: 'ANALITICO'|'AGRUPADO',
                     linhasAgrupadas?: [{chave,nome,nVendas,valorVenda}],
                     linhasAnaliticas?: [{idVenda,idEmpresa,nomeEmpresa,dataHoraVenda,nomeCliente,
                       nomeVendedor,nomeOperador,qtdProdutos,valorVenda,acrescimos,descontos,valorLiquido}] } }

GET /api/v1/relatorios/vendas/detalhe?<mesmos filtros>&totalizarPor=&chave=
  → { itens: [ mesma linha analítica acima ] }
```

`totalizarPor` ∈ `NAO_TOTALIZAR|DATA_VENDA|CLIENTE|VENDEDOR|OPERADOR_CAIXA|EMPRESA`. `/detalhe`
só é chamado quando `totalizador.tipo = 'AGRUPADO'` e responde 400 se `totalizarPor` for
`NAO_TOTALIZAR` (não há grupo pra detalhar). Erros em Problem Details: 400 (datas inválidas /
período > 400 dias). Ambos sob `/api/v1/**` (JWT de tenant, RLS ativo — P8), qualquer papel.

## Critérios de aceitação (viram testes)

- Dado um cenário conhecido de 1 venda com desconto manual no ledger, os KPIs e a composição do
  faturamento batem com a soma manual esperada.
- Dado vendas ativas e uma cancelada no período, a cancelada não entra em nenhum KPI, na
  composição, nem na grid (nem analítica, nem agrupada).
- Dado um OPERADOR informando `idsEmpresa` de outra empresa, a pesquisa usa a empresa da sessão
  dele mesmo assim.
- Dado `totalizarPor=NAO_TOTALIZAR`, o totalizador devolve a lista analítica direto.
- Dado `totalizarPor=VENDEDOR` com vendas de dois vendedores diferentes, o agrupamento soma
  certo por vendedor e o drill-down de um grupo devolve exatamente as vendas daquele vendedor.
- Dado `/detalhe` chamado com `totalizarPor=NAO_TOTALIZAR`, responde 400.
- Dado uma venda com itens de duas marcas diferentes, o Top 10 Marcas agrega por item (não
  atribui a venda inteira a uma marca só).
- Dado o período de um tenant, vendas de outro tenant não aparecem (RLS).
- Dado um período maior que 400 dias, responde 400.

Cobertos por `RelatorioVendasCrudTest` (8 testes).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `relatorios.vendas.tela`** — ver `web/src/components/AjudaDaTela.tsx`.
  `url_video`: `NULL` por ora.

## Impacto no banco

Nenhum — tela 100% de leitura sobre tabelas já existentes (`venda`, `produto_movimento_mestre/
detalhe`, `contas_receber`, `caixa_detalhe`, `caixa_mestre`, `usuario`, `cliente`, `funcionario`,
`empresa`, `tipo_carteira`, `produto`, `produto_barra`). Nenhuma migration nova.

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- **Exportação em Excel** — fora de escopo (PDF existe, ver seção "PDF" acima — decisão revista
  em relação a Pesquisa de Vendas, que não tem exportação nenhuma).
- **Navegação real por trás dos cards de KPI** — são só informativos nesta v1, apesar do visual
  de botão.
- **Dado real de devolução** — a seção existe e a query está correta, mas fica zerada até o
  módulo de devolução ser implementado em alguma tela futura.
- **Outros relatórios do grupo Relatórios** — esta feature só cobre Vendas; o padrão aqui
  definido (filtros → KPIs → composição → gráficos → grid totalizável) é o ponto de partida
  para as próximas telas do grupo, não uma entrega delas.

## Questões abertas

Nenhuma bloqueante — todas as decisões de modelagem foram fechadas com o dono do produto antes
da implementação (ver "Decisões de escopo").

## Métrica de sucesso

Um gerente consegue montar o filtro (período + empresa/vendedor), ler os 4 KPIs e a composição
do faturamento, e identificar visualmente o dia/hora/vendedor/marca de maior venda do período em
menos de 30 segundos, sem sair da tela.
