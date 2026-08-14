# Spec: Parâmetros do Sistema (cfg_geral)                Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-07-21 · Módulo(s): `configuracao.geral` · Fase: 1 — Núcleo do ERP

## Problema

A tabela `cfg_geral` (V023) guarda os parâmetros gerais do tenant — percentual máximo de
desconto em venda, uso de cor/grade em produto e as taxas de crediário (Fase
2, Q5) — e já é semeada com valores padrão no signup (`SignupService.assinar`), mas não tinha
endpoint nem tela. Sem esta tela, o lojista não tem como ajustar esses parâmetros.

## Solução proposta

Tela nova, deliberadamente **fora do padrão de `cadastros`**: `cfg_geral` é um singleton por
tenant (`id_tenant` é a própria PK, sem surrogate), sempre existe (criada no signup) e nunca é
criada/excluída pela UI — só lida e atualizada. Por isso a tela não tem listagem, paginação,
busca, modo somente-leitura nem `InfoRegistro` — é um formulário único de edição.

**Acesso: somente ADMIN** (leitura e escrita) — decisão de produto, diferente de todos os
cadastros anteriores (Cliente/Funcionário/Fornecedor/Plano de Contas, onde OPERADOR também
tem acesso total). Racional: os parâmetros afetam o tenant inteiro (taxas financeiras, regras
de venda), sensibilidade equivalente à de Configuração de Tela (`docs/telas/configuracao-tela.md`),
que já é ADMIN-only.

## Particularidades estruturais (diferenças em relação às telas de cadastro)

1. **Singleton por tenant, sem criação/exclusão pela UI** — só `GET`/`PUT`. O `PUT` num
   tenant sem linha (não deveria acontecer, já que o signup sempre insere uma) responde 404
   em vez de criar silenciosamente — mais honesto que fingir sucesso.
2. **Sem `InfoRegistro`** — a tabela não tem `criado_em` (só `atualizado_em`) nem um "código"
   de registro que faça sentido mostrar; em vez do bloco de 3 campos do componente padrão, a
   tela mostra só "Última atualização: `<data>`" abaixo do título.
3. **Todos os campos são `NOT NULL`** (V023) — não existe noção de "campo vazio nem
   obrigatório configurável" (nada de `cfg_tela_campo` aqui). A validação é de **faixa**
   (percentual entre 0–100, dias ≥ 0) e de **presença**: todo campo do request é `@NotNull` e o
   `idPlanoContasCompraMercadoria`, por ser texto, é **`@NotBlank`** — mandar `""` ou só espaços
   devolve 400 (`api/src/main/java/com/vetor/niner/configuracao/geral/ConfiguracaoGeralDtos.java:22-34`).
4. **Item de menu condicional ao papel** — diferente das telas de cadastro (sempre visíveis
   no menu, com sub-rotas ADMIN-only como "configuração de campos"), aqui a **tela inteira** é
   ADMIN-only, então o próprio item de menu ("Parâmetros do Sistema") só aparece para ADMIN
   (`Layout.tsx`, `NAV_ADMIN`) — evita mostrar um link que vai redirecionar o OPERADOR de
   volta ao Painel.
5. **Ícone próprio, deliberadamente diferente da engrenagem (⚙)** — `IconeEngrenagem` já
   significa "configurar campos desta tela" em cada cadastro; usar o mesmo ícone aqui
   confundiria as duas ideias. Ícone novo: `IconeParametros` (sliders/ajustes).

## Campos do formulário

Tabela `cfg_geral` (V023). Cinco seções, nesta ordem: **Vendas**, **Catálogo**, **Estoque**
(2026-07-29), **Compras** (2026-08-22, plano de contas padrão da compra de mercadoria) e
**Crediário** — esta última nasceu rotulada "(Fase 2)" com um aviso de que o módulo ainda não
existia; o crediário saiu do papel em 2026-07-29 (Recebimento de Crediário calcula multa/juros
automaticamente sobre parcelas vencidas usando esses quatro campos) e o rótulo/aviso da tela
foram corrigidos só em **2026-08-07**, quando a discrepância foi notada.

| Campo (banco) | Rótulo na tela | Componente | Regra |
|---|---|---|---|
| `percentual_desconto_venda` | Desconto máximo em venda (%) | percentual (máscara) | 0–100 |
| `cfg_usa_cor_grade` | Usa cor/grade (calçados, confecções) | checkbox | — (default `false`, 2026-08-08; era duas flags separadas de linha/coluna) |
| `cfg_permite_qtd_decimal` | Permite quantidade decimal para produtos | checkbox | — (default `true`) |
| `cfg_exige_numero_venda_devolucao` | Exigir número da venda na Devolução de Produtos | checkbox | — (default `false`, 2026-08-11) |
| `cfg_rateia_frete_entrada` | Ratear frete/IPI/ICMS-ST no custo do item | checkbox | — (default `false`, 2026-08-11) |
| `cfg_reajusta_preco_entrada` | Reajustar preço do produto automaticamente | checkbox | — (default `false`, 2026-08-11) |
| `cfg_consiste_valor_contas_pagar` | Consistir valor das contas a pagar na entrada | checkbox | — (**default `true`**, 2026-08-23) |
| `id_plano_contas_compra_mercadoria` | Plano de Contas da Compra | `SeletorPlanoContas` (`apenasAnaliticas`) | obrigatório |
| `juros_crediario_dias` | Juros após (dias) | inteiro | ≥ 0 |
| `juros_crediario` | Juros (%) | percentual (máscara) | 0–100 |
| `multa_crediario_dias` | Multa após (dias) | inteiro | ≥ 0 |
| `multa_crediario` | Multa (%) | percentual (máscara) | 0–100 |

Campos percentuais reaproveitam `mascararPercentual`/`desmascararPercentual`/`formatarPercentual`
(`web/src/lib/masks.ts`), a mesma máscara do "% Comissão" do Funcionário.

**`cfg_permite_qtd_decimal` (2026-07-29):** liga/desliga se a quantidade de produto (venda,
transferência, histórico do cliente) aceita até 3 casas decimais (`numeric(14,3)`, mesma
convenção de peso) ou é sempre inteira. Ligado por padrão — preserva o comportamento que já
existia antes deste parâmetro (quantidade sempre aceitava decimal em qualquer lugar). Lido por
qualquer papel via `GET /api/v1/config-geral/permite-qtd-decimal` (PDV, Transferência de
Produtos e Histórico do Cliente precisam do valor sem ser ADMIN) — mesmo padrão de
`/usa-cor-grade` e `/desconto-venda`. Validado também no servidor (`PdvVendaService`/
`TransferenciaService` rejeitam com 400 uma quantidade fracionária quando o parâmetro está
desligado), não é só uma máscara de UI. Detalhe completo de onde isso é aplicado:
`docs/telas/pdv.md` e `docs/telas/transferencia-estoque.md`.

**`cfg_exige_numero_venda_devolucao` (2026-08-11):** liga/desliga se o campo "Número da Venda"
é obrigatório na Devolução de Produtos. Desligado por padrão — preserva o comportamento
original (campo opcional, devolução sem vínculo com nenhuma venda). Ligado, a tela exige o
número antes de gravar **e** só permite devolver produtos que a venda de fato vendeu, até a
quantidade ainda não devolvida dela — o vínculo obrigatório é o que torna essa restrição
possível de aplicar (sem uma venda de referência não há o que validar). Lido por qualquer papel
via `GET /api/v1/config-geral/exige-numero-venda-devolucao` (mesmo padrão das outras flags
leves) e validado também no servidor (`DevolucaoProdutoService.efetivar` rejeita com 400 se a
venda não foi informada e a flag está ligada). Detalhe completo do que a restrição faz e como o
foco inicial do campo muda de acordo com esta flag: `docs/telas/devolucao-produtos.md`.

**`cfg_consiste_valor_contas_pagar` (2026-08-23):** liga/desliga a exigência de que a soma das
duplicatas lançadas na Entrada de Produtos por Compra seja **igual ao total dos produtos
lançados** — entrada de R$ 1.500,00 só confirma com duplicatas somando R$ 1.500,00. **Ligado por
padrão**, ao contrário das outras flags de Compras: essa consistência já existia fixa na tela
desde 2026-08-14, então o default preserva o comportamento de sempre (e o fallback do serviço,
quando a linha de `cfg_geral` não existe, também é `true` — diferente das demais flags leves,
cujo fallback é `false`). Desligada, a divergência é permitida — caso real de adiantamento,
parte da nota paga à vista ou nota parcialmente financiada. Base de comparação: o total dos
produtos **sem** o rateio de frete/IPI/ICMS-ST, que é o que a tela exibe em "Total dos produtos"
(comparar com o valor já rateado rejeitaria a nota por uma diferença que o operador não enxerga).
Lida por qualquer papel via `GET /api/v1/config-geral/consiste-valor-contas-pagar` (mesmo padrão
das outras flags leves) e **validada também no servidor** (`EntradaMercadoriaService.registrar`
rejeita com 400 antes de gravar qualquer coisa — a transação inteira é revertida, nem movimento
de estoque nem conta a pagar sobram). Detalhe do efeito na tela: `docs/telas/entrada-mercadoria.md`.

## Critérios de aceitação (viram testes)

- Dado um tenant recém-assinado, quando consulta `GET /api/v1/config-geral`, então recebe os
  valores padrão do banco (desconto 0%, cor/grade desligada).
- Dado um ADMIN, quando atualiza os parâmetros, então o `GET` seguinte reflete os novos
  valores e `atualizadoEm` muda.
- Dado um OPERADOR, quando tenta ler ou gravar, então 403 nos dois casos.
- Dado um percentual fora de 0–100, quando salvo, então 400.
- Dado um número de dias negativo, quando salvo, então 400.
- Dado dois tenants distintos, quando um atualiza seus parâmetros, então o outro não é
  afetado (isolamento).
- Dado `cfg_permite_qtd_decimal` atualizado, quando consultado por `GET /permite-qtd-decimal`
  (qualquer papel, inclusive OPERADOR), então reflete o valor salvo.
- Dado `cfg_exige_numero_venda_devolucao` atualizado, quando consultado por
  `GET /exige-numero-venda-devolucao` (qualquer papel), então reflete o valor salvo.
- Dado um tenant recém-assinado, quando consulta `GET /api/v1/config-geral`, então
  `cfgConsisteValorContasPagar` vem `true` (default do banco).
- Dado `cfg_consiste_valor_contas_pagar` atualizado, quando consultado por
  `GET /consiste-valor-contas-pagar` (qualquer papel), então reflete o valor salvo.

Cobertos por `ConfiguracaoGeralTest` (7 testes). Ver também `DevolucaoProdutoCrudTest` pro
critério "sem número da venda, quando a flag exige, então 400" e `EntradaMercadoriaCrudTest`
(`comConsistenciaLigadaRejeitaDuplicataComTotalDiferente` /
`comConsistenciaDesligadaAceitaDuplicataComTotalDiferente`, 2026-08-23) pro par de critérios da
consistência de contas a pagar — validação cruzada entre os módulos.

## Bug corrigido (2026-08-11) — cache do React Query desatualizado nas telas que leem as flags leves

Ao salvar, `ConfiguracaoGeralForm.tsx` só atualizava o cache da própria query (`['config-geral']`)
— as 4 flags leves (`usa-cor-grade`, `desconto-venda`, `permite-qtd-decimal`,
`exige-numero-venda-devolucao`), cada uma com sua própria query key, continuavam servindo o
valor antigo em cache pra qualquer tela que já tivesse sido visitada nesta sessão do navegador
(a navegação do sistema é toda client-side via React Router, sem recarregar a página, então o
cache do `QueryClient` sobrevive entre telas). Sintoma: mudar um parâmetro aqui e ir direto pra
outra tela (Produto, PDV, Devolução de Produtos) sem recarregar o navegador podia mostrar o
comportamento antigo até o cache expirar sozinho. Corrigido invalidando as 4 queries no
`onSuccess` do salvar — `queryClient.invalidateQueries({ queryKey: [...] })` pra cada uma.
Detalhe completo (incluindo o efeito colateral do lado do consumidor, que também precisou de
ajuste): [[feedback_react_query_cache_entre_telas]] (memória) e `docs/telas/devolucao-produtos.md`.

## Bug corrigido (2026-08-22) — plano de contas de compra não chegava pra usuário OPERADOR

O cadastro rápido de fornecedor embutido na Entrada de Produtos por Compra
(`FornecedorQuickCreateModal.tsx`) buscava `id_plano_contas_compra_mercadoria` chamando o
endpoint **completo** (`GET /api/v1/config-geral`, ADMIN-only) só pra ler esse único campo —
pra qualquer usuário `OPERADOR` (o caso mais comum de quem faz entrada de mercadoria), a
chamada voltava 403 e o campo ficava vazio, silenciosamente. Corrigido com o endpoint
`GET /api/v1/config-geral/plano-contas-compra-mercadoria` (sem checagem de papel, mesmo padrão
de `/usa-cor-grade`/`/permite-qtd-decimal` acima), reaproveitando um método de serviço que já
existia (`ConfiguracaoGeralService.idPlanoContasCompraMercadoria()`, usado internamente por
`EntradaMercadoriaService`) mas nunca tinha rota própria. Reproduzido e confirmado ao vivo com
usuário `OPERADOR` real: 403 no endpoint antigo, 200 no novo. Detalhe completo:
`docs/telas/entrada-mercadoria.md` e `docs/telas/plano-contas.md`.

## Impacto no contrato de API

```
GET  /api/v1/config-geral                    lê os parâmetros do tenant atual (ADMIN)
PUT  /api/v1/config-geral                    atualiza (ADMIN) — todos os campos são obrigatórios no corpo
GET  /api/v1/config-geral/usa-cor-grade      usa cor/grade (qualquer papel)
GET  /api/v1/config-geral/desconto-venda     percentual de desconto máximo (qualquer papel, PDV)
GET  /api/v1/config-geral/permite-qtd-decimal  quantidade decimal ligada/desligada (qualquer papel)
GET  /api/v1/config-geral/exige-numero-venda-devolucao  nº da venda obrigatório na devolução? (qualquer papel)
GET  /api/v1/config-geral/rateia-frete-entrada     rateia frete/IPI/ICMS-ST no custo? (qualquer papel, Entrada de Produtos)
GET  /api/v1/config-geral/reajusta-preco-entrada   reajusta preço na entrada? (qualquer papel, Entrada de Produtos)
GET  /api/v1/config-geral/consiste-valor-contas-pagar  duplicatas têm de somar o total dos produtos? (qualquer papel, Entrada de Produtos — 2026-08-23)
GET  /api/v1/config-geral/plano-contas-compra-mercadoria  plano de contas padrão de compra (qualquer papel — 2026-08-22, ver "Bug corrigido" acima)
```

Sob `/api/v1/**` (JWT de tenant, RLS ativo — P8); 403 (Problem Details) para papel diferente
de ADMIN, verificado a partir do claim `roles` do JWT (mesmo mecanismo de
`ConfiguracaoTelaService.exigirAdmin`).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `configuracao.geral.form`** — desconto máximo, exigência de venda na
  devolução, uso de cor/grade, quantidade decimal de produtos, juros/multa de crediário e as
  regras de Compras (rateio de frete, reajuste de preço, consistência do valor das contas a
  pagar, plano de contas da compra); erros comuns: só ADMIN acessa, percentuais entre 0–100.
  `url_video`: NULL.

## Impacto no banco

`cfg_permite_qtd_decimal boolean NOT NULL DEFAULT true` (coluna nova, 2026-07-29, dentro de
`V023__cfg_geral.sql` — banco em construção, editada em vez de nova migration).
`cfg_exige_numero_venda_devolucao boolean NOT NULL DEFAULT false` (coluna nova, 2026-08-11,
mesmo padrão — dentro de `V023__cfg_geral.sql`).
`cfg_consiste_valor_contas_pagar boolean NOT NULL DEFAULT true` (coluna nova, 2026-08-23, mesmo
padrão — dentro de `V023__cfg_geral.sql`; aplicada no banco de dev por `ALTER TABLE ... ADD
COLUMN IF NOT EXISTS` + `flyway repair`, sem recriar o banco, e sem `GRANT` novo porque os
privilégios de `cfg_geral` para `niner_app` são de tabela inteira, não por coluna).
`cfg_geral.id_plano_contas_compra_mercadoria` (seção **Compras**) é a única coluna que **não**
veio da V023: nasceu na `V032__plano_contas.sql` (linhas 21, 48 e 51 — coluna, FK e seed do
padrão), junto do plano de contas gerencial. O resto da tabela já existia por completo (V023, RLS
via V024), semeada no signup.

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- Qualquer lógica que *use* estes parâmetros (aplicar o desconto máximo numa venda, calcular
  juros/multa de um crediário) — isso pertence aos módulos de vendas e financeiro quando
  existirem; esta tela só armazena os valores.
- Configuração por filial/empresa — o parâmetro é por tenant (`empresa` é 1:1 com tenant em
  v1, spec §3.3).

## Questões abertas

Nenhuma bloqueante.

## Métrica de sucesso

Ajuste de um parâmetro em menos de 10 segundos.
