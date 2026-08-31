# Relatório de Ordens de Serviço

**Rota:** `/relatorio-ordens-servico` · **Chave da tela:** `relatorio-ordens-servico`
**Menu:** Relatórios › Faturamento (só aparece com o **módulo de serviços ligado**)
**Papel:** qualquer um — somente leitura.

Fecha a lacuna registrada em `docs/PENDENCIAS.md` #56: *"não há relatório de OS — produtividade por
mecânico, tempo médio de execução, OS abertas por período"*.

---

## 1. A pergunta que a tela responde

Duas, e elas **não usam o mesmo eixo de data** — é a decisão central desta tela:

1. **"O que entrou e o que saiu no período?"** → o bloco de **Movimento**. Cada contador conta pela
   sua própria data: abertas por `data_abertura`, concluídas por `data_conclusao`, faturadas por
   `data_faturamento`, canceladas por `data_cancelamento`.
2. **"Quem executou o quê?"** → a grade de **Produtividade por executor**, que conta pelo trabalho
   **entregue** (`data_conclusao` dentro do período).

⚠️ **Por que não um eixo só.** Uma OS aberta em julho e concluída em agosto é *movimento de julho*
na entrada e *produtividade de agosto* na execução. Forçar um eixo único faria o relatório mentir
sobre uma das duas perguntas, e a mentira seria invisível — os dois números são plausíveis
isolados. Mesmo raciocínio do aviso da Exportação de XML, que somava duas populações com conselhos
opostos.

## 2. O executor é do ITEM, o atendente é do cabeçalho

`ordem_servico.id_funcionario` = **quem atendeu** (abriu a OS, o consultor do balcão).
`ordem_servico_item.id_funcionario` = **quem executou** aquele item (o mecânico, o tosador).

A produtividade agrupa pelo **executor**. Usar o do cabeçalho creditaria todo o trabalho da oficina
ao consultor — é o mesmo defeito que a V088/V089 corrigiu no ledger de venda
(`feedback_coluna_que_muda_de_significado`).

## 3. Itens sem executor aparecem, não somem

`ordem_servico_item.id_funcionario` é **nullable** de propósito (peça normalmente fica sem
executor). Esses itens entram numa linha própria, **"(SEM EXECUTOR)"**, em vez de serem filtrados.

⚠️ Filtrar seria o defeito clássico: o total por executor não fecharia com o total geral, e nada na
tela diria por quê. Uma linha visível é a única forma de o número continuar batendo.

## 4. OS cancelada não conta produtividade — mas conta no movimento

Uma OS concluída e depois cancelada teve o trabalho feito e desfeito. Ela **sai** da produtividade
(o fato foi revertido) e **aparece** no contador de canceladas do Movimento, para o número não
desaparecer em silêncio.

## 5. Valores

- **Serviços** e **Peças** saem separados, por `produto.tipo_item` (`SERVICO` × `MERCADORIA`) —
  produtividade de mecânico é mão de obra; peça é giro de estoque.
- Valor do item = `qtd_produto × preco_venda`, o **preço congelado** na inclusão (DS16).
- ⛔ **O desconto do cabeçalho (`ordem_servico.valor_desconto`) NÃO é rateado por executor.** Ele é
  concessão comercial de quem fechou, não do mecânico; ratear tiraria produção de quem executou por
  causa de um desconto que ele não deu. Os valores por executor são **brutos** e a tela diz isso; o
  desconto aparece no bloco de Movimento, onde ele pertence.
- ⛔ **Comissão não aparece aqui.** Quem calcula comissão é o Relatório de Comissões, e ele é o
  caminho que **paga** — dois cálculos do mesmo conceito divergem no dia em que só um for corrigido
  (`feedback_relatorio_segue_quem_paga`).

## 6. Tempo médio de execução

`data_conclusao − data_abertura`, em horas, média das OS **concluídas no período** (canceladas
fora). Aparece no Movimento (geral) e por executor.

⚠️ É tempo de **calendário**, não de bancada: inclui a espera pela aprovação do cliente e a peça que
não chegou. Está escrito na tela, porque a média de 72h de uma oficina não significa 72 horas de
trabalho e ninguém deve ler assim.

## 7. Contrato de API

```
GET /api/v1/relatorios/ordens-servico
    ?dataInicial=2026-08-01&dataFinal=2026-08-31
    &idsEmpresa=1,2          (opcional; ADMIN só)
    &ordenarPor=valorTotal&direcao=desc
```

```jsonc
{
  "movimento": {
    "qtdAbertas": 40, "qtdConcluidas": 33, "qtdFaturadas": 30, "qtdCanceladas": 2,
    "valorFaturado": "18500.00", "valorDesconto": "420.00", "ticketMedio": "616.66",
    "tempoMedioHoras": "51.3"
  },
  "linhas": [{
    "idEmpresa": 1, "nomeEmpresa": "MATRIZ",
    "idFuncionario": 7, "nomeFuncionario": "MARIA",
    "qtdOrdens": 12, "valorServicos": "4200.00", "valorPecas": "1310.00",
    "valorTotal": "5510.00", "tempoMedioHoras": "44.0"
  }],
  "subtotaisPorEmpresa": [ … ],
  "totalGeral": { "qtdOrdens": 33, "valorServicos": "…", "valorPecas": "…", "valorTotal": "…" }
}
```

⚠️ `totalGeral.qtdOrdens` é **OS distintas**, não a soma de `qtdOrdens` das linhas: uma OS com dois
executores conta 1 no total e 1 para cada um deles. A tela diz isso no rodapé — sem o aviso, a
coluna parece não fechar.

## 8. Fuso

Toda comparação e todo agrupamento por data vão em `(coluna AT TIME ZONE 'America/Sao_Paulo')::date`
nos dois lados, como manda `feedback_data_fuso_loja_nao_do_banco`; `ComparacaoDeDataNoFusoCertoTest`
reprova o build se escapar.

## 9. PDF

Captura visual (html2canvas + jsPDF), padrão de `relatorio-vendas.md`: `temaClaroParaCaptura` +
`aguardarPintura` compartilhados, `[data-sem-impressao]` no aviso de atualização.
