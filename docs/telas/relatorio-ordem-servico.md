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

Captura visual (html2canvas + jsPDF), padrão de `relatorio-vendas.md`: `paletaDeImpressaoParaCaptura` (renomeado em 2026-09-04) +
`aguardarPintura` compartilhados, `[data-sem-impressao]` no aviso de atualização.

---

## 10. ⚠️ Duas armadilhas que só apareceram ABRINDO A TELA (2026-08-31)

Os 5 testes iniciais passavam, o `tsc -b` passava, as classes CSS foram conferidas contra o
`styles.css` — e a tela tinha **dois** defeitos, um deles impedindo o relatório de existir.

### 10.1 Alias do SELECT não vale dentro de expressão no `ORDER BY`

`valorTotal` era `(valor_servicos + valor_pecas)`, dois **aliases** do SELECT dentro de uma
expressão. O Postgres aceita alias no `ORDER BY` **sozinho**, não dentro de expressão:

```
ERROR: column "valor_servicos" does not exist
```

E a tela **abre ordenando por `valorTotal`**, então batia sempre — *"Ocorreu um erro."*, nenhuma vez
gerando. Hoje é `SUM(i.qtd_produto * i.preco_venda)` (serviços + peças é o total de todos os itens),
e é essa a razão de o Relatório de Comissões repetir a expressão inteira em vez do alias.

⚠️ **Por que a suíte não pegou:** os 5 testes chamavam **sem** `ordenarPor`, caindo no default.
**Allowlist de ordenação é uma lista de SQL que ninguém executou até alguém executá-la** — o teste
`todasAsColunasOrdenaveisGeramSqlValido` percorre todas as chaves, nas duas direções.

### 10.2 "Não há o que medir" não é zero

O tempo médio saía `—` em todas as linhas, e o dado existia: as OS reais levaram de **0,0047 h a
0,0594 h**. Dois erros somados — `COALESCE(AVG(…), 0)` transformava *ausência* em *zero*, e
arredondar para **uma casa** matava toda duração abaixo de 3 minutos.

Hoje o campo é **nulo** quando não há medida (só o nulo vira `—`), com **4 casas**, e **a tela**
escolhe a unidade: minutos, horas ou dias. Quem escolhe a unidade é a apresentação, e ela não pode
recuperar o que o arredondamento já jogou fora. O teste tem o **par**: nulo quando não há × maior
que zero para uma OS de 1 minuto — a duração exata que o arredondamento antigo matava.

---

**Revisão 2026-09-04 — PDF preto no branco e cabeçalho de coluna repetido.**
O mecanismo é comum aos 11 relatórios e está descrito em `docs/telas/relatorio-vendas.md`
(arquivo-padrão de tela de relatório): a captura deixou de reproduzir o **tema claro do
produto** e passou a declarar uma **paleta de impressão própria** (fundo `#ffffff`, texto
`#000000`, cabeçalho de tabela `#f2f2f2`), mantendo coloridas só as cores de série, que são
informação do gráfico. O módulo virou `lib/paletaDeImpressaoParaCaptura.ts`.

**Nesta tela:** o cabeçalho das colunas **se repete** no topo de todas as páginas — a tela tem
uma tabela só, e ela é o corpo do relatório. ⚠️ **Não teve PDF gerado na entrega de 09-04**:
a mudança foi aplicada e verificada por script e por `tsc`, mas só Estoque e Lucratividade
foram exercitados de ponta a ponta.
