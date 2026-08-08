# Spec: Rotina de Exportação de Dados                  Status: Implementada
Autor: Claudio Calixto (dono do produto) + Claude · Data: 2026-08-06 · Módulo(s): `configuracao` (exportacao) · Fase: 1 — Núcleo do ERP

## Problema

O lojista às vezes precisa tirar os dados do Niner pra fora — uma auditoria contábil pedindo o
plano de contas, um backup próprio, conferência cruzada com outro sistema, ou simplesmente abrir
numa planilha pra analisar fora do ERP. Não existia nenhum jeito de baixar o que já está
cadastrado além de copiar linha por linha das telas de listagem.

## Solução proposta

Irmã "de saída" da [Rotina de Importação de Dados](importacao-dados.md): em vez de trazer dado de
um sistema anterior, exporta o que já está no Niner para planilha Excel (`.xlsx`). Vive no mesmo
grupo do menu, **Configurações**, `ADMIN`-only. Bem mais simples que a importação — é só leitura,
sem validação, sem escolha prévia, sem dependência entre tabelas: um clique numa tabela já baixa
o arquivo.

Cobre **9 tabelas**: Empresas, Clientes, Fornecedores, Funcionários, Contas a Receber/Recebidas,
Contas a Pagar/Pagas, Código de Barras, Plano de Contas e Estoque — deliberadamente mais ampla que
a importação (que cobre só 4), porque exportar não tem o mesmo risco de misturar dado ruim no
tenant; qualquer tabela de leitura pode entrar.

## User stories

- Como **ADMIN**, quero baixar meus clientes/fornecedores/produtos em Excel pra analisar ou
  compartilhar com o contador.
- Como **ADMIN**, quero exportar o plano de contas e o financeiro (contas a receber/pagar) pra
  conferência externa.
- Como **ADMIN**, quero exportar o estoque atual por empresa/variação pra um inventário físico
  ou conferência.

## Especificação por tabela

Cada tabela é **uma única consulta SQL**, sem parâmetro — RLS + filtro explícito de `id_tenant`
(mesmo padrão de defesa em profundidade do resto do domínio) restringem ao tenant atual. Os
próprios aliases de coluna do `SELECT` (`AS "Nome da Coluna"`) já saem em português e viram o
cabeçalho da planilha direto — o frontend não mapeia nome nenhum. Datas (`to_char(..., 'DD/MM/YYYY')`)
e booleanos (`CASE WHEN ... THEN 'SIM' ELSE 'NAO' END`) já saem formatados do SQL, prontos pra
exibir.

| # | Tabela (chave) | Título | Fonte |
|---|---|---|---|
| 1 | `empresa` | Empresas | `empresa` |
| 2 | `cliente` | Clientes | `cliente` + `cfg_categoria_cliente` |
| 3 | `fornecedor` | Fornecedores | `fornecedor` + `cfg_plano_contas` |
| 4 | `funcionario` | Funcionários | `funcionario` |
| 5 | `contas_receber` | Contas a Receber / Recebidas | `contas_receber` + `venda` + `cliente` + `empresa` + `tipo_carteira` |
| 6 | `contas_pagar` | Contas a Pagar / Pagas | `contas_pagar` + `fornecedor` + `empresa` + `cfg_plano_contas` |
| 7 | `codigo_barras` | Código de Barras | `produto_barra` + `produto` + `cfg_cor`/`cfg_tamanho` |
| 8 | `plano_contas` | Plano de Contas | `cfg_plano_contas` |
| 9 | `estoque` | Estoque | `produto_estoque` + `produto_barra` + `produto` + `empresa` + `cfg_cor`/`cfg_tamanho` |

Duas dessas fontes (`contas_pagar`, `produto_barra`) **não tinham nenhuma tela ou consulta
existente no sistema** até esta feature — as consultas de exportação foram a primeira vez que
esses dados viraram visíveis de alguma forma pro usuário.

## Fluxo da tela

1. Escolhe a tabela (grid de botões, mesma estética visual da etapa 1 da Importação —
   `.wizard-tabela-btn`).
2. Clique dispara: busca os dados (`GET /api/v1/exportacao/{tabela}`), gera a planilha no
   navegador e baixa (`{tabela}-AAAA-MM-DD.xlsx`).
3. Toast de sucesso com a contagem de linhas exportadas, ou erro (ex.: "Nenhum registro
   encontrado").

Sem etapas intermediárias — diferente da importação, não há nada a configurar antes de exportar.

## Critérios de aceitação

- Dado um `ADMIN` autenticado, quando exporta qualquer uma das 9 tabelas, então recebe um
  `.xlsx` com uma linha por registro do tenant, cabeçalho em português.
- Dado um `OPERADOR`, quando tenta acessar `/api/v1/exportacao/**`, então recebe `403`.
- Dado um tenant sem nenhum registro numa tabela, quando exporta, então a tela mostra "Nenhum
  registro encontrado" (nenhum arquivo vazio é baixado).
- Dado dois tenants diferentes, quando cada um exporta a mesma tabela, então cada um só vê os
  próprios registros (RLS, P8).

## Impacto no contrato de API

```
GET /api/v1/exportacao/tabelas       lista as 9 tabelas (chave + título)
GET /api/v1/exportacao/{tabela}      todos os registros do tenant, já formatados (colunas em
                                      português, datas dd/mm/aaaa, booleanos SIM/NAO)
```

Todos sob `/api/v1/**` (JWT de tenant, RLS ativo — P8), `ADMIN`-only. Erros em Problem Details.

**Implementação:** `api/src/main/java/com/vetor/niner/configuracao/exportacao/` —
`ExportacaoController`/`ExportacaoService` (9 constantes SQL + dispatcher `switch`). Usa
`JdbcTemplate.queryForList(sql)` (não `JdbcClient`, convenção do resto do domínio) especificamente
porque devolve `List<Map<String,Object>>` pronto — sem parâmetro nenhum pra bindar, não compensa
escrever 9 `RowMapper` manuais. Frontend: `web/src/pages/exportacao/ExportacaoDadosPage.tsx` +
`web/src/lib/exportacao.ts` — planilha gerada 100% no navegador com `write-excel-file`, mesmo
padrão já usado no CRM (`web/src/lib/crm.ts`), sem dependência nova.

## Ajuda da tela (manual de operação) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `configuracao.exportacao`**
  - Objetivo: baixar em planilha Excel tudo que já está cadastrado no Niner.
  - Passos: (1) escolha a tabela; (2) a planilha é gerada e baixada automaticamente.
  - Erros comuns: "Nenhum registro encontrado" → a tabela escolhida ainda não tem dado
    cadastrado neste tenant.
  - `url_video`: `NULL` por ora.

## Impacto no banco

Nenhum — só leitura, nenhuma tabela nova, nenhuma migration.

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- Filtros (período, status, empresa) — exporta sempre a tabela inteira. Se aparecer demanda real,
  os relatórios existentes (Relatório de Vendas, Contas a Receber, Estoque etc., grupo
  Relatórios) já cobrem exportação filtrada em PDF; esta rotina é o "dump completo", não um
  relatório.
- Exportar em outro formato (CSV, PDF) — só `.xlsx`.
- Testes automatizados — mesma situação da Importação: implementada e verificada por compilação/
  typecheck/smoke test manual, sem suíte JUnit própria ainda.

## Questões abertas

Nenhuma bloqueante.

## Métrica de sucesso

Exportar qualquer uma das 9 tabelas em menos de 5 segundos, sem precisar de nenhuma configuração
prévia.
