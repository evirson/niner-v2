# Spec: Relatório de Comissões                     Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-03 · Módulo(s): `vendas`/`relatorios` · Fase: 2

## Problema

Não existe hoje nenhuma forma de apurar quanto cada funcionário vendeu (e devolveu) num período,
nem quanto isso representaria de comissão — o `funcionario.perc_comissao` já existe no cadastro
(campo puramente informativo até aqui) mas nada no sistema o utiliza.

## Solução proposta

Tela `/relatorio-comissoes`, disponível a **qualquer papel**, somente consulta — segunda tela do
grupo **Relatórios**, mais simples que o Relatório de Vendas (que definiu o padrão de tela):
popup de filtros (período obrigatório + empresas) → grid banda clássica (uma linha por
funcionário, ou por empresa+funcionário quando há mais de uma empresa no resultado) → subtotal
por empresa + total geral. Sem KPIs nem gráficos.

## Decisões de escopo

1. **Fonte dos valores é o ledger, não um lançamento de comissão** — não existe (e não é criado
   por esta feature) nenhum registro de "comissão paga/apurada". `valorVenda` vem do débito
   (`credito_debito = 'D'`) de `produto_movimento_detalhe` dos movimentos `tipo_movimento =
   'VENDA'`; `valorDevolucao` vem do crédito (`'C'`) dos movimentos `tipo_movimento =
   'DEVOLUCAO'` (existe desde a Devolução de Produtos, ver `docs/telas/devolucao-produtos.md`).
   `valorLiquido = valorVenda − valorDevolucao`; `valorComissao = valorLiquido × percComissao /
   100` — tudo calculado na consulta, nada persistido.
2. **Funcionário de uma devolução é o vendedor da venda original**, resolvido pela Devolução de
   Produtos (`id_funcionario` gravado no movimento de devolução a partir do nº de venda
   informado, opcional, na tela de devolução) — não o operador que processou a devolução em si.
3. **Um funcionário só aparece se teve venda OU devolução no período** naquela empresa — a
   consulta combina as duas fontes com `FULL OUTER JOIN` (cobre o caso raro de um funcionário
   que só apareceu numa devolução, sem nenhuma venda no mesmo período).
4. **Empresa: ADMIN em multi-select** (nenhuma marcada = todas), **OPERADOR fixo na empresa ativa
   da sessão** — mesmo padrão de todos os relatórios anteriores; backend ignora `idsEmpresa`
   enviado por um OPERADOR.
5. **Vendas canceladas excluídas** (`v.cancelada = false`), mesmo critério do Relatório de
   Vendas.
6. **Ordenação:** empresa é sempre o critério primário (mantém o agrupamento do subtotal válido);
   a coluna escolhida pelo usuário entra como critério secundário dentro de cada empresa — com
   uma única empresa no resultado (filtro de 1 empresa, ou OPERADOR), o primário vira um no-op e
   a coluna escolhida manda de fato. Sem coluna escolhida, cai no default (nome do funcionário).
7. **Período máximo: 400 dias**, mesma mensagem de erro do padrão já estabelecido.

## Filtros de pesquisa

| Campo | Tipo | Obrigatório | Comportamento |
|---|---|---|---|
| Período | Data inicial/final (texto mascarado `dd/mm/aaaa`) | Sim | Sem presets — sempre digitado |
| Empresas | Multi-select (`EmpresaMultiSelect`) | Não | Só ADMIN. Nenhuma marcada = todas |

### Validações

- Sem data inicial ou final → *"Informe a data inicial e a data final."*
- Data inicial > data final → *"Data inicial não pode ser maior que a data final."*
- Período > 400 dias → *"Período de consulta não pode exceder 400 dias."*

## Grid

Colunas: Empresa | Funcionário | Valor da Venda | Valor da Devolução | Valor Líquido | % Comissão
| Valor Comissão — todas ordenáveis por clique no título (client-side não, client já recebe do
backend na ordem certa e reordena via nova chamada). Com mais de uma empresa no resultado, uma
linha de subtotal aparece logo depois das linhas de cada empresa; total geral no rodapé sempre.
Cabeçalho/rodapé fixos, só a grid rola (`.relatorio-corpo-fixo`, mesmo padrão de Contas a
Receber).

## PDF

Mesmo mecanismo de captura visual (`html2canvas` + `jsPDF`) e mesmo modelo de cabeçalho/rodapé em
toda página (título + paginação + data/hora no cabeçalho; empresa da sessão + "Niner ERP" no
rodapé) do Relatório de Vendas/Contas a Receber — ver a seção "PDF" de
`docs/telas/relatorio-vendas.md` para o raciocínio completo (captura, tema claro forçado só no
clone, margem lateral 3mm). "Filtros Aplicados" (Período, Empresa(s)) aparece só na captura, não
na tela ao vivo.

## Contrato de API

```
GET /api/v1/relatorios/comissoes?dataInicial=&dataFinal=&idsEmpresa=1&idsEmpresa=2&ordenarPor=&direcao=
  → { linhas: [{idEmpresa,nomeEmpresa,idFuncionario,nomeFuncionario,
                valorVenda,valorDevolucao,valorLiquido,percComissao,valorComissao}],
      subtotaisPorEmpresa: [{idEmpresa,nomeEmpresa,valorVenda,valorDevolucao,valorLiquido,valorComissao}],
      totalGeral: {valorVenda,valorDevolucao,valorLiquido,valorComissao} }
```

Sob `/api/v1/**` (JWT de tenant, RLS ativo — P8), qualquer papel. Erros em Problem Details: 400
(datas ausentes/inválidas, período > 400 dias).

## Critérios de aceitação (viram testes)

- Dado uma venda sem devolução, a comissão é calculada sobre o valor líquido da venda.
- Dado uma devolução com vendedor identificado (via nº de venda na Devolução de Produtos), o
  valor líquido e a comissão do funcionário caem proporcionalmente.
- Dado uma venda cancelada no período, ela não entra no relatório.
- Dado nenhuma data informada, responde 400.
- Dado o período de um tenant, dados de outro tenant não aparecem (RLS).
- Dado ordenação por valor da venda, ASC e DESC respeitam a direção pedida.

Cobertos por `RelatorioComissoesCrudTest` (6 testes).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `relatorios.comissoes.tela`** — ver `web/src/components/AjudaDaTela.tsx`.
  `url_video`: `NULL` por ora.

## Impacto no banco

Nenhum — tela 100% de leitura sobre tabelas já existentes (`venda`, `produto_movimento_mestre/
detalhe`, `funcionario`, `empresa`). Nenhuma migration nova.

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- **Apurar/pagar comissão de verdade** — o relatório só calcula em cima do ledger existente;
  nenhum lançamento financeiro é criado.
- **Comissão por item/produto** — o percentual é único por funcionário (cadastro), não varia por
  produto vendido.

## Questões abertas

Nenhuma bloqueante.
