# CRM

**Status (2026-08-05):** primeira implementação real da área — até então só um placeholder "Em
construção" no menu (nascido em 2026-08-04, junto com as outras 8 áreas de Implementações
Futuras). Uma tela só: filtra clientes por perfil + histórico de compras e exporta a lista pra
planilha Excel. Sem grid de pré-visualização de propósito (ver "Decisões de escopo"). **Rodada 2
(mesmo dia):** +3 colunas de saída (Valor Total Comprado, Ticket Médio, Nº de Dias sem Comprar —
ver Item 3), 11 no total.

**Revisão de fluxo (2026-08-07, duas rodadas no mesmo dia — reverte a decisão "sem grid" acima):**
pedido explícito do dono do produto pra ver o resultado na própria tela antes de exportar. 1ª
rodada: "Localizar Clientes" carrega uma grid de resultado (cabeçalho fixo, ordenação por coluna,
total, scroll só nos dados). 2ª rodada, mesmo dia: os filtros/colunas saíram da tela e viraram um
**popup obrigatório que abre sozinho ao entrar na tela** (com `GaugeProgresso` — o mesmo anel de
progresso indeterminado da Rotina de Importação de Dados — enquanto a busca roda), e o botão
"Gerar Planilha Excel" subiu pra linha do título (mesmo lugar de ações primárias como "＋ Novo
cliente" noutras telas). Ver "Frontend" pra detalhe de cada rodada.

## Contexto

Ferramenta de segmentação de clientes pra campanha/relacionamento (a essência de um CRM básico):
o lojista escolhe critérios de cliente e/ou de produtos comprados, escolhe quais dados quer na
planilha, e baixa um `.xlsx` com uma linha por cliente encontrado. Pedido veio como uma lista
numerada e fechada (1. Filtros de Cliente, 2. Filtros de Produtos Comprados, 3. Dados do Cliente
Para Geração, 4. Formato da Geração) mais uma observação de UX: **a tela não pode ter scroll** —
use popup quando precisar.

## Decisões de escopo (não perguntadas — resolvidas por analogia com o resto do sistema)

- ~~**Sem grid de pré-visualização.** O pedido não menciona ver os clientes na tela antes de
  baixar, só filtrar → gerar. Como a lista pode ter qualquer tamanho, uma grid arriscaria voltar a
  precisar de scroll — o requisito mais explícito do pedido. "Gerar Planilha Excel" busca e baixa
  na mesma ação; o toast de sucesso mostra quantos clientes entraram.~~ **Revertido em
  2026-08-07** — pedido explícito de mostrar a grid antes de gerar. O requisito "sem scroll na
  tela" continua valendo, só que agora resolvido com `.grid-altura-fixa` (scroll contido dentro da
  grid, igual às telas de Relatório) em vez de não ter grid nenhuma. Ver "Frontend".
- **Escopo por empresa, silencioso.** `cliente` não é por empresa (compartilhado no tenant), mas
  `venda` é. Sem seletor de empresa na tela (não pedido) — aplicada a mesma regra silenciosa de
  todo relatório do sistema: OPERADOR só enxerga compras da própria empresa ativa (claim `eid`);
  ADMIN vê todas. Só afeta os filtros/colunas de PRODUTOS COMPRADOS (período, categoria, variação,
  primeira/última compra, nº de compras) — os filtros de CLIENTE (nome, gênero, idade etc.) nunca
  dependem de empresa.
- **"Compra" exclui venda cancelada, de propósito** — diferente de
  `ClienteHistoricoService.buscarCompras` (histórico do cliente, que mostra TUDO, cancelado
  incluso, por ser uma visão de auditoria). Aqui o objetivo é relevância pra campanha: uma venda
  cancelada não é uma compra de verdade pro cliente.
- **Primeira/Última Compra e Nº de Compras são sempre o HISTÓRICO COMPLETO** do cliente, nunca
  limitados pelo filtro "Período de Compras" (item 2.1) — esse filtro só decide QUEM entra na
  lista, não recorta os dados de saída. Ambíguo no pedido original; resolvido assim porque é a
  leitura mais simples e menos surpreendente (as 3 colunas descrevem o cliente, não a campanha).
- **CRM saiu de "Implementações Futuras" e entrou no grupo "Relatórios"** do menu — é uma
  ferramenta de consulta/exportação somente leitura, o mesmo perfil das outras 5 telas desse
  grupo. Mantém a rota `/crm` e o ícone (`IconeCliente`) que já existiam no placeholder.

## Item 1 — Filtros de Cliente (todos opcionais, combináveis com AND)

| # | Filtro | Fonte | Observação |
|---|---|---|---|
| 1.1/1.2 | Cliente Inicial / Final | `cliente.nome` | Compara só a **primeira letra** do nome (o exemplo do pedido é literalmente "A"/"Z") — evita a armadilha clássica de comparação lexicográfica de string onde `nome <= 'Z'` excluiria "Zeca" (que é `> 'Z'` como string). |
| 1.3 | Gênero | `cliente.genero` (ENUM) | Multi-seleção (`MultiSelectGenerico`). |
| 1.4 | Idade De/Até | `cliente.data_nascimento` | `EXTRACT(YEAR FROM age(...))`; cliente sem data cadastrada nunca aparece quando o filtro está ativo (comum em Pessoa Jurídica). |
| 1.5 | Aniversário De/Até | `cliente.data_nascimento` | Só mês/dia (`to_char(..., 'MM-DD')`), ignora o ano — suporta intervalo cruzando a virada do ano (ex.: 20/12 a 10/01). Campo de tela é `dd-mm` (dia primeiro, mesma convenção do resto do sistema), convertido pra `mm-dd` antes de comparar. |
| 1.6 | Categoria do Cliente | `cfg_categoria_cliente` | Multi-seleção; reaproveita `GET /api/v1/categorias-cliente` já existente. |
| 1.7 | Período de Cadastro | `cliente.criado_em` | Range fechado — os dois lados juntos ou nenhum. |
| 1.8 | Nº de Dias sem Compras | calculado | Um valor só (mínimo) — "há pelo menos N dias sem comprar". Cliente que **nunca** comprou sempre atende (dias sem comprar = infinito). |

## Item 2 — Filtros de Produtos Comprados (todos opcionais)

| # | Filtro | Fonte |
|---|---|---|
| 2.1 | Período de Compras | `venda.data_venda` |
| 2.2 | Categoria de Produtos Comprados | `cfg_categoria_produto` via `produto_categoria` |
| 2.3 | Cor | `cfg_cor` |
| 2.4 | Tamanho | `cfg_tamanho` |

**Regra importante:** quando qualquer um destes 4 está preenchido, o filtro vira um `EXISTS`
exigindo que **todos os ativos batam na MESMA linha de venda** (o mesmo item comprado), não em
compras diferentes — ex.: filtrar categoria "Calçados" + linha "AZUL" só traz quem comprou um
calçado azul num item só, não quem comprou uma bota (sem cor) numa compra e uma camisa azul
noutra. Coberto por teste (`filtroDeProdutosCompradosExigeMesmaLinhaDeVenda`,
`CrmCrudTest.java`).

"Compra" = `produto_movimento_detalhe` de uma venda não cancelada
(`tipo_movimento='VENDA'`, `credito_debito='D'`, `venda.cancelada=false`) — mesma definição de
`ClienteHistoricoService.buscarCompras`, exceto que aqui vendas canceladas são excluídas (ver
Decisões de escopo).

## Item 3 — Dados do Cliente Para Geração (colunas de saída)

Nome, Data de Nascimento, Gênero, E-mail, **Celular** (= `cliente.telefone`, renomeado só na UI
desde 2026-07-21), Primeira Compra, Última Compra, Nº de Compras, **Valor Total Comprado**,
**Ticket Médio**, **Nº de Dias sem Comprar** (as últimas 3, rodada 2 — pedido separado, mesma
sessão). Todas as 11 são **sempre** calculadas pelo backend (`ClienteCrmResponse` traz todas em
toda resposta) — os checkboxes da tela só decidem quais entram na planilha, sem precisar de uma
nova consulta.

- **Valor Total Comprado** = soma de `produto_movimento_detalhe.preco_total` das compras (mesma
  definição de "compra" do resto da tela — não cancelada); vem da mesma CTE `compras` já usada
  pras outras 3 colunas de agregado, sem `JOIN` extra.
- **Ticket Médio** = `valor_total / nº_de_compras`, com `NULLIF` no divisor (cliente sem compra
  nenhuma dá `NULL`, não erro/divisão por zero).
- **Nº de Dias sem Comprar** = `current_date - última_compra` (mesmo cálculo do filtro 1.8, mas
  aqui como coluna de saída, sempre presente mesmo que o filtro não esteja ativo); `NULL` para
  quem nunca comprou (não "infinito" como no filtro — a coluna mostra o dado cru).

## Item 4 — Formato da Geração

Só Planilha Excel (`.xlsx`) — única opção pedida, sem seletor de formato. Desde 2026-08-07 o botão
"Gerar Planilha Excel" não fica mais numa seção própria no corpo da tela — foi pra linha do título
(ver "Frontend"); mantido aqui só pela regra de negócio (formato único, exporta o que já foi
localizado). Gerada 100% no
navegador com a lib **`write-excel-file`**, não `xlsx`/SheetJS: a `xlsx` publicada no npm tem uma
vulnerabilidade alta (prototype pollution) **sem correção disponível** — o SheetJS moveu a
distribuição de versões corrigidas pro CDN próprio, fora do fluxo padrão `npm install`. Avaliada
também `exceljs` (só 1 vulnerabilidade moderada, transitiva, via `uuid`) antes de decidir por
`write-excel-file`, que não traz **nenhuma** dependência nova (`npm audit` limpo em relação ao que
já existia no projeto) — e o caso de uso é só escrever, nunca ler/parsear planilha alheia, o que já
elimina a superfície de ataque das duas outras.

## Backend

`com.vetor.niner.cadastros.crm` — `CrmController`/`Service`/`Dtos`, sem tabela nova (lê só de
`cliente`/`venda`/`produto_movimento_mestre`/`produto_movimento_detalhe`/`produto_barra`/
`produto_categoria`/`cfg_categoria_cliente`/`cfg_categoria_produto`/`cfg_cor`/`cfg_tamanho`,
todas já existentes). Dois endpoints, `/api/v1/**` (qualquer papel, só leitura):

- `GET /api/v1/crm/opcoes` — as 4 listas de dropdown (categorias de cliente/produto, cores,
  tamanhos) do tenant, pra popular os `MultiSelectGenerico` dos dois popups.
- `GET /api/v1/crm/clientes` — os **16** filtros como `@RequestParam`
  (`CrmController.java:39-54`: `clienteInicial`, `clienteFinal`, `generos`, `idadeDe`, `idadeAte`,
  `aniversarioDe`, `aniversarioAte`, `idsCategoriaCliente`, `cadastroDe`, `cadastroAte`,
  `diasSemComprasMinimo`, `comprasDe`, `comprasAte`, `idsCategoriaProduto`, `idsCor`,
  `idsTamanho`) — mesmo estilo de
  `RelatorioContasReceberController`: `List<Long>`/`List<String>` para multi-seleção, LocalDate
  pros períodos), devolve a lista de `ClienteCrmResponse`.

`CrmService.buscarClientes` monta a consulta em uma `WITH compras AS (...)` (agregando
primeira/última compra e nº de compras por cliente, uma vez só, reaproveitada tanto pro filtro
"dias sem compras" quanto pras colunas de saída) + `WHERE` dinâmico (`StringBuilder`/`List<Object>`
de params, mesmo padrão de `RelatorioEstoqueService`/`RelatorioContasReceberService`) + o `EXISTS`
de produtos comprados só quando algum dos 4 filtros daquele grupo está ativo. Validação: os pares
"De/Até" que são realmente um período (aniversário, cadastro, compras) exigem os dois lados juntos
ou nenhum; idade/nome/dias-sem-compras aceitam lado único.

**15 testes** (`CrmCrudTest.java`, era 14 antes da rodada 2) — opções, sem-filtro, cada filtro
isoladamente (gênero, letra inicial, idade, categoria), dias-sem-compras incluindo quem nunca
comprou, agregados de compra (incluindo valor total/ticket médio/dias sem comprar), venda
cancelada não conta, a regra "mesma linha de venda" dos filtros de produto, validação de
aniversário (formato e par obrigatório), aniversário cruzando virada de ano, e isolamento de
tenant (RLS).

**Bug real achado pelos próprios testes (rodada 2):** `rs.getObject(coluna, Long.class)` pra ler
`dias_sem_ultima_compra` (resultado de `current_date - data`, tipo `integer` no Postgres) fazia
toda a chamada falhar com **HTTP 409** e uma mensagem de exclusão ("Registro em uso... não pode
ser excluído" — pensada pro fluxo de `DELETE`, não `GET`), porque a coerção do driver JDBC do
Postgres pra `Long` via `getObject(String, Class)` não é confiável para `int4`. Corrigido com o
padrão já usado em `RelatorioContasReceberService.getLongOuNulo`: `rs.getLong(col)` +
`rs.wasNull()`.

## Frontend

`web/src/pages/crm/` — `CrmForm.tsx` (tela principal) + `FiltrosClientesModal.tsx` +
`FiltrosProdutosModal.tsx` (os 2 popups, ambos `.modal-overlay`/`.modal modal-largo`, estado
controlado pelo pai — sem "aplicar/cancelar" separado, já que nada acontece de verdade até
"Localizar Clientes", editar ao vivo é seguro). `web/src/lib/crm.ts` reúne tipos, chamadas de API,
`exportarClientesCrmExcel` (monta o schema de colunas do `write-excel-file` a partir da lista de
`ColunaSaidaCrm` marcada) e, desde 2026-08-07, `valorOrdenacaoCrm`/`formatarCelulaCrm` (valor bruto
pra ordenar vs. texto formatado pra célula da grid — mascarado/`R$`/`dd/mm/aaaa` conforme a
coluna).

Novo helper de máscara **`dd-mm`** (sem ano) em `web/src/lib/masks.ts`
(`mascararDiaMes`/`diaMesValido`) — só pro filtro de aniversário, que é um dia recorrente todo ano,
não uma data de um ano específico.

### Fluxo da tela (2026-08-07, duas rodadas)

**1ª rodada — grid de resultado.** "Localizar Clientes" (`useMutation` chamando
`buscarClientesCrm`) carrega a lista na própria tela antes de exportar. A grid usa o mesmo padrão
de cabeçalho fixo + ordenação por coluna do resto do sistema (`.th-ordenavel`, setas
`⇅`/`▲`/`▼`, `aria-sort`), mas **100% client-side**: como não há paginação (é sempre o resultado
inteiro do filtro, pensado pra exportação), ordenar não precisa de round-trip — só reordena
(`useMemo`) o array já carregado, usando o valor bruto (`valorOrdenacaoCrm`), nunca o texto já
formatado (evita ordenar `dd/mm/aaaa` como string). Scroll contido só na grid via
`.table-wrap.grid-altura-fixa` (mesmo mecanismo das telas de Relatório: `max-height: 60vh`,
cabeçalho `position: sticky; top: 0` — já é regra global do projeto — e `<tfoot>` com o total
também sticky no rodapé da grid) — o resto da tela não rola.

**2ª rodada — popup obrigatório + gauge.** Os filtros e o checklist de colunas saíram do corpo da
tela e viraram um único popup (`.modal-overlay`/`.modal modal-largo`, sem `onClick` de fechar no
overlay — só sai clicando em "Localizar Clientes", de propósito, já que a ação tem efeito real
de rede) que **abre sozinho ao montar a tela** (`useState(true)`). Dentro dele: os 2 botões que
abrem os popups de filtro (aninhados, mostram quantos filtros estão ativos) + o checklist de 11
checkboxes de colunas. Ao clicar em "Localizar Clientes": o popup fecha, `clientes` volta a
`null` e a busca dispara — enquanto `localizar.isPending`, a tela mostra só um
`<GaugeProgresso rotulo="Localizando clientes…" />` (componente já existente, criado
2026-08-06 pra Rotina de Importação de Dados — reaproveitado sem nenhuma mudança); ao terminar, a
grid aparece. Um botão novo, **"Alterar Filtros e Colunas"**, reabre o mesmo popup mantendo a
seleção atual (filtros e colunas não se perdem).

**Botão "Gerar Planilha Excel" na linha do título.** Não fica mais numa seção do corpo — foi pra
`topbar-acoes`, ao lado do ícone de ajuda (mesmo lugar de ações primárias como "＋ Novo cliente"
noutras telas de cadastro). Fica desabilitado até localizar clientes ao menos uma vez
(`!clientes || clientes.length === 0`) e continua exportando só o que já está carregado, sem
buscar de novo.

**Testado ao vivo (Chrome, `mcp__claude-in-chrome`):** popup abrindo sozinho ao entrar; "Localizar
Clientes" fechando o popup e carregando a grid (7195 clientes reais do tenant de teste); clique no
cabeçalho "Nome" alternando ASC/DESC de verdade (conferido nome do primeiro registro mudando de
"ADELIR..." pra "ZENAIDE..."); cabeçalho e rodapé (`Total: N clientes`) `position: sticky`
confirmados via DOM enquanto a grid é rolada por `javascript_tool`; "Gerar Planilha Excel"
desabilitado (`opacity: 0.6`) antes de localizar e habilitado depois; "Alterar Filtros e Colunas"
reabrindo o popup com a seleção anterior intacta. Geração de planilha em si (sem filtro nenhum, 3
clientes reais, arquivo `.xlsx` válido conferido byte a byte) testada na 1ª implementação
(2026-08-05) e não voltou a ser reconferida byte a byte nesta revisão de fluxo — só o gatilho
(habilitado/desabilitado) mudou de lugar, não a lógica de exportação em si.

## Pendências explícitas, fora do escopo desta tela

- **Envio de campanha de verdade** (e-mail/WhatsApp em massa) — a tela só filtra e exporta; o que
  o lojista faz com a planilha é responsabilidade dele.
- **Outros formatos de geração** (PDF, CSV) — só Excel foi pedido.
- **Seletor de empresa** — aplicado silenciosamente (ver Decisões de escopo), sem controle visível
  na tela; se um tenant multi-empresa precisar escolher explicitamente no futuro, é extensão, não
  correção.
