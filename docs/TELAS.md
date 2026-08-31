# Telas do Nainer — inventário completo

> Gerado a partir de `web/src/lib/menu.ts` + `web/src/App.tsx` em 2026-08-25; as telas de entrada foram acrescentadas em 2026-08-27 (elas não estão no menu, por serem públicas) e Ordens de Serviço em 2026-08-28.
>
> ⚠️ **Ordens de Serviço só aparece no menu com o módulo de serviços ligado** (`cfg_geral.cfg_usa_servicos`, desligado por padrão) — ela está no código e pode não estar na tela do lojista.
>
> ⚠️ **É retrato do CÓDIGO, não da intenção.** Se divergir de outra documentação, o código
> está certo e a outra doc envelheceu.
>
> **Contagem em 2026-08-31 (medida deste arquivo, não estimada):** **58 telas do ERP em uso** +
> **3 públicas** (entrada, sem login) + **6 em Implementações Futuras** = 67 linhas. A 58ª é a
> **Configuração da NFS-e** (V101/V103, 2026-08-31).
>
> ⚠️ **A NFS-e acrescentou uma tela e meia, não duas.** A *Configuração da NFS-e* é tela própria
> com rota e item de menu; a listagem de NFS-e é uma **aba dentro de Documentos Fiscais**, sem rota
> própria — mas tem chave em `cfg_tela` (`fiscal.nfse`) porque o RBAC governa emitir e cancelar.
> Quem contar por `cfg_tela` acha 59; quem contar por rota acha 58. As duas contagens estão certas,
> medem coisas diferentes.
>
> ⚠️ **Declare sempre a base ao citar o número.** Até hoje três documentos traziam contagens
> diferentes (57, 58 e 60) porque cada um incluía coisas distintas — as públicas, as futuras, ou
> as telas do marketplace que saíram em 2026-08-28. Comando que reproduz a conta:
> `awk '/^## /{sec=$0} /^| .* | `//{cnt[sec]++} END{for(s in cnt) print cnt[s], s}' docs/TELAS.md`
>
> A coluna **Spec** é casada por semelhança de nome, com um de-para manual para os casos em
> que o arquivo tem outro nome. ⚠️ Um `—` ali significa *não encontrei*, **não** *não existe*.


## Entrada (públicas, sem login)

| Tela | Rota | Spec |
|---|---|---|
| Entrar | `/login` | `docs/telas/login.md` · `login-empresa.md` |
| Esqueci minha senha | `/esqueci-senha` | `docs/telas/login.md` |
| Criar senha nova | `/redefinir-senha` | `docs/telas/login.md` |

⚠️ `/redefinir-senha` é o destino do link enviado por e-mail — renomear a rota quebra links vivos.

## Frente de Loja

| Tela | Rota | Spec |
|---|---|---|
| PDV - Vendas | `/pdv` | `docs/telas/pdv.md` · `papeleta-venda.md` |
| Orçamentos | `/orcamentos` | `docs/telas/orcamento.md` |
| Ordens de Serviço | `/ordens-servico` | `docs/telas/ordem-servico.md` |
| Pesquisa de Vendas | `/pesquisa-vendas` | `docs/telas/pesquisa-vendas.md` |
| Recebimento de Crediário | `/recebimento-crediario` | `docs/telas/recebimento-crediario.md` |
| Reimpressão de Recebimento de Crediário | `/reimpressao-recebimento-crediario` | `docs/telas/comprovante-recebimento-crediario.md` |
| Devolução de Produtos | `/devolucao-produto` | `docs/telas/devolucao-produtos.md` |
| Fechamento de Caixa | `/fechamento-caixa` | `docs/telas/fechamento-caixa.md` |
| Sangria de Caixa | `/sangria-caixa` | `docs/telas/sangria-caixa.md` |

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
| Exportação de XML em Lote | `/fiscal/exportacao-xml` | `docs/telas/exportacao-xml-lote.md` |
| Configuração da NFS-e | `/fiscal/nfse` | `docs/telas/nfse-configuracao.md` |
| NFS-e (aba em Documentos Fiscais) | — (aba, sem rota) | `docs/telas/nfse-emissao.md` |
| Inutilização de Numeração | `/fiscal/inutilizacao` | `docs/MODULOFISCAL.md §9.8` |

## Relatórios

Dividido em subgrupos por assunto em 2026-08-26 — a coluna **Subgrupo** é a de `web/src/lib/menu.ts`.
⚠️ Cinco rótulos encurtaram junto (o prefixo "Relatório de" ficou redundante dentro do subgrupo); os
nomes antigos continuam achando a tela na busca do topo via `sinonimos`.

| Subgrupo | Tela | Rota | Spec |
|---|---|---|---|
| Faturamento | Vendas | `/relatorio-vendas` | `docs/telas/relatorio-vendas.md` |
| Faturamento | Comissões | `/relatorio-comissoes` | `docs/telas/relatorio-comissoes.md` |
| Estoque | Posição de Estoque | `/relatorio-estoque` | `docs/telas/relatorio-estoque.md` |
| Estoque | Movimentação de Produtos | `/relatorio-movimentacao-produtos` | `docs/telas/relatorio-movimentacao-produtos.md` |
| Estoque | Etiquetas de Produtos | `/etiqueta-emissao` | `docs/telas/etiqueta-emissao.md` |
| Financeiro | Contas a Receber / Recebidas | `/relatorio-contas-receber` | `docs/telas/relatorio-contas-receber.md` |
| Financeiro | Contas a Pagar / Pagas | `/relatorio-contas-pagar` | `docs/telas/relatorio-contas-pagar.md` |
| Financeiro | Fluxo de Caixa | `/fluxo-caixa` | `docs/telas/fluxo-caixa.md` |
| Resultados | DRE — Demonstração do Resultado | `/relatorio-dre` | `docs/telas/relatorio-dre.md` |
| Resultados | Lucratividade | `/lucratividade` | `docs/telas/relatorio-lucratividade.md` |
| *(solto)* | CRM | `/crm` | `docs/telas/crm.md` |

## Implementações Futuras

| Tela | Rota | Spec |
|---|---|---|
| Painel | `/` | `docs/telas/painel-assinatura.md` |
| ⏭️ Pedidos | `/pedidos` | *em construção* |
| ⏭️ BI Dashboard | `/bi-dashboard` | *em construção* |
| Relatório de Movimentação Bancária | `/relatorio-movimentacao-bancaria` | `docs/telas/relatorio-movimentacao-produtos.md` |
| ⏭️ Integração com Marketplace | `/integracao-marketplace` | *em construção* |
| ⏭️ Cobrança de Crediário em Atraso | `/cobranca-crediario-atraso` | *em construção* |

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
- `/usuarios/:id/permissoes` — **Permissões do usuário** (RBAC, 2026-08-27; ícone de cadeado na
  lista de Usuários, que **não aparece** para o administrador — não há o que configurar nele)
- `/etiqueta-configuracao/novo`
- `/fiscal/perfis/novo`

---

**57 telas em uso** · **4 em construção** · **21 telas-filhas**.

⚠️ Atualizado em 2026-08-26. Entraram três telas: **Relatório de Contas a Pagar / Pagas** e
**Exportação de XML em Lote** (as duas saíram de "em construção" — ⚠️ a rota de cada uma já existia
apontando para o placeholder `EmBreve`, que foi removido junto, ver
[[feedback_placeholder_embreve_vira_rota_duplicada]]), além de **Fila de Expedição** e **Vincular
Anúncios** do marketplace. O grupo **Relatórios** ganhou a coluna **Subgrupo** no mesmo dia.

⚠️ Sem spec localizada: **Efetivar Balanço** · **Tipo de Carteira**.
⚠️ Do Tipo de Carteira, ao menos o teto de 0–100% do desconto/acréscimo ficou registrado em
`docs/telas/configuracao-geral.md` (auditoria de 2026-08-27) — a spec da tela continua faltando.

---

## Atualização de 2026-08-27

Entrou **Permissões do usuário** (`/usuarios/:id/permissoes`), a grade do RBAC — tela-filha de
Usuários, alcançada pelo ícone de cadeado. Spec: `docs/telas/usuario-permissoes.md`.

⚠️ **A tela de login ganhou uma etapa a mais** (código de 4 dígitos, quando o usuário tem login em
duas etapas ligado) — não é rota nova: é um estado do próprio `/login`, como as perguntas "qual
conta?" e "qual empresa?". Spec: `docs/telas/login-duas-etapas.md`.

⚠️ **A escolha de ambiente some da Configuração Fiscal** quando a instalação estiver em produção
(`NINER_FISCAL_AMBIENTE_FIXO`) — hoje vazia, porque o produto está homologando junto às SEFAZ.

---

## Atualização de 2026-08-28 — o marketplace saiu

Saíram **três telas** com a remoção da integração com marketplaces (decisão do dono do produto):
**Canais de Venda** (`/canais`), **Vincular Anúncios** (`/canais/:idCanal/anuncios`) e **Fila de
Expedição** (`/expedicao`). Foram de 60 para **57 telas em uso**.

⚠️ **Nada entrou em "Implementações Futuras" por causa disso** — os placeholders `/pedidos` e
`/integracao-marketplace` **já existiam** lá e nunca haviam sido removidos quando as telas reais
nasceram em 26/08. Ou seja: a contagem de "em construção" **não muda** (segue 4). É o inverso da
armadilha de sempre ([[feedback_placeholder_embreve_vira_rota_duplicada]]) — desta vez o
placeholder sobrevivente foi o que deixou o menu correto de graça.

⚠️ As duas telas também saíram do catálogo de RBAC (`cfg_tela`, migration **V084**). Tela
catalogada sem controller reprova `AcoesPorTelaConferemTest`, e grade oferecendo permissão para
tela inexistente é exatamente o defeito que a V076 introduziu e esse teste passou a impedir.

Detalhe da remoção: `docs/MODULOMARKETPLACE.md` (cabeçalho e §13).
