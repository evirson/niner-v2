# Spec: Relatório de Contas a Receber / Recebidas   Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-03 · Módulo(s): `vendas`/`relatorios` · Fase: 2

## Problema

Não existe hoje uma visão consolidada das parcelas de cartão/crediário — a Pesquisa de Vendas
mostra a venda, o Fechamento de Caixa mostra o dia, mas nenhuma tela cruza as parcelas por
período de venda, vencimento ou recebimento com o valor líquido já descontada a taxa
administrativa.

## Solução proposta

Tela `/relatorio-contas-receber`, disponível a **qualquer papel**, somente consulta — terceira
tela do grupo **Relatórios**, mesmo padrão de tela do Relatório de Comissões (popup de filtros →
grid banda clássica com subtotal por empresa + total geral, sem KPIs/gráficos), mas com três
períodos independentes (venda/vencimento/recebimento, pelo menos um obrigatório) em vez de um só,
mais filtros de status e forma de pagamento.

## Decisões de escopo

1. **Só três categorias de carteira aparecem:** `CARTAO_DEBITO`, `CARTAO_CREDITO` e
   `CREDIARIO` — À Vista e Vale-Mercadoria nascem sempre quitados na hora da venda, não fazem
   sentido num relatório de "a receber/recebidas".
2. **Três períodos independentes, todos opcionais, mas pelo menos um obrigatório** (venda,
   vencimento, recebimento) — dá pra combinar mais de um ao mesmo tempo (todos aplicados com
   `AND`). Cada um exige início **e** fim quando preenchido.
3. **Valor líquido sempre com a taxa ATUAL do cadastro de Tipo de Carteira**, não a que vigorava
   na data da venda — `valorLiquido = valorBruto − valorBruto × taxaAdministradora/100`.
   Crediário nunca tem taxa (sempre 0%, `COALESCE(tc.taxa_administradora, 0)`).
4. **"01/06" (parcela/total) é sempre o total VERDADEIRO da linha de pagamento** (mesma venda +
   mesmo `id_carteira`), calculado por subconsulta correlacionada — não uma window function —
   porque precisa ignorar os filtros de período/status do WHERE externo: uma parcela "3/3" segue
   "3/3" mesmo filtrando só quem já foi recebido.
5. **Status da parcela:** Todos (padrão) / Em Aberto (`data_recebimento IS NULL`) / Recebidas
   (`data_recebimento IS NOT NULL`) — combina livremente com o filtro de forma de pagamento.
6. **Vendas canceladas excluídas** (`v.cancelada = false`).
7. **Empresa: ADMIN em multi-select** (nenhuma marcada = todas), **OPERADOR fixo na empresa ativa
   da sessão** — mesmo padrão de todos os relatórios anteriores.
8. **Ordenação:** mesmo truque de empresa-sempre-primária dos demais relatórios com subtotal —
   coluna escolhida pelo usuário é secundária dentro de cada empresa; sem escolha, cai no default
   histórico (vencimento).
9. **Período máximo: 400 dias** por período informado.

## Filtros de pesquisa

| Campo | Tipo | Obrigatório | Comportamento |
|---|---|---|---|
| Período de Venda | Data inicial/final | Não* | *Pelo menos um dos três períodos precisa vir completo |
| Período de Vencimento | Data inicial/final | Não* | Idem |
| Período de Recebimento | Data inicial/final | Não* | Idem |
| Empresas | Multi-select (`EmpresaMultiSelect`) | Não | Só ADMIN. Nenhuma marcada = todas |
| Status da Parcela | Combo | Não | Todos (padrão) / Em Aberto / Recebidas |
| Forma de Pagamento | Combo | Não | Todos (padrão) / Crediário / Cartão Débito / Cartão Crédito |

### Validações

- Um período preenchido só com início ou só com fim → *"Informe início e fim do período de
  [venda/vencimento/recebimento]."*
- Início > fim de qualquer período → *"Data inicial de [período] não pode ser maior que a
  final."*
- Nenhum dos três períodos preenchido → *"Informe ao menos um período (venda, vencimento ou
  recebimento)."*
- Qualquer período > 400 dias → *"Período de [período] não pode exceder 400 dias."*

## Grid

Colunas: Empresa | Nº Venda | Cliente | Forma de Pagamento (nome — categoria) | Parcela ("01/06")
| Data Venda | Vencimento | Recebimento (vazio = em aberto) | Empresa Pagamento | Valor Bruto |
Taxa Adm. | Valor Líquido — todas ordenáveis. Subtotal por empresa (valor bruto/líquido) quando
há mais de uma no resultado; total geral sempre no rodapé. Cabeçalho/rodapé fixos
(`.relatorio-corpo-fixo`), uma linha por parcela (não agregada por venda).

## PDF

Mesmo mecanismo de captura visual e cabeçalho/rodapé em toda página do Relatório de
Vendas/Comissões — ver `docs/telas/relatorio-vendas.md`, seção "PDF".

## Contrato de API

```
GET /api/v1/relatorios/contas-receber?dataVendaInicial=&dataVendaFinal=
    &dataVencimentoInicial=&dataVencimentoFinal=&dataRecebimentoInicial=&dataRecebimentoFinal=
    &idsEmpresa=1&idsEmpresa=2&status=ABERTO|RECEBIDA&categoria=CREDIARIO|CARTAO_DEBITO|CARTAO_CREDITO
    &ordenarPor=&direcao=
  → { linhas: [{idEmpresa,nomeEmpresa,nomeEmpresaPagamento,idVenda,idCliente,nomeCliente,
                nomeCarteira,categoriaCarteira,numeroParcela,totalParcelas,
                dataVenda,dataVencimento,dataRecebimento,valorBruto,taxaAdministrativa,valorLiquido}],
      subtotaisPorEmpresa: [{idEmpresa,nomeEmpresa,valorBruto,valorLiquido}],
      totalGeral: {valorBruto,valorLiquido} }
```

Sob `/api/v1/**` (JWT de tenant, RLS ativo — P8), qualquer papel. Erros em Problem Details: 400
(período incompleto/inválido/excessivo, nenhum período informado).

## Critérios de aceitação (viram testes)

- Dado cartão débito com taxa configurada, o valor líquido é calculado corretamente.
- Dado crediário (sem taxa), o valor líquido é igual ao bruto.
- Dado filtro por período de recebimento, só parcelas recebidas dentro da janela aparecem.
- Dado nenhum período informado, responde 400.
- Dado uma venda à vista, ela não aparece no relatório.
- Dado o período de um tenant, dados de outro tenant não aparecem (RLS).
- Dado uma venda parcelada em 3x, número da parcela e total de parcelas saem corretos em cada
  linha.
- Dado filtro de status (aberta/recebida), cada filtro isola o conjunto certo de parcelas.
- Dado ordenação por valor bruto, ASC e DESC respeitam a direção pedida.

Cobertos por `RelatorioContasReceberCrudTest` (9 testes).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `relatorios.contasreceber.tela`** — ver `web/src/components/AjudaDaTela.tsx`.
  `url_video`: `NULL` por ora.

## Impacto no banco

Nenhum — tela 100% de leitura sobre tabelas já existentes (`contas_receber`, `venda`, `empresa`,
`cliente`, `tipo_carteira`). Nenhuma migration nova.

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- **Lançar/editar recebimento a partir do relatório** — é só consulta; recebimento continua
  sendo feito na tela de Recebimento de Crediário / no fechamento de venda no PDV.

## Questões abertas

Nenhuma bloqueante.

---

## Revisão 2026-08-22 — o popup de filtros fecha com `navigate(-1)` (auditoria, item 17)

O botão de fechar do popup obrigatório de filtros usava `navigate('/')`, empilhando histórico.
Passou a `navigate(-1)`, alinhado com `RelatorioDre` e `FluxoCaixa`. Mesma correção em Vendas,
Comissões, Estoque e Movimentação de Produtos.

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
