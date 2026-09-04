# Spec: Menu Principal — hambúrguer no topo + páginas-hub com cards   Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-03 · Módulo(s): `web/` (shell) · Fase: 2

## Problema

O menu lateral do ERP já era retrátil e agrupado desde 2026-07-28/31 (`Layout.tsx`), mas tinha
três incômodos apontados pelo dono do produto:

1. **O controle de recolher ficava no rodapé do menu.** Com sete grupos e ~20 telas (era o
   tamanho do menu em 2026-08-03; hoje os grupos continuam sete, mas são **~57 folhas** —
   `web/src/lib/menu.ts` é a contagem real), o botão saía
   da área visível assim que a árvore abria — era preciso rolar a navegação inteira para
   recolher. O padrão que todo mundo conhece de aplicativo mobile é o inverso: o hambúrguer é a
   **primeira** coisa da navegação.
2. **A árvore inteira na lateral é ruído.** Sete grupos, dois subgrupos e vinte telas empilhados
   verticalmente numa coluna de 200px viram uma parede de rótulos truncados — e o problema só
   cresceu desde então (hoje são ~57 folhas e mais subgrupos), o que confirma a decisão.
3. **Um grupo do menu não era lugar nenhum.** Clicar em "Financeiro" só abria/fechava rótulos
   curtos. Quem ainda não decorou o sistema não tinha onde descobrir *o que cada tela faz* — o
   rótulo "Tipo de Carteira" não explica nada a quem está começando.

## Solução proposta

Quatro mudanças no shell, sem tocar em nenhuma tela de domínio:

1. **Hambúrguer no topo** (`IconeMenuHamburguer`, novo em `Icones.tsx`): o botão de recolher/
   expandir passa a ser a primeira linha do `<nav>`, com divisória abaixo separando-o da
   navegação. Comportamento e persistência (`localStorage` `niner_nav_recolhido`) inalterados,
   inclusive a "espiada" no hover/foco com o menu recolhido. O botão **alterna de ícone conforme
   a preferência salva** — alfinete quando travado em aberto, hambúrguer quando no modo
   retrátil; o rótulo é sempre "Menu".
2. **A lateral lista apenas os grupos principais.** Sete links, um por grupo, cada um levando à
   página-hub daquela área. Sem árvore, sem seta de expandir, sem sub-item na lateral.
3. **Página-hub por grupo** (`/menu/:grupo`, `MenuGrupo.tsx`): a área de conteúdo mostra os
   filhos do grupo como **cards** — ícone, nome e uma frase do que a tela faz. Um filho que é
   subgrupo (Caixa, Cancelamentos) abre o próximo nível, com seta de retorno.
4. **Busca de telas no cabeçalho** (`BuscaDeTelas.tsx`): campo à direita do header, com
   **Ctrl+K** (ou ⌘K) de qualquer lugar do ERP, navegação por setas e Enter para abrir. É a
   contrapartida do custo de navegar por hub — o acesso direto que a lateral deixou de dar.
5. **Seletor de tema no cabeçalho** (`SeletorTema.tsx`, 2026-08-14): botão de ícone entre a busca
   e o "Sair", com um menu de três opções — **Claro / Escuro / Automático**. Ver a seção própria
   abaixo.

## Tema claro/escuro (2026-08-14)

A paleta dos dois temas existe em `styles.css` desde o começo do projeto (§3.7 da spec:
`:root` = clara, `@media (prefers-color-scheme: dark)` = escura, mais os overrides
`:root[data-theme='light']`/`[data-theme='dark']`). O que não existia era **quem escrevesse o
atributo** — por isso o ERP só seguia o tema do sistema operacional, e como o Windows costuma
estar em escuro, era o único tema que se via na prática. A spec já previa o toggle ("override
explícito via `data-theme`; **o toggle do usuário vence a preferência do sistema**"), então esta
tela fecha uma pendência antiga, não inventa comportamento novo.

- **Três estados, não dois.** `Automático` **não escreve** o atributo — é a media query
  decidindo, o comportamento histórico —, e por isso é o padrão de quem nunca mexeu no seletor.
  `Claro`/`Escuro` gravam `data-theme` e vencem o sistema operacional.
- **Guardado em `localStorage` (`niner_tema`), por navegador**, não no banco: sem migration, sem
  endpoint, e a escolha acompanha a máquina — o caixa da loja pode ficar claro e o computador do
  escritório escuro, independente de quem loga. Se um dia a preferência precisar seguir o usuário
  entre máquinas, o caminho é uma coluna em `usuario` + `/api/v1/eu`; nada do que existe hoje
  atrapalha essa migração.
- **Aplicado antes da primeira pintura.** O trecho que lê o `localStorage` e escreve o atributo é
  **duplicado em JS puro, inline no `<head>` do `web/index.html`**. Não é descuido: o bundle do
  React só roda depois que o browser já pintou o `<body>`, então sem esse script quem escolhesse
  "Claro" veria um flash escuro a cada F5. Qualquer mudança na regra de gravação precisa ser
  refletida nos dois lugares (`index.html` e `lib/tema.ts`).
- **O ícone do gatilho mostra o tema em uso, não a preferência** — com "Automático" ele vira sol
  ou lua conforme o SO (e acompanha a troca com o ERP aberto, via listener de `matchMedia`);
  mostrar o ícone de monitor não diria nada sobre o que está na tela.
- **Não exigiu tocar em nenhuma tela.** Toda cor do projeto sai de token (`var(--…)`), incluindo
  os gráficos Recharts (`stroke="var(--line)"`, `fill="var(--accent)"`) — as únicas cores fixas do
  `web/src` são o papel branco do editor de etiqueta e as constantes de captura de PDF, ambas
  propositais. `color-scheme` foi declarado nos blocos de tema para os controles **nativos**
  (lista do `<select>`, scrollbar, autofill) virem na paleta certa.
- **O PDF dos relatórios continua sempre claro**, independente do tema escolhido: a captura força
  `data-theme='light'` só no clone do documento (ver `docs/telas/relatorio-vendas.md`).
- ⚠️ **A extensão Dark Reader ignora tudo isso.** Ela injeta `<style class="darkreader--root-vars">`
  e reescreve os próprios tokens, então o ERP aparece escuro mesmo com "Claro" selecionado — e não
  há CSS do lado da página que resolva. Diagnosticado ao vivo em 2026-08-14 (os tokens liam
  `--ground: #f5f4f0` enquanto o `body` pintava `rgb(30,28,20)`). Se um lojista reclamar que o
  tema claro "não funciona", **essa é a primeira coisa a checar**, antes de procurar bug no CSS.

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
3. **Modo recolhido = ícones dos sete grupos.** Antes a faixa de 56px mostrava todas as telas
   achatadas (eram ~20 na época, hoje ~57); agora mostra os grupos, coerente com o modo expandido. `achatarFolhas()` saiu de
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
8. **A busca do cabeçalho indexa telas, não grupos.** O que se quer abrir é uma tela; o grupo é
   só o caminho. Mas o termo casa também contra a **trilha** e a **descrição**, então "caixa"
   traz Abertura/Fechamento (nome), e "cancelamento" traz o que está sob *Cancelamentos* mesmo
   quando o nome da tela não repete a palavra. A ordenação é por relevância —
   `pontuarTela()`/`buscarTelas()` ficam em `menu.ts`, não no componente: é lógica de dados e
   assim dá para exercitá-la sem montar React. Comparação **sem acento e sem caixa**
   (`normalizar()`): "crediario" tem que achar "Crediário".
9. **Layout do cabeçalho em 3 colunas.** Marca à esquerda, **nome da loja centralizado**, busca
   e Sair à direita (pedido do dono do produto). As colunas laterais são `1fr` para que a loja
   fique no centro **da tela**, não no espaço que sobra — o bloco da direita é bem mais largo
   que a marca. O elemento da loja é renderizado mesmo vazio, senão a coluna some e o
   alinhamento quebra quando não há empresa na sessão.
10. **Filtro por papel vale no hub também.** `MenuGrupo` roda `filtrarPorPapel` com o papel de
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
E a área de conteúdo mostra 5 cards (Conta Corrente, Movimentação de Conta Corrente,
    Plano de Contas, Tipo de Carteira, Contas a Pagar / Pagas), cada um com ícone, nome e a
    frase do que a tela faz
E o item "Financeiro" na lateral fica marcado como ativo
```

```
Dado que estou em /menu/frente-loja
Quando a página monta
Então vejo os cards de PDV, Pesquisa de Vendas, Recebimento de Crediário,
    Reimpressão de Recebimento de Crediário e Devolução de Produtos
E os cards de subgrupo "Caixa" e "Cancelamentos", cada um mostrando quantas telas contém
E o conteúdo desses dois NÃO aparece nesta tela
```

> ⚠️ **Atualizado em 2026-08-19:** o subgrupo "Reimpressões" saiu do menu — tinha só um item
> desde que "Reimpressão de Papeleta de Venda" saiu em 2026-08-18 (ficou redundante com o botão
> "Reimprimir papeleta" da Pesquisa de Vendas), e não fazia sentido manter um subgrupo de item
> único. "Reimpressão de Recebimento de Crediário" virou item direto de "Frente de Loja".

```
Dado que estou em /menu/frente-loja e clico no card "Cancelamentos"
Quando a navegação acontece
Então a URL passa a ser /menu/cancelamentos
E vejo os cards Cancelamento de Vendas, Estorno de Crediário e Cancelamento de Devolução
    de Produtos (conforme o meu papel — Cancelamento de Vendas é ADMIN-only)
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

```
Dado que estou em qualquer tela do ERP
Quando pressiono Ctrl+K (ou ⌘K)
Então o campo de busca do cabeçalho recebe o foco com o conteúdo selecionado
```

```
Dado que digito "crediario" (sem acento) na busca
Quando a lista aparece
Então vejo Recebimento de Crediário e Estorno de Crediário
E cada resultado mostra ícone, nome, descrição e a trilha de grupos
E setas ↑/↓ movem a seleção e Enter abre a tela selecionada
E Esc limpa o termo; com o termo já vazio, Esc tira o foco do campo
```

```
Dado que sou OPERADOR e busco "cancelamento"
Quando a lista aparece
Então vejo apenas Estorno de Crediário — Cancelamento de Vendas é ADMIN-only
```

```
Dado que busco um termo sem correspondência
Quando a lista aparece
Então vejo "Nenhuma tela encontrada para …", sem resultado algum
```

## Fora de escopo

- Busca sobre **dados** (cliente, produto, venda) — a do cabeçalho é só de telas.
- Favoritos ou "acessados recentemente" nos hubs e na busca.
- Redesenhar o Painel (`/`) como launcher de tudo — avaliado e descartado em favor do hub por
  grupo, que mantém a rota `/` como resumo da conta.
- `AjudaDaTela` (R22) nos hubs: o card já traz a explicação; um ícone de ajuda sobre uma tela
  que só explica outras telas seria redundante.

## Arquivos

| Arquivo | Papel |
|---|---|
| `web/src/components/BuscaDeTelas.tsx` | **novo** — busca de telas do cabeçalho (Ctrl+K, setas, Enter) |
| `web/src/lib/menu.ts` | **novo** — tipos, `MENU` (com `descricao`), `filtrarPorPapel`, `acharGrupo`, `acharPai`, `rotaDoGrupo`, `listarTelas`, `normalizar`, `pontuarTela`, `buscarTelas` |
| `web/src/pages/MenuGrupo.tsx` | **novo** — página-hub: cards das telas e cards-com-subcards dos subgrupos |
| `web/src/components/Layout.tsx` | hambúrguer/alfinete no topo; lateral reduzida aos grupos principais; cabeçalho em 3 colunas com a busca |
| `web/src/components/Icones.tsx` | **novos** `IconeMenuHamburguer`, `IconeAlfinete`, `IconeVoltar` |
| `web/src/App.tsx` | rota `/menu/:grupo` |
| `web/src/styles.css` | `.app-nav-toggle` no topo, cabeçalho em grid, `.busca-telas*`, `.menu-card*`, `.menu-hub-*`; saíram os `.app-nav-grupo*` |
| `web/src/components/SeletorTema.tsx` | **novo (2026-08-14)** — menu Claro/Escuro/Automático do cabeçalho |
| `web/src/lib/tema.ts` | **novo (2026-08-14)** — leitura/gravação em `localStorage` e escrita do `data-theme` |
| `web/index.html` | **(2026-08-14)** script inline no `<head>` que aplica o tema antes da primeira pintura (anti-flash) |

---

**Revisão 2026-09-04 — o tema CLARO virou azul; o ESCURO ficou como estava.**

Pedido do dono do produto a partir de um template do Figma Community (*ERP Dashboard*), do qual ele
disse ter gostado das cores. A escolha foi feita num **piloto reversível** (`?paleta=a|b|hoje`, dois
blocos temporários no `styles.css` mais um alternador no `main.tsx`, tudo removido depois) que
mostrava a mesma tela em três versões: o azul do template, uma versão só com as neutras suavizadas
mantendo o verde-petróleo, e a paleta de então. Ele escolheu o azul olhando o PDV.

**A paleta clara agora** (blocos `:root` e `:root[data-theme='light']` do `web/src/styles.css`, que
são cópias literais um do outro e precisam andar juntos):

| Token | Valor | Origem |
|---|---|---|
| `--ground` | `#d8e9f0` | `Fifth` do template |
| `--surface` | `#ffffff` | `White` |
| `--surface-2` | `#eaf1f6` | derivado |
| `--field-bg` | `#dbe7f0` | ⚠️ **não** é o `Fifth` puro — ver abaixo |
| `--ink` / `--field-text` | `#001446` | `Primary` |
| `--ink-muted` / `--label-color` | `#4a6280` | derivado |
| `--accent` | `#02437b` | `Second` |
| `--line` / `--line-strong` | `#c5dce7` / `#8dc2d5` | derivado / `Fourth` escurecido |
| `--danger` / `--sucesso` / `--info` / `--aviso` | `#b3261e` / `#1b7a4b` / `#026b93` / `#b45309` | ⚠️ **escolhidos aqui** |

⚠️ **O template não tem cor de estado.** São cinco tons do mesmo azul mais branco (`Primary
#001446` · `Second #02437B` · `Third #028BBF` · `Fourth #98CBDC` · `Fifth #D8E9F0`) — ele não
precisa de erro/sucesso/aviso porque as telas dele não têm nenhum desses estados. O Nainer tem
badges, toasts e tarjas, então as quatro cores foram escolhidas para conviver com o azul.

⚠️ **Dois desvios deliberados do template, ambos por medição de contraste** (os 13 pares críticos
foram calculados antes de escrever qualquer hex, e a paleta nova empata ou supera a anterior em
todos):

- `--field-bg`: o `Fifth` puro sobre card branco dá **1.16** e o campo desaparece contra o card —
  o produto segue o *golden file* "campos cinza" (§3.7). `#dbe7f0` dá **1.26**, exatamente a mesma
  separação campo/card que a paleta anterior entregava.
- `--line-strong`: o `Fourth` puro dá **1.76** contra os **1.89** anteriores, e borda mais fraca faz
  a tabela densa perder a grade.

🔵 **Decisão dele: o tema escuro NÃO acompanhou.** Foi apontado que botão primário e links ficariam
azuis no claro e verde-água (`#4fbdb2`) no escuro — o mesmo produto com duas identidades — e que
levar só o `--accent` para o escuro custaria ~4 linhas. Ele optou por manter o escuro intacto. A
consequência está escrita no comentário do próprio `styles.css`, não só aqui.

⚠️ **Escopo: só o `web/`.** `admin/` e `site/` continuam na paleta anterior. Como o `site/` é o
*golden file* da spec §3.7, a spec está desalinhada **de propósito** até alguém decidir propagar.

⚠️ **A paleta clara vive em três lugares**, e um deles deixou de espelhá-la: `:root` e
`:root[data-theme='light']` no `styles.css` continuam idênticos entre si, mas
`lib/paletaDeImpressaoParaCaptura.ts` (o PDF dos relatórios) passou a ter **paleta própria de
papel** — preto no branco. Ver `docs/telas/relatorio-vendas.md`.

---

**⛔ A extensão Dark Reader e o `darkreader-lock` (2026-09-04).**

Até esta data, `styles.css` registrava que o produto **não** vencia a extensão Dark Reader: ela
injeta as próprias folhas e reescreve os tokens, então quem a tivesse ativa via o ERP escuro mesmo
com o tema claro selecionado. **Isso deixou de ser verdade.**

O sintoma apareceu logo depois da paleta nova, e foi relatado como *"você bagunçou o tema light"*.
A medição na página mostrou o oposto:

```
niner_tema: "claro"    data-theme: "light"
--ground: #d8e9f0                      ← token correto
body pintado: rgb(23, 49, 60)          ← não é nosso
9 tags <style class="darkreader…">
--darkreader-neutral-background: #181a1b
```

⚠️ **A extensão só age quando a página está clara.** Enquanto o ERP estava no tema escuro ela ficava
quieta — por isso o problema nasceu junto com a paleta nova e pareceu causado por ela.

⛔ **Remover as folhas em runtime não resolve:** medido, ela reinjeta 8 em segundos. A saída é
`<meta name="darkreader-lock">` no `web/index.html` — o mecanismo oficial para a página declarar que
gerencia o próprio tema, o que o Nainer faz (tem seletor na topbar). Depois da tag: **0 folhas** e
`body` em `rgb(216,233,240)`.

⚠️ **No PDF a defesa é outra e continua necessária** (`removerDarkReaderDoClone`): o clone do
html2canvas é nosso e não é reinjetado, e relatório que sai da máquina para o contador não pode
depender de a extensão do lojista estar atualizada.
