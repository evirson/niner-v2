# Telas do Nainer — inventário completo

> Gerado a partir de `web/src/lib/menu.ts` + `web/src/App.tsx` em 2026-08-25.
>
> ⚠️ **É retrato do CÓDIGO, não da intenção.** Se divergir de outra documentação, o código
> está certo e a outra doc envelheceu.
>
> A coluna **Spec** é casada por semelhança de nome, com um de-para manual para os casos em
> que o arquivo tem outro nome. ⚠️ Um `—` ali significa *não encontrei*, **não** *não existe*.


## Frente de Loja

| Tela | Rota | Spec |
|---|---|---|
| Fila de Expedição | `/expedicao` | `docs/MODULOMARKETPLACE.md` §10.10 |
| PDV - Vendas | `/pdv` | `docs/telas/pdv.md` · `papeleta-venda.md` |
| Orçamentos | `/orcamentos` | `docs/telas/orcamento.md` |
| Pesquisa de Vendas | `/pesquisa-vendas` | `docs/telas/pesquisa-vendas.md` |
| Recebimento de Crediário | `/recebimento-crediario` | `docs/telas/recebimento-crediario.md` |
| Reimpressão de Recebimento de Crediário | `/reimpressao-recebimento-crediario` | `docs/telas/comprovante-recebimento-crediario.md` |
| Devolução de Produtos | `/devolucao-produto` | `docs/telas/devolucao-produtos.md` |
| Fechamento de Caixa | `/fechamento-caixa` | `docs/telas/fechamento-caixa.md` |

## Cancelamentos

| Tela | Rota | Spec |
|---|---|---|
| Estorno de Crediário | `/estorno-recebimento-crediario` | `docs/telas/estorno-recebimento-crediario.md` |
| Cancelamento de Devolução de Produtos | `/cancelamento-devolucao-produtos` | `docs/telas/cancelamento-devolucao-produtos.md` |

## Estoque

| Tela | Rota | Spec |
|---|---|---|
| Entrada de Produtos por Compra | `/entrada-produtos-compra` | `docs/telas/cancelamento-devolucao-produtos.md` |
| Devolução de Produtos Comprados | `/estoque/devolucao-compra` | `docs/telas/devolucao-compra.md` |
| Transferência de Produtos | `/estoque` | `docs/telas/cancelamento-devolucao-produtos.md` |

## Contagem de Estoque

| Tela | Rota | Spec |
|---|---|---|
| Contagem de Estoque | `/estoque/contagem` | `docs/telas/contagem-estoque.md` |
| Diferenças de Estoque | `/estoque/diferencas` | `docs/telas/contagem-estoque.md` |
| Efetivar Balanço | `/estoque/efetivar-balanco` | — |
| Zerar Contagem de Estoque | `/estoque/zerar-contagem` | `docs/telas/contagem-estoque.md` |

## Financeiro

| Tela | Rota | Spec |
|---|---|---|
| Conta Corrente | `/contas-corrente` | `docs/telas/conta-corrente-movimento.md` |
| Movimentação de Conta Corrente | `/contas-corrente-movimento` | `docs/telas/conta-corrente-movimento.md` |
| Plano de Contas | `/planos-contas` | `docs/telas/plano-contas.md` |
| Tipo de Carteira | `/tipos-carteira` | — |
| Contas a Pagar / Pagas | `/contas-pagar` | `docs/telas/contas-pagar.md` |

## Cadastros

| Tela | Rota | Spec |
|---|---|---|
| Clientes | `/clientes` | `docs/telas/cliente.md` |
| Fornecedores | `/fornecedores` | `docs/telas/fornecedor.md` |
| Funcionários | `/funcionarios` | `docs/telas/funcionario.md` |
| Produtos | `/produtos` | `docs/telas/cancelamento-devolucao-produtos.md` |

## Configurações

| Tela | Rota | Spec |
|---|---|---|
| Canais de Venda | `/canais` | `docs/MODULOMARKETPLACE.md` |
| Vincular Anúncios | `/canais/:idCanal/anuncios` | `docs/MODULOMARKETPLACE.md` §10.7 |
| Minha Conta | `/minha-conta` | `docs/telas/conta-corrente-movimento.md` |
| Usuários | `/usuarios` | `docs/telas/usuario.md` |
| Empresas | `/empresas` | `docs/telas/empresa.md` |
| Parâmetros do Sistema | `/configuracoes-gerais` | `docs/telas/configuracao-geral.md` |
| Configuração de Etiqueta de Produtos | `/etiqueta-configuracao` | `docs/telas/configuracao-etiqueta.md` |

## Importação de Dados

| Tela | Rota | Spec |
|---|---|---|
| Clientes | `/importacao-dados/clientes` | `docs/telas/cliente.md` |
| Contas a Receber | `/importacao-dados/contas-receber` | `docs/telas/relatorio-contas-receber.md` |
| Fornecedores | `/importacao-dados/fornecedores` | `docs/telas/fornecedor.md` |
| Produtos | `/importacao-dados/produtos` | `docs/telas/cancelamento-devolucao-produtos.md` |
| Estoque Inicial | `/importacao-dados/estoque` | `docs/telas/contagem-estoque.md` |
| Exportação de Dados | `/exportacao-dados` | `docs/telas/exportacao-dados.md` |

## Fiscal

| Tela | Rota | Spec |
|---|---|---|
| Configuração Fiscal | `/fiscal/configuracao` | `docs/MODULOFISCAL.md §B2` |
| Perfis Fiscais | `/fiscal/perfis` | `docs/MODULOFISCAL.md §B2` |
| Certificado Digital | `/fiscal/certificados` | `docs/MODULOFISCAL.md §B2` |
| Conformidade Fiscal | `/fiscal/conformidade` | `docs/MODULOFISCAL.md §B3` |
| Contingência Fiscal | `/fiscal/contingencia` | `docs/MODULOFISCAL.md §9.7` |
| Documentos Fiscais | `/fiscal/documentos` | `docs/MODULOFISCAL.md §12` |
| Inutilização de Numeração | `/fiscal/inutilizacao` | `docs/MODULOFISCAL.md §9.8` |

## Relatórios

| Tela | Rota | Spec |
|---|---|---|
| Relatório de Vendas | `/relatorio-vendas` | `docs/telas/relatorio-vendas.md` |
| Relatório de Comissões | `/relatorio-comissoes` | `docs/telas/relatorio-comissoes.md` |
| Contas a Receber / Recebidas | `/relatorio-contas-receber` | `docs/telas/relatorio-contas-receber.md` |
| Relatório de Estoque | `/relatorio-estoque` | `docs/telas/relatorio-estoque.md` |
| Movimentação de Produtos | `/relatorio-movimentacao-produtos` | `docs/telas/relatorio-movimentacao-produtos.md` |
| Fluxo de Caixa | `/fluxo-caixa` | `docs/telas/fluxo-caixa.md` |
| DRE — Demonstração do Resultado | `/relatorio-dre` | `docs/telas/relatorio-dre.md` |
| Relatório de Lucratividade | `/lucratividade` | `docs/telas/relatorio-lucratividade.md` |
| CRM | `/crm` | `docs/telas/crm.md` |
| Emissão de Etiqueta de Produtos | `/etiqueta-emissao` | `docs/telas/etiqueta-emissao.md` |

## Implementações Futuras

| Tela | Rota | Spec |
|---|---|---|
| Painel | `/` | `docs/telas/painel-assinatura.md` |
| ⏭️ Pedidos | `/pedidos` | *em construção* |
| ⏭️ BI Dashboard | `/bi-dashboard` | *em construção* |
| ⏭️ Relatório de Contas a Pagar / Pagas | `/relatorio-contas-pagar` | *em construção* |
| Relatório de Movimentação Bancária | `/relatorio-movimentacao-bancaria` | `docs/telas/relatorio-movimentacao-produtos.md` |
| ⏭️ Integração com Marketplace | `/integracao-marketplace` | *em construção* |
| ⏭️ Cobrança de Crediário em Atraso | `/cobranca-crediario-atraso` | *em construção* |
| ⏭️ Exportação de XML em Lote | `/exportacao-xml-fiscal` | *em construção* |

## Telas-filhas

Abrem a partir de uma lista (criar/editar/configurar) e por isso não têm item de menu próprio.

- `/login`
- `/orcamentos/novo`
- `/produtos/novo`
- `/produtos/configuracao`
- `/estoque/nova`
- `/entrada-produtos-compra/nova`
- `/clientes/novo`
- `/clientes/configuracao`
- `/funcionarios/novo`
- `/funcionarios/configuracao`
- `/planos-contas/novo`
- `/tipos-carteira/novo`
- `/contas-corrente/nova`
- `/contas-corrente-movimento/novo`
- `/contas-pagar/nova`
- `/fornecedores/novo`
- `/fornecedores/configuracao`
- `/usuarios/novo`
- `/etiqueta-configuracao/novo`
- `/fiscal/perfis/novo`

---

**58 telas em uso** · **6 em construção** · **20 telas-filhas**.

⚠️ Atualizado em 2026-08-26 com a **Fila de Expedição** e o **Vincular Anúncios** (M2 e M7 do módulo de marketplace).

⚠️ Sem spec localizada: **Efetivar Balanço** · **Tipo de Carteira**.
