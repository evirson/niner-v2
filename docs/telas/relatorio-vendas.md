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

| Card | Linha principal | Linha secundária |
|---|---|---|
| Ticket Médio | Valor (`SUM(valorLíquido)/nVendas`) | Nº Vendas |
| % Médio de Desconto | `SUM(desconto)/SUM(bruto)*100` (ponderado) | Valor Desconto |
| Devoluções | `valorDevolução/SUM(bruto)*100` | Valor Devolução |
| Itens Vendidos / Média | `SUM(qtdItens)` | Média de itens por venda |

## Composição do Faturamento

Valor Bruto · Descontos · Acréscimos · Devoluções · **Venda Líquida** = `Bruto − Descontos +
Acréscimos − Devoluções`.

## Gráficos (7)

1. **Valor Vendido por Dia** — linha, um ponto por dia do período (zero-preenchido, sem buracos).
2. **Top 10 Marcas** — barra horizontal, granularidade de item.
3. **Top 10 Vendedores** — barra horizontal.
4. **Top 10 Clientes** — barra horizontal (cliente nulo → "Consumidor Final").
5. **Recebimentos por Tipo de Carteira** — barra horizontal, todos os tipos com recebível no
   período, uma barra por `(nome_carteira, categoria_carteira)` — ver "Decisões de escopo" #5.
6. **Por Hora da Venda** — coluna, 24 buckets (00–23), zero-preenchido.
7. **Por Dia da Semana** — coluna, Segunda…Domingo, zero-preenchido.

Todo gráfico usa uma única cor (o accent do design system — cada gráfico é uma série só, sem
necessidade de legenda) e tooltip ao passar o mouse.

## PDF

Botão **"Gerar PDF"** no topo da tela (`topbar-acoes`, ao lado da Ajuda — só aparece depois que o
relatório carrega). Ao clicar, baixa o PDF direto (sem pré-visualização — a pré-visualização é a
própria tela, o usuário já está olhando pra ela).

**Pedido explícito do dono do produto: "gere como está na tela, com os gráficos"** — diferente do
padrão textual monoespaçado usado no Fechamento de Caixa/Comprovante de Crediário (que reconstrói
os dados como tabela ASCII). Aqui o PDF é uma **captura visual** do próprio conteúdo renderizado
(`html2canvas` rasteriza o bloco de KPIs/composição/gráficos/grid tal como está na tela — mesmo
tema claro/escuro, mesmas cores — vira uma imagem única; `jsPDF`, folha A4 **paisagem**, recorta
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
   no canto inferior direito).

## Grid totalizada + drill-down

- **`totalizarPor = NAO_TOTALIZAR`:** a grid já mostra a lista analítica de vendas do período
  direto (Empresa | Nº Venda | Data/Hora | Cliente | Vendedor | Operador | Qtd Produtos | Valor
  Venda | Acréscimos | Descontos | Valor Líquido) — sem agrupamento, sem clique necessário.
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
