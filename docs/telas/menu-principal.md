# Spec: Menu Principal — hambúrguer no topo + páginas-hub com cards   Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-03 · Módulo(s): `web/` (shell) · Fase: 2

## Problema

O menu lateral do ERP já era retrátil e agrupado desde 2026-07-28/31 (`Layout.tsx`), mas tinha
três incômodos apontados pelo dono do produto:

1. **O controle de recolher ficava no rodapé do menu.** Com sete grupos e ~20 telas, o botão saía
   da área visível assim que a árvore abria — era preciso rolar a navegação inteira para
   recolher. O padrão que todo mundo conhece de aplicativo mobile é o inverso: o hambúrguer é a
   **primeira** coisa da navegação.
2. **A árvore inteira na lateral é ruído.** Sete grupos, dois subgrupos e vinte telas empilhados
   verticalmente numa coluna de 200px viram uma parede de rótulos truncados.
3. **Um grupo do menu não era lugar nenhum.** Clicar em "Financeiro" só abria/fechava rótulos
   curtos. Quem ainda não decorou o sistema não tinha onde descobrir *o que cada tela faz* — o
   rótulo "Tipo de Carteira" não explica nada a quem está começando.

## Solução proposta

Três mudanças no shell, sem tocar em nenhuma tela de domínio:

1. **Hambúrguer no topo** (`IconeMenuHamburguer`, novo em `Icones.tsx`): o botão de recolher/
   expandir passa a ser a primeira linha do `<nav>`, com divisória abaixo separando-o da
   navegação. Comportamento e persistência (`localStorage` `niner_nav_recolhido`) inalterados,
   inclusive a "espiada" no hover/foco com o menu recolhido. O botão **alterna de ícone e de
   rótulo conforme a preferência salva** — alfinete/"Menu fixo" quando travado em aberto,
   hambúrguer/"Menu retrátil" quando não.
2. **A lateral lista apenas os grupos principais.** Sete links, um por grupo, cada um levando à
   página-hub daquela área. Sem árvore, sem seta de expandir, sem sub-item na lateral.
3. **Página-hub por grupo** (`/menu/:grupo`, `MenuGrupo.tsx`): a área de conteúdo mostra os
   filhos do grupo como **cards** — ícone, nome e uma frase do que a tela faz. Um filho que é
   subgrupo (Caixa, Cancelamentos) vira um **card com subcards dentro**, então a hierarquia
   aparece inteira numa tela só.

## Decisões de escopo

1. **A árvore da lateral foi removida, não escondida.** A navegação para uma tela passa a ser
   dois passos (grupo na lateral → card no hub) em vez de um. É troca consciente: a lateral vira
   um índice curto e estável de sete linhas, e a descoberta do que existe em cada área acontece
   no hub, com explicação. `niner_nav_grupos_abertos` no `localStorage` deixa de ser lido —
   chave órfã, inofensiva, some sozinha quando o navegador limpa.
2. **O ícone do topo mostra estado, não ação.** A "espiada" no hover deixa o menu retrátil
   visualmente idêntico ao travado em aberto — mesma largura, mesmos rótulos — e o usuário não
   tinha como saber em qual dos dois estava até tirar o mouse. Então o botão passa a refletir a
   **preferência persistida**: alfinete em cor de destaque quando travado em aberto, hambúrguer
   quando no modo retrátil. **Só o ícone muda — o rótulo é sempre "Menu"** (decisão do dono do
   produto: trocar o texto para "Menu fixo"/"Menu retrátil" era redundante e poluía a primeira
   linha da navegação). A ação fica no `title`/`aria-label` ("clique para travar em aberto"), e o
   estado, no ícone. `aria-pressed` (não `aria-expanded`), porque o que o botão alterna é a
   fixação, não a visibilidade momentânea.
3. **Modo recolhido = ícones dos sete grupos.** Antes a faixa de 56px mostrava as ~20 telas
   achatadas; agora mostra os grupos, coerente com o modo expandido. `achatarFolhas()` saiu de
   `menu.ts` por ter ficado sem uso.
4. **Subgrupo é um nível de navegação, com seta de retorno.** O hub de *Frente de Loja* mostra
   três cards de tela e dois cards de subgrupo (*Caixa*, *Cancelamentos*); clicar num card de
   subgrupo abre o hub dele (`/menu/caixa`), onde os filhos aparecem como cards do mesmo
   tamanho. Chegou-se a testar o subgrupo como card com os filhos abertos dentro, mas ficava
   pesado e sem hierarquia clara. Como o conteúdo do subgrupo não fica mais visível no nível de
   cima, o card mostra a **contagem de telas**, e o hub do subgrupo ganha **seta de retorno**
   para o pai (`acharPai()` em `menu.ts`) mais o nome do pai como trilha acima do título. Hubs
   de primeiro nível não têm seta — o nível acima deles é a própria lateral.
5. **Prefixo de rota `/menu/`.** As chaves de grupo colidem com rotas de tela já existentes —
   `estoque` é a Transferência de Produtos, não o grupo. `rotaDoGrupo()` centraliza a montagem.
   Subgrupos também têm hub próprio válido (`/menu/caixa`), acessível por URL, ainda que nada
   linke para lá hoje.
6. **O menu virou dado, em `web/src/lib/menu.ts`.** Estava embutido em `Layout.tsx`; agora
   `Layout` e `MenuGrupo` leem a mesma árvore, então descrição, ícone e regra de papel de cada
   item existem em um lugar só. Acrescentar uma tela ao sistema continua sendo editar `MENU`,
   agora com um campo `descricao` obrigatório.
7. **`descricao` é obrigatória em item e em grupo** (tipo, não convenção). É o que dá conteúdo
   ao card; um item sem descrição renderizaria um card vazio pela metade.
8. **Filtro por papel vale no hub também.** `MenuGrupo` roda `filtrarPorPapel` com o papel de
   `/api/v1/eu` antes de montar os cards, então *Cancelamento de Vendas* (ADMIN) não aparece
   para OPERADOR, e `/menu/configuracoes` acessado na unha por um OPERADOR cai no estado
   "Área não encontrada". Isso é conveniência de UI: **a autorização de verdade continua no
   servidor** (P4) — nenhuma regra nova de negócio entrou no front.

## Critérios de aceitação

```
Dado que estou com o menu travado em aberto
Quando olho o topo da navegação lateral
Então o primeiro elemento é o botão com ícone de alfinete e o rótulo "Menu"
E abaixo dele há exatamente um link por grupo principal, nenhum sub-item
```

```
Dado que clico no botão do topo
Quando o menu recolhe para a faixa de 56px
Então o ícone vira o hambúrguer
E vejo os ícones dos grupos principais, um por grupo
E a preferência persiste em localStorage e sobrevive ao recarregar a página
```

```
Dado que o menu está no modo retrátil (recolhido)
Quando passo o mouse sobre a faixa e ela expande temporariamente
Então o topo mostra o hambúrguer — nunca o alfinete
E é por esse contraste que distingo a espiada do menu travado em aberto
E o rótulo continua sendo "Menu" nos dois estados
E tirar o mouse recolhe de novo, sem ter alterado a preferência
```

```
Dado que clico em "Financeiro" na lateral
Quando a navegação acontece
Então a URL passa a ser /menu/financeiro
E a área de conteúdo mostra 4 cards (Conta Corrente, Movimentação de Conta Corrente,
    Plano de Contas, Tipo de Carteira), cada um com ícone, nome e a frase do que a tela faz
E o item "Financeiro" na lateral fica marcado como ativo
```

```
Dado que estou em /menu/frente-loja
Quando a página monta
Então vejo os cards de PDV, Pesquisa de Vendas e Recebimento de Crediário
E um card "Caixa" e um card "Cancelamentos", cada um mostrando quantas telas contém
E o conteúdo desses dois NÃO aparece nesta tela
```

```
Dado que estou em /menu/frente-loja e clico no card "Cancelamentos"
Quando a navegação acontece
Então a URL passa a ser /menu/cancelamentos
E vejo os cards Cancelamento de Vendas e Estorno de Crediário (conforme o meu papel)
E há uma seta de retorno à esquerda do título, com "Frente de Loja" como trilha
E clicar na seta volta para /menu/frente-loja
```

```
Dado que estou em um hub de primeiro nível (ex.: /menu/financeiro)
Quando olho o título
Então NÃO há seta de retorno — o nível acima é a própria lateral
```

```
Dado que sou OPERADOR (não ADMIN)
Quando olho a lateral
Então o grupo "Configurações" não aparece
E acessar /menu/configuracoes pela URL mostra "Área não encontrada"
E o card "Cancelamentos" mostra só o Estorno de Crediário, sem o Cancelamento de Vendas
```

```
Dado que acesso /menu/inexistente
Quando a página monta
Então vejo o estado "Área não encontrada" com link de volta ao painel, sem erro de runtime
```

## Fora de escopo

- Busca/atalho de teclado sobre o menu (Ctrl+K) — cogitado, não pedido. Fica mais relevante
  agora que a tela não está mais a um clique; candidato natural à próxima iteração.
- Favoritos ou "acessados recentemente" nos hubs.
- Redesenhar o Painel (`/`) como launcher de tudo — avaliado e descartado em favor do hub por
  grupo, que mantém a rota `/` como resumo da conta.
- `AjudaDaTela` (R22) nos hubs: o card já traz a explicação; um ícone de ajuda sobre uma tela
  que só explica outras telas seria redundante.

## Arquivos

| Arquivo | Papel |
|---|---|
| `web/src/lib/menu.ts` | **novo** — tipos, `MENU` (com `descricao`), `filtrarPorPapel`, `acharGrupo`, `rotaDoGrupo` |
| `web/src/pages/MenuGrupo.tsx` | **novo** — página-hub: cards das telas e cards-com-subcards dos subgrupos |
| `web/src/components/Layout.tsx` | hambúrguer no topo; lateral reduzida aos grupos principais; menu importado de `lib/menu` |
| `web/src/components/Icones.tsx` | **novo** `IconeMenuHamburguer` |
| `web/src/App.tsx` | rota `/menu/:grupo` |
| `web/src/styles.css` | `.app-nav-toggle` no topo, `.menu-card*`, `.menu-subcard*`; saíram os `.app-nav-grupo*` |
