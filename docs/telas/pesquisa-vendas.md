# Spec: Pesquisa de Vendas                         Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-07-30 · Módulo(s): `vendas` · Fase: 2

## Problema

Não existe hoje uma tela pra localizar vendas já registradas e ver, numa única tela, o
detalhamento completo: itens vendidos, movimentação de caixa gerada e parcelas de crediário (quando
houver). O Cancelamento de Venda (`docs/telas/cancelamento-venda.md`) tem uma grid de busca
parecida, mas é ADMIN-only, focada em cancelar (esconde vendas já canceladas por padrão) e não
mostra caixa/crediário. Atendente, financeiro, gerência e suporte ficam sem uma consulta de uso
diário — só dá pra ver detalhes de uma venda de crediário indiretamente, pelo Histórico do Cliente.

## Solução proposta

Tela `/pesquisa-vendas`, disponível a **qualquer papel** (ADMIN e OPERADOR), **somente consulta**
(alterações continuam na rotina de cadastro/PDV, ou no Cancelamento de Venda). Painel de filtros no
topo + grid de resultado paginada (mesmo esqueleto de paginação/ordenação do Cancelamento de Venda)
+, ao clicar numa linha, um popup com quatro grids **empilhadas** dentro dele (sem abas — decisão
da especificação original, mantida: o usuário compara produtos com recebimentos ao mesmo tempo).
**Desde 2026-07-31 o detalhamento abre em popup** (`DetalheVendaModal.tsx`, rola internamente,
sem afetar o scroll da página) — a versão original empilhava o bloco abaixo da grid de resultado.

Adaptada de uma especificação escrita para um sistema de referência ("Mitryus ERP") pro modelo de
dados real do Niner — ver seção seguinte para cada divergência e a decisão tomada.

## Adaptações em relação à especificação original

- **Sem `venda_item`/`valor_total`/`desconto` na tabela `venda`.** Itens, total e desconto vêm do
  ledger de estoque (`produto_movimento_mestre`/`produto_movimento_detalhe`, tipo `VENDA`) — mesmo
  padrão de `CancelamentoVendaService`/`ClienteHistoricoService`. Valor do item = `qtd_produto *
  preco_venda − valor_desconto + valor_acrescimo`; valor da venda = soma dos itens.
- **Sem `observação` na venda** (comentário da tabela `venda`: *"Sem valor_total/observacao/
  criado_em... total é derivado do ledger"*) — campo removido do detalhamento.
- **Sem "Unidade" no cadastro de produto** — coluna removida da grid de itens vendidos.
- **"Situação" só tem dois estados reais** — `venda.cancelada` é um boolean, não existe "Em aberto"
  separado de "Faturada" (uma venda do PDV já nasce fechada, financeiramente resolvida na hora,
  crediário incluso). Situação exibida = **Faturada** ou **Cancelada**.
- **"Condição de pagamento" é derivada, não armazenada** — lista as formas de pagamento usadas
  (`contas_receber` daquela venda, uma linha por forma), com "(Nx)" quando há crediário.
- **Filtro de empresa segue o mesmo padrão já usado em Fechamento de Caixa** (`docs/telas/
  fechamento-caixa.md`): ADMIN vê um combo com todas as empresas do tenant (`GET /api/v1/
  empresas`); OPERADOR pesquisa só a empresa ativa da sessão (claim JWT `eid`), sem combo, e o
  backend ignora qualquer `idEmpresa` enviado por um OPERADOR. Não existe hoje um mecanismo de
  "empresas que o usuário tem acesso" fora do login — `usuario_empresa` só monta a lista de
  escolha na hora de entrar (`docs/telas/login-empresa.md`).
- **"Vendedor" = `funcionario`** — não existe um cadastro de vendedor separado.
- **Troco não existe como lançamento hoje** — o enum `TROCO` de `tipo_operacao_caixa` nunca é
  gravado (não há lógica de troco no PDV). O ponto em aberto original sobre troco fica sem
  resposta por não ser aplicável; quando o PDV implementar troco, a grid de movimentação de caixa
  (5.3) já está preparada pra mostrar a linha (mesmo enum).

## Perfil de uso

| Perfil | Uso típico |
|---|---|
| Atendente / balcão | Localizar venda do dia para conferência |
| Financeiro | Conferir recebimentos e parcelas em aberto de uma venda |
| Gerente / supervisor | Acompanhar vendas por período, vendedor ou empresa |
| Suporte | Investigar divergência entre venda, caixa e crediário |

## Decisões de escopo (resolvendo os pontos em aberto da especificação original)

1. **Duplo clique na grid de resultado** — nenhuma ação especial nesta v1 (clique simples já carrega
   o detalhamento; não existe hoje reimpressão de comprovante de venda do PDV nem tela de edição de
   venda pra abrir).
2. **Exportação (Excel/PDF)** — fora de escopo nesta v1.
3. **Filtro por situação da venda** — incluído: combo Todas (padrão) / Ativas / Canceladas.
4. **Período máximo sem filtro adicional** — 365 dias, igual ao Cancelamento de Venda (mesma
   mensagem de erro).
5. **Campos "Recebido" / "A receber"** — incluídos no bloco de dados da venda, calculados a partir
   de `contas_receber` (ver RN "Recebido/A receber").
6. **Desconto por item** — existe (`produto_movimento_detalhe.valor_desconto`) e é exibido.
7. **Troco** — não aplicável (ver "Adaptações" acima).

## User stories

- Como atendente, quero localizar uma venda pelo número, ou por período + cliente/vendedor, sem
  precisar de permissão de administrador.
- Como financeiro, quero ver numa venda quanto já foi recebido e quanto ainda falta receber, sem
  somar manualmente caixa e parcelas.
- Como gerente, quero acompanhar vendas por período/empresa/vendedor e ver o total do que foi
  vendido (sem contar canceladas).
- Como suporte, quero abrir uma venda e ver produtos, lançamentos de caixa e parcelas de crediário
  juntos, pra investigar uma divergência sem trocar de tela.
- Como qualquer usuário, quero que uma venda cancelada apareça sinalizada, sem distorcer o total.

## Filtros de pesquisa

| Campo | Tipo | Obrigatório | Comportamento |
|---|---|---|---|
| Nº da venda | Numérico | Não | Preenchido, ignora os demais filtros e retorna a venda exata (mesmo comportamento do Cancelamento de Venda) |
| Empresa | Combo | Não | ADMIN: todas as empresas do tenant, padrão "Todas". OPERADOR: campo fixo na empresa ativa da sessão, sem combo |
| Situação | Combo | Não | Todas (padrão) / Ativas / Canceladas |
| Data início da venda | Data (texto mascarado `dd/mm/aaaa`) | Sim* | Sugerido: primeiro dia do mês corrente |
| Data final da venda | Data (texto mascarado `dd/mm/aaaa`) | Sim* | Sugerido: hoje |
| Cliente | Lookup (`PesquisaClienteModal`, já existe) | Não | Busca parcial por nome/razão social |
| Vendedor | Lookup (`PesquisaVendedorModal`, já existe) | Não | Lista `funcionario` ativos |

\* Obrigatório **exceto** quando "Nº da venda" está preenchido.

### Validações

- Data início > data final → *"Data inicial não pode ser maior que a data final."*
- Período > 365 dias sem nº da venda → *"Período de consulta não pode exceder 365 dias."*
- Nenhuma data e nenhum nº de venda → botão Pesquisar desabilitado (mesmo padrão do Cancelamento de
  Venda: `podeBuscar`).

## Grid de resultado

| Coluna | Alinhamento | Formato | Observação |
|---|---|---|---|
| Nº da venda | Direita | Inteiro | |
| Data da venda | Centro | dd/mm/aaaa | |
| Empresa | Esquerda | Nome fantasia/razão social | Oculta se OPERADOR (empresa única, fixa) |
| Cliente | Esquerda | Nome/razão social | `—` quando venda sem cliente vinculado |
| Vendedor | Esquerda | Nome | `—` quando nenhum item tem `id_funcionario` |
| Valor da venda | Direita | R$ 9.999.999,99 | Valor líquido (já com desconto) |
| Situação | — | Badge | "Cancelada" (vermelho) quando `cancelada = true`; nada quando ativa |

- **Ordenação padrão:** nº da venda decrescente. Todas as colunas ordenáveis por clique no
  cabeçalho (mesmo componente `th-ordenavel` já usado nas outras telas).
- **Totalizador:** quantidade de vendas retornadas pelo filtro + soma do valor — **calculado sobre
  todo o resultado filtrado, não só a página atual**, e **excluindo canceladas**.
- **Seleção:** clique simples abre o detalhamento (seção seguinte) num popup.
- **Sem resultado:** *"Nenhuma venda encontrada para os filtros informados."*, filtros preenchidos.
- **Paginação:** 50 por página, janela deslizante de 7 páginas (mesmo padrão de Cancelamento de
  Venda/Conta Corrente).

## Detalhamento da venda selecionada

Ao clicar numa linha, um popup (`DetalheVendaModal.tsx`) abre com quatro grids empilhadas dentro
dele, nesta ordem:

### 1. Dados da venda

| Campo | Origem |
|---|---|
| Nº da venda / Data / Empresa | `venda` |
| Cliente / CPF ou CNPJ | `cliente` (mascarado com `mascararCpfCnpj`) |
| Vendedor | `produto_movimento_detalhe.id_funcionario` → `funcionario` |
| Condição de pagamento | Derivada de `contas_receber` (lista de formas + "(Nx)" se crediário) |
| Desconto | Soma de `produto_movimento_detalhe.valor_desconto` dos itens |
| Valor total | Soma dos itens |
| **Recebido** | Soma de `contas_receber.valor_recebido` onde `data_recebimento IS NOT NULL` |
| **A receber** | Soma de `contas_receber.valor_receber` onde `data_recebimento IS NULL` |
| Situação | Faturada / Cancelada — com data/usuário/motivo do cancelamento quando cancelada |

### 2. Produtos vendidos

| Coluna | Formato |
|---|---|
| Código (SKU interno) | `produto_barra.sku` |
| Descrição | Produto + variação (linha/coluna), quando houver |
| Quantidade | 9.999,999 |
| Valor unitário | R$ 9.999,99 |
| Desconto do item | R$ 9.999,99 |
| Valor total do item | R$ 9.999.999,99 |

Linha de totalização ao final.

### 3. Movimentação de caixa

| Coluna | Formato |
|---|---|
| Data/Hora | dd/mm/aaaa hh:mm |
| Tipo de operação | `caixa_detalhe.tipo_operacao` (RECEBIMENTO_VENDA/RECEBIMENTO_PARCELA_CREDIARIO/…) |
| Forma de pagamento | `tipo_carteira.nome_carteira` |
| Documento (origem) | "Venda nº X" ou "Recebimento nº X" (mesmo helper `origem()` de `CaixaService`) |
| C/D | Crédito/Débito |
| Valor | R$ 9.999.999,99 |

Todos os lançamentos de `caixa_detalhe` vinculados à venda (inclusive recebimentos de parcela feitos
em dias posteriores — o `id_venda` da parcela original continua no lançamento). Sem lançamentos →
*"Nenhuma movimentação de caixa para esta venda."*.

### 4. Parcelas de crediário

| Coluna | Formato |
|---|---|
| Parcela | n/total |
| Vencimento | dd/mm/aaaa |
| Valor | R$ 9.999.999,99 |
| Situação | Aberta / Paga / Vencida |
| Data de pagamento | dd/mm/aaaa, `—` se aberta |
| Valor pago | R$ 9.999.999,99, `—` se aberta |
| Juros | R$ 9.999,99 (`contas_receber.valor_juros`) |

Só linhas de `tipo_carteira.categoria_carteira = 'CREDIARIO'`. **Quando a venda não é a prazo, a
grid aparece com a mensagem "Esta venda não possui parcelas de crediário." em vez de ser ocultada**
(mantém a expectativa visual de tela completa, como pedia a especificação original). Situação
"Vencida" é sempre calculada na hora (`data_recebimento IS NULL AND data_vencimento < now()`),
nunca lida de uma coluna — mesmo padrão de `ClienteHistoricoService`.

## Regras de negócio

### Permissão de empresa por papel

ADMIN pode filtrar/ver qualquer empresa do tenant. OPERADOR sempre pesquisa só a empresa ativa da
sessão (claim `eid`) — o backend ignora um `idEmpresa` diferente enviado por um OPERADOR (não é
erro, é substituído silenciosamente pela empresa da sessão, mesmo espírito de Fechamento de Caixa).
Não há restrição de cliente/vendedor/nº de venda por papel — qualquer usuário autenticado pode
pesquisar.

### Situação e totalizador

`cancelada = true` → badge "Cancelada" na grid, mas **não entra na soma nem na contagem do
totalizador** (a contagem/soma são recalculadas sobre o filtro completo, ignorando canceladas,
independentemente da página exibida).

### Valor líquido do item/venda

`valor_item = qtd_produto * preco_venda − valor_desconto + valor_acrescimo` (mesma fórmula usada em
Cancelamento de Venda e Histórico do Cliente). `valor_venda` = soma dos itens dessa venda.

### Recebido / A receber

`recebido = SUM(valor_recebido) WHERE data_recebimento IS NOT NULL`; `a_receber =
SUM(valor_receber) WHERE data_recebimento IS NULL`, ambos sobre `contas_receber` da venda (cobre
tanto pagamento à vista, já quitado na hora da venda, quanto crediário em aberto). `recebido +
a_receber` deve bater com `valor_venda` — divergência indicaria bug no PDV/Recebimento, não é
validada ativamente nesta tela (é só exibição).

### Nenhuma grid de detalhamento permite edição

Toda a seção de detalhamento é somente leitura — sem botões de ação, sem inputs.

## Contrato de API

```
GET /api/v1/vendas/pesquisa?numeroVenda=&idEmpresa=&situacao=&idCliente=&idFuncionario=
        &dataInicial=&dataFinal=&pagina=&limite=&ordenarPor=&direcao=
    → { itens: [{ idVenda, idEmpresa, nomeEmpresa, dataVenda, idCliente, nomeCliente,
        idFuncionario, nomeFuncionario, valorVenda, cancelada }],
        pagina, tamanhoPagina, totalItens, totalPaginas,
        totalItensAtivos, somaValorAtivas }
    -- totalItensAtivos/somaValorAtivas cobrem TODO o filtro (não só a página), excluindo canceladas.

GET /api/v1/vendas/pesquisa/{idVenda}
    → { idVenda, idEmpresa, nomeEmpresa, dataVenda, idCliente, nomeCliente, cpfCnpj, fisicaJuridica,
        idFuncionario, nomeFuncionario, condicaoPagamento, desconto, valorTotal, recebido, aReceber,
        cancelada, dataCancelamento, nomeUsuarioCancelamento, motivoCancelamento,
        itens: [{ codigo, descricaoProduto, variacaoCor, variacaoTamanho, qtd, valorUnitario,
                   valorDesconto, valorItem }],
        movimentosCaixa: [{ dataHora, tipoOperacao, nomeCarteira, origem, creditoDebito, valor }],
        temParcelasCredario: boolean,
        parcelas: [{ numeroParcela, totalParcelas, dataVencimento, valor, situacao,
                      dataPagamento, valorPago, valorJuros }] }
```

`situacao` aceita `ATIVAS`/`CANCELADAS`/omitido (todas). Ambos sob `/api/v1/**` (JWT de tenant, RLS
ativo — P8), qualquer papel. Erros em Problem Details: 400 (datas inválidas/período > 365 dias sem
nº de venda), 404 (`idVenda` inexistente ou de outro tenant).

## Critérios de aceitação (viram testes)

- Dado um nº de venda preenchido, quando pesquisa, então ignora os demais filtros e devolve só
  aquela venda (mesmo cancelada).
- Dado nenhum nº de venda e nenhuma data, então a pesquisa não executa (front) / 400 (back, se
  forçado via API).
- Dado um período maior que 365 dias sem nº de venda, então 400.
- Dado um OPERADOR, quando informa `idEmpresa` de outra empresa, então a pesquisa usa a empresa da
  sessão dele mesmo assim (não filtra pela informada, nem dá erro).
- Dado um filtro de situação "Ativas"/"Canceladas", então só vendas com aquela situação retornam.
- Dado um resultado com vendas ativas e canceladas, então `totalItensAtivos`/`somaValorAtivas`
  ignoram as canceladas, mesmo em páginas diferentes.
- Dado o detalhe de uma venda com itens em mais de uma variação, então a lista de itens bate com o
  ledger e a soma bate com `valorTotal`.
- Dado o detalhe de uma venda com pagamento parte à vista/parte crediário, então `recebido` reflete
  só a parte já quitada e `aReceber` só a parte em aberto.
- Dado o detalhe de uma venda sem crediário, então `temParcelasCredario = false` e `parcelas` vem
  vazio (a tela mostra a mensagem, não some com a grid).
- Dado uma parcela vencida (vencimento no passado, não paga), então a situação computada é
  "Vencida", nunca lida de uma coluna do banco.
- Dado o caixa de um tenant, então a pesquisa/detalhe de outro tenant não aparece (RLS).

Cobertos por `PesquisaVendaCrudTest`.

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `vendas.pesquisavendas.tela`** — ver `web/src/components/AjudaDaTela.tsx`.
  `url_video`: `NULL` por ora.

## Impacto no banco

Nenhum — tela 100% de leitura sobre tabelas já existentes (`venda`, `produto_movimento_mestre/
detalhe`, `contas_receber`, `caixa_detalhe`, `cliente`, `funcionario`, `empresa`, `tipo_carteira`).
Nenhuma migration nova.

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- **Edição da venda** — só a rotina de cadastro/PDV altera uma venda; cancelamento continua em
  Cancelamento de Venda.
- **Exportação (Excel/PDF)** — ver "Decisões de escopo".
- **Reimpressão de comprovante da venda** — não existe comprovante de venda do PDV hoje (só
  crediário tem, `docs/telas/comprovante-recebimento-crediario.md`); fora desta feature.
- **Sangria/troco como lançamento de caixa** — não implementado em nenhuma tela hoje.

## Questões abertas

Nenhuma bloqueante — todas as da especificação original foram resolvidas em "Decisões de escopo".

## Métrica de sucesso

Localizar uma venda e conferir seu detalhamento completo (itens + caixa + crediário) em menos de 15
segundos.
