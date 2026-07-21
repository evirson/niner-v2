# Spec: Cadastro de Clientes                    Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-07-20 · Módulo(s): `cadastros` (cliente + cfg_categoria_cliente) · Fase: 1 — Núcleo do ERP

## Problema

O lojista precisa identificar quem compra (loja física e, futuramente, marketplace) para
histórico de vendas, contato (WhatsApp/e-mail) e — mais adiante — crediário (Fase 2, limite
de crédito por cliente). Hoje não existe nenhuma tela: as tabelas `cliente` e
`cfg_categoria_cliente` existem no banco (V016) mas sem nenhum endpoint/UI.

## Solução proposta

Um CRUD de clientes (`web/`, superfície `/api/v1`) com formulário de cadastro/edição,
listagem com busca/filtro, e uma gestão simples e embutida da categoria do cliente (que é
campo obrigatório do cadastro). Papéis `ADMIN` e `OPERADOR` têm acesso completo (criar,
editar, excluir) — cadastro de cliente é operação de dia a dia (ex.: balcão), não cai na
restrição de R8 (que só barra OPERADOR de config de integrações e preço de custo).

## User stories

- Como **operador**, quero cadastrar um cliente novo rapidamente no balcão, mesmo sem CPF/CNPJ
  em mãos, para não travar o atendimento.
- Como **gestor**, quero classificar clientes por categoria (ex.: Padrão/VIP/Atacado) para
  relatórios e regras futuras (crediário, preço diferenciado).
- Como **operador**, quero que o endereço seja preenchido sozinho ao digitar o CEP, para não
  digitar tudo manualmente.
- Como **gestor**, quero excluir um cliente cadastrado por engano, mas se ele já tiver
  vendas associadas, quero que o sistema apenas o inative em vez de travar com erro.

## Campos do formulário

Tabela `cliente` (V016; `complemento` adicionado em 2026-07-20). Nomes de campo da API
seguem o contrato REST (snake_case do banco → camelCase no JSON, padrão dos demais endpoints
já implementados, ex. `OnboardingDtos`).

**Ordem de exibição no formulário (2026-07-20, pedido do dono do produto):** `ativo` é o
**primeiro campo da tela**, antes até do tipo de pessoa, e aparece tanto ao incluir quanto ao
editar. Os demais campos seguem a tabela abaixo.

**Foco automático (revisto 2026-07-21):** o foco automático ao abrir a tela vai para o campo
**Nome** — não mais para o checkbox "Cliente ativo" (que continua sendo o primeiro campo
visualmente, só deixou de receber o foco).

| Campo (banco) | Rótulo na tela | Componente | Máscara / formato | Tamanho na tela | Obrigatório | Regra |
|---|---|---|---|---|---|---|
| `ativo` | Cliente ativo | checkbox | — | linha própria, campo único | default `true` | **Primeiro campo do formulário** (2026-07-20); visível tanto ao incluir quanto ao editar — ver "Exclusão" abaixo |
| `fisica_juridica` | Tipo de pessoa | radio: **Física** \| **Jurídica** | — | linha própria | Sim, default **Física** | Controla os campos abaixo (nascimento/gênero) e a máscara de `cpf_cnpj` |
| `nome` | Nome / Razão Social | texto | — | linha própria (largo) | **Sim** (NOT NULL no banco) | Label muda para "Razão Social" quando Jurídica |
| `id_categoria_cliente` | Categoria | select (+ "＋ Nova categoria") | — | linha própria | **Sim** (NOT NULL no banco) | Ver seção "Categoria de cliente" abaixo |
| `cpf_cnpj` | CPF / CNPJ | texto | `000.000.000-00` (PF) / `00.000.000/0000-00` (PJ), conforme `fisica_juridica` | 1/3 da linha | Não (nullable no banco) | Se preenchido: **valida dígito verificador** (algoritmo oficial); único por tenant quando preenchido. **CNPJ é alfanumérico** (2026-07-21) — ver nota abaixo |
| `rg_ie` | RG / Inscrição Estadual | texto | — | 1/3 da linha (junto do CPF/CNPJ) | Não | Label muda para "Inscrição Estadual" quando Jurídica |
| `data_nascimento` | Data de nascimento | date picker | `dd/mm/aaaa` | 1/3 da linha | **Não** (2026-07-21 — deixou de ser obrigatória mesmo p/ Física) | Campo **oculto** quando Jurídica; quando preenchida, **não pode ser hoje nem no futuro** (validado no front e no back) |
| `genero` | Gênero | select: Masculino / Feminino / Outros | — | 1/3 da linha (junto da data) | **Condicional: obrigatório se Física** (CHECK do banco) | Campo **oculto** quando Jurídica |
| `email` | E-mail | texto | validação de formato e-mail (client-side) | metade da linha | Não | Única exceção à convenção de maiúsculas (item "Maiúsculas" abaixo) |
| `telefone` | **Celular** (renomeado 2026-07-21, era "Telefone") | texto | `(00) 00000-0000` | 1/4 da linha | Não | Quando preenchido: **11 dígitos com o 3º dígito = 9** (padrão de celular BR) |
| `whatsapp` | **Id. WhatsApp** (renomeado 2026-07-21, era "WhatsApp") | texto | `@` + dígitos (ex.: `@11999998888`, mesma convenção visual de Instagram/Facebook/TikTok) | 1/4 da linha | Não | Mesma regra do Celular (11 dígitos, 3º = 9) — só muda a máscara visual |
| `instagram` | Instagram | texto | `@usuario` (sem link completo) | 1/3 da linha | Não | |
| `facebook` | Facebook | texto | `@usuario` ou nome da página | 1/3 da linha | Não | |
| `tiktok` | TikTok | texto | `@usuario` | 1/3 da linha | Não | |
| `cep` | CEP | texto | `00000-000` | 1/4 da linha | Não | Ao completar 8 dígitos, consulta **ViaCEP** e preenche `endereco`/`bairro`/`cidade`/`estado` (o usuário pode corrigir depois) |
| `endereco` | Endereço | texto | — | 3/4 da linha (junto do CEP) | Não | |
| `numero` | Número | texto | — | campo pequeno (~1/6 da linha) | Não | Campo pequeno agrupado com Complemento/Bairro na mesma linha (item "Tamanho dos campos" abaixo) |
| `complemento` | Complemento | texto | — | campo médio (~1/3 da linha) | Não | **Novo (2026-07-20)** — apto/bloco/sala etc.; fica entre Número e Bairro, tanto no banco quanto na tela |
| `bairro` | Bairro | texto | — | campo médio/largo (junto de Número/Complemento) | Não | |
| `cidade` | Cidade | texto | — | 3/4 da linha | Não | |
| `estado` | UF | **select fixo com as 27 UFs** (AC…TO) | — | campo pequeno (~1/4 da linha, junto da Cidade) | Não | Coluna no banco continua `text` livre; só a UI restringe |
| `limite_credito` | Limite de crédito | numérico, moeda (`R$ 0,00`, mascarado — dígitos digitados = centavos) | `NUMERIC(12,2)` | campo pequeno (~1/3 da linha) | Não, default `0` | Campo **opcional exposto já no formulário**, mesmo o crediário (Fase 2) ainda não usar o valor de fato — só armazena |

`id_cliente`, `criado_em`, `atualizado_em` não aparecem no formulário (gerados pelo banco/API).

**CNPJ alfanumérico (2026-07-21) — implementação de referência para todo o projeto.** A partir
de julho/2026 a Receita Federal emite CNPJ no formato alfanumérico (IN RFB 2.229/2024): as 12
primeiras posições (raiz+ordem) aceitam dígitos `0-9` **ou** letras `A-Z` maiúsculas; os 2
dígitos verificadores finais (posições 13-14) continuam sempre numéricos. CNPJs só-numéricos
(formato antigo) continuam válidos. **CPF não muda** — 11 dígitos, só numérico, sem alteração.

- **Algoritmo do dígito verificador:** valor de cada caractere = código ASCII menos 48 (dígitos
  mantêm 0-9; letras viram 17-42). Pesos e módulo 11 são os mesmos de sempre — `[5,4,3,2,9,8,7,
  6,5,4,3,2]` para o 1º dígito, `[6,5,4,3,2,9,8,7,6,5,4,3,2]` para o 2º (que inclui o 1º DV);
  resto da divisão por 11 vira dígito por `resto < 2 ? 0 : 11 - resto`. Confirmado com o exemplo
  oficial `12.ABC.345/01DE-35` antes de implementar.
- **Frontend:** `web/src/lib/masks.ts` — `somenteAlfanumerico()` (maiúsculas, mantém `0-9A-Z`)
  em vez de `somenteDigitos()` (que descartaria as letras) para o CNPJ; `cnpjValido()` usa
  `charCodeAt(0) - 48` em vez de `Number()`. CPF continua com `somenteDigitos`, sem mudança.
- **Backend:** `api/.../cadastros/cliente/Documentos.java` — `somenteAlfanumerico()` análoga;
  `digitos()` já fazia `c - '0'` (coincidentemente já era "ASCII menos 48"), só faltava não
  descartar as letras antes de calcular. `cliente.cpf_cnpj` já era `text` no banco — sem
  migration.
- **Convenção para outras tabelas:** qualquer campo de CNPJ novo no projeto (fornecedor,
  empresa/tenant etc.) **deve reaproveitar essa mesma lógica** — nunca limpar o valor com um
  "só dígitos" antes de validar/persistir CNPJ. Registrado também em `CLAUDE.md` (seção
  "Conventions to honor when building").

**Tamanho dos campos (2026-07-20):** o formulário usa o grid de 12 colunas do design system
(§3.7 da spec) em vez de uma coluna única — campos curtos por natureza (Número, UF, CEP,
Limite de crédito, Telefone/WhatsApp) dividem a linha com outros campos em vez de ocupar a
largura toda, para caber mais campos por linha. Nome/Razão Social e Categoria continuam em
linha própria (podem ser longos). Colapsa para 1 coluna em telas ≤640px.

**Grid se reajusta quando um campo é ocultado (2026-07-21):** para os campos configuráveis
pela tela de configuração (`docs/telas/configuracao-tela.md`), ocultar um campo não deixa
vão vazio na linha — os campos restantes da mesma linha crescem proporcionalmente para
preencher as 12 colunas (`web/src/components/LinhaGrid.tsx` + `lib/grid.ts:distribuirSpans`,
método dos maiores restos). Ex.: ocultando RG/Inscrição Estadual, CPF/CNPJ, Data de
nascimento e Gênero passam de 3 colunas cada para 4 colunas cada.

**Maiúsculas (2026-07-20, convenção de todo o projeto — ver spec §3.7):** todo campo de
texto livre deste formulário (nome, RG/IE, redes sociais, endereço, número, complemento,
bairro, cidade) é normalizado para **MAIÚSCULAS**, não importa o estado do teclado do
usuário — inclusive o endereço vindo do autopreenchimento por CEP (ViaCEP). Aplicado no
`onChange` de cada campo (feedback imediato) e reforçado no backend (defesa em
profundidade). **Única exceção: e-mail**, que mantém a caixa digitada pelo usuário. Campos
não-texto (select, data, checkbox, CPF/CNPJ/telefone/CEP mascarados como dígitos) não se
aplicam.

**Validação por campo (2026-07-21):** cada campo com regra própria (Nome, Categoria,
CPF/CNPJ, Data de nascimento, Gênero, E-mail, Celular, Id. WhatsApp, CEP) valida **ao sair do
campo** (`blur`) e **de novo no submit** — não só no submit. Mensagem de erro aparece embaixo
do campo específico (`.erro-campo`), não mais uma mensagem genérica só no rodapé. O CPF/CNPJ,
além do dígito verificador, também **verifica se já existe outro cliente com aquele
documento** ao sair do campo (reaproveita `GET /api/v1/clientes?cpfCnpj=...`, sem endpoint
novo; na edição ignora o próprio registro). Erros de submissão que não são de um campo
específico (ex.: falha de rede, conflito do servidor) aparecem como um **pop-up** no canto
superior direito (vermelho sólido, letras brancas — `web/src/components/Toast.tsx`), não mais
no rodapé da página.

**Validação replicada no backend (2026-07-21):** até então, formato de e-mail/celular/
WhatsApp/CEP e a obrigatoriedade configurável por tela (`cfg_tela_campo`) só eram checados no
frontend — `ClienteService.validar` (backend) só cobria gênero/data de nascimento/dígito
verificador de CPF-CNPJ. Corrigido: a API agora replica todas essas regras (defesa em
profundidade — nunca confiar só no cliente da API), consultando `ConfiguracaoTelaService`
para saber quais campos estão marcados obrigatórios no tenant atual.

**Tab pula o botão de categoria (2026-07-21):** "＋ Nova categoria" tem `tabIndex={-1}` — com
a categoria já escolhida, `Tab` vai direto da Categoria para o CPF/CNPJ.

**Cabeçalho enxuto, com Cancelar/Salvar no topo (2026-07-21):** o título deixou de variar
entre "Novo cliente"/"Editar cliente" (e o "CADASTROS" acima dele) — agora é só **"Cliente"**,
reduzindo a altura da faixa superior. Os botões **Cancelar**/**Salvar**, que ficavam num
rodapé no fim da página, subiram para essa faixa, ao lado dos ícones de ajuda/configuração.
Tecnicamente o botão Salvar (`type="submit"`) fica fora da árvore do `<form>` — associado via
atributo HTML `form="form-cliente"` — porque o cabeçalho é renderizado antes do formulário no
JSX; o `<form>` ganhou esse `id` só para viabilizar essa associação.

**Linha de identificação compacta (2026-07-21):** o checkbox "Cliente ativo" e os rádios
"Pessoa Física"/"Pessoa Jurídica" — antes em duas linhas — passaram a ficar lado a lado numa
única linha (`.identificacao-linha` em `web/src/styles.css`).

**Limite de crédito com máscara de moeda (2026-07-21):** o campo passou a mascarar a digitação
como dinheiro (mesma convenção de caixa eletrônico/app de banco — os dígitos digitados são
sempre os centavos, contados da direita para a esquerda; ex.: digitar "150000" mostra
"1.500,00"), em vez de aceitar texto livre. Novas funções `mascararMoeda`/`formatarMoeda`/
`desmascararMoeda` em `web/src/lib/masks.ts`; o valor existente (vindo da API como número) é
formatado para exibição com `formatarMoeda` ao abrir o formulário de edição.

## Categoria de cliente (`cfg_categoria_cliente`)

Tabela auxiliar simples: `id_categoria_cliente` PK, `nome_categoria` (único por tenant). Sem
seed padrão — o tenant nasce **sem nenhuma categoria**, então o primeiro cadastro de cliente
já precisa criar uma.

- No formulário de Cliente, o campo **Categoria** é um select carregado de
  `GET /api/v1/categorias-cliente`, com uma opção **"＋ Nova categoria"** que abre um modal.
- O modal de categoria é uma **mini-gestão** (criar **e** editar, decisão do dono do produto):
  lista as categorias já cadastradas do tenant com opção de renomear inline, mais um campo
  para adicionar uma nova. Não há exclusão de categoria nesta primeira versão (categoria em
  uso é protegida pela própria FK `cliente_categoria_fk`, sem `ON DELETE CASCADE`).
- Campo do modal: `nome_categoria` (texto, obrigatório, único por tenant — erro amigável se
  duplicar, mapeando a violação de `cfg_categoria_cliente_uk`).

## Tela de listagem

- **Colunas:** Nome/Razão Social, CPF/CNPJ, Categoria, Telefone/WhatsApp, Cidade/UF, Status
  (Ativo/Inativo).
- **Ordenação por coluna (2026-07-21):** qualquer cabeçalho é clicável e ordena ASC/DESC
  (clicar de novo inverte a direção); default é `nome` ASC, empate resolvido por
  `id_cliente`. Cabeçalho da grade em **destaque visual** (fundo diferenciado, borda inferior
  mais grossa) e cada coluna mostra um ícone **"⇅"** discreto indicando que é ordenável — a
  coluna ativa no momento troca para **"▲"/"▼"** em cor de destaque. Backend:
  `GET /api/v1/clientes?ordenarPor=&direcao=`, com allowlist de colunas (nunca concatena o
  parâmetro direto na SQL).
- **Busca:** por nome (contém, digitação sempre em maiúsculas) e por CPF/CNPJ (exato, ignorando
  máscara).
- **Filtros:** por categoria; por status (Ativo/Inativo/Todos — default mostra só Ativos).
- **Paginação:** por **número de página** (`GET /api/v1/clientes?pagina=&limite=`, `LIMIT`/
  `OFFSET` + contagem total no backend — não é cursor, para permitir pular direto para
  qualquer página). Navegação em **janela deslizante** (estilo inspirado no sistema legado):
  até 7 números centrados na página atual, mais os botões **« primeira** / **‹ anterior** /
  **próxima ›** / **última »** nas pontas (desabilitados quando não fazem sentido — ex.:
  "primeira"/"anterior" apagados na página 1). Clicar em um número, ou em "primeira"/"última",
  pula direto para aquela página. **Tamanho fixo em 50 itens por página** (2026-07-21, sem
  seletor — era 10/20/50 configurável, o dono do produto pediu para simplificar).
- **Layout fixo:** o cabeçalho da tela (título "Clientes" + barra de filtros) e o rodapé
  (contagem de clientes + paginação) não rolam, assim como o menu lateral do ERP; só a
  tabela tem scroll próprio (`.lista-tela`/`.lista-topo`/`.lista-corpo`/`.lista-rodape` em
  `web/src/styles.css`, convenção do shell reaproveitável em outras telas de listagem).
- **Grid compacta (2026-07-21):** espaçamento vertical entre linhas reduzido
  (`.table-compacta`, `padding: 6px 16px` em vez de `12px 16px`).
- **Ações por linha:** três ícones em vez de texto — **verde** (olho) para visualizar em modo
  somente-leitura (`/clientes/:id/visualizar`, reaproveita o `ClienteForm` com a prop
  `somenteLeitura` — todo o formulário vira um `<fieldset disabled>`, sem botão Salvar),
  **azul** (lápis) para editar, **vermelho** (lixeira) para excluir
  (`.acao-visualizar`/`.acao-editar`/`.acao-excluir` em `web/src/styles.css`, tokens de cor
  `--sucesso`/`--info`/`--danger`).

## Exclusão de cliente

Decisão do dono do produto: **tenta excluir de verdade; se houver vínculo (ex.: `venda`),
inativa em vez de excluir.**

- `DELETE /api/v1/clientes/{id}` **verifica antes** se existe `venda` vinculada
  (`SELECT EXISTS(...)`) — não tenta o `DELETE` e captura a violação de FK depois, porque no
  Postgres uma instrução que falha aborta o resto da transação (não dá pra simplesmente
  continuar com um `UPDATE` de fallback sem `SAVEPOINT`).
- Com vínculo: `UPDATE cliente SET ativo = false WHERE id_cliente = ?`, respondendo
  `200 OK` com `{"acao": "inativado", "motivo": "Cliente possui vendas associadas."}`.
- Sem vínculo: `DELETE` de verdade, respondendo `200 OK` com `{"acao": "excluido", "motivo":
  null}` (resposta sempre em JSON, sem variar entre `204`/`200` conforme o caso).
- Cliente inativo (`ativo=false`) não aparece na listagem padrão, mas pode ser reativado
  editando o registro (checkbox "Cliente ativo", agora o primeiro campo do formulário de
  edição — ver "Campos do formulário").

## Critérios de aceitação (viram testes)

- Dado um formulário de cliente Pessoa Física sem gênero, quando salvo, então a API rejeita
  com Problem Details (gênero é obrigatório para PF; data de nascimento não é).
- Dado um formulário de cliente Pessoa Física sem data de nascimento mas com gênero, quando
  salvo, então é aceito normalmente (2026-07-21 — nascimento é sempre opcional).
- Dado um formulário de cliente Pessoa Jurídica, quando salvo sem data de nascimento/gênero,
  então é aceito normalmente (campos não se aplicam a PJ).
- Dado uma data de nascimento igual a hoje ou no futuro, quando salvo, então a API rejeita.
- Dado um celular/Id. WhatsApp preenchido com menos de 11 dígitos ou 3º dígito diferente de
  9, quando o campo perde o foco, então mostra erro de formato.
- Dado um CPF com formato válido mas dígito verificador incorreto, quando salvo, então a API
  rejeita antes de chegar ao banco.
- Dado um cliente sem `id_categoria_cliente` meio de uma requisição, quando salvo, então é
  rejeitado (NOT NULL).
- Dado um cliente com vendas associadas, quando o usuário tenta excluir, então o cliente é
  inativado (não excluído) e a UI informa o motivo.
- Dado um cliente sem nenhum vínculo, quando excluído, então o registro deixa de existir.
- Dado dois clientes do mesmo tenant com o mesmo CPF, quando o segundo é salvo, então é
  rejeitado (único por tenant); CPFs vazios (NULL) não colidem entre si.
- Dado um nome de categoria já existente no tenant, quando criada de novo, então é rejeitada
  com erro amigável (não o erro cru de constraint).
- Dado um usuário `OPERADOR`, quando cadastra/edita/exclui um cliente, então a ação é
  permitida (cliente não é restrito a `ADMIN`).
- Dado um nome/endereço digitado em minúsculas (ou o endereço vindo do autopreenchimento de
  CEP), quando salvo, então o valor é armazenado em maiúsculas — exceto e-mail.
- Dado um complemento de endereço (ex.: "apto 12"), quando salvo, então é persistido em
  `cliente.complemento` e devolvido na resposta.
- [x] caso feliz  [x] erro  [x] borda (PF×PJ, CPF duplicado, vínculo em venda)  [x] o que
  NÃO deve acontecer (excluir cliente com venda associada sem inativar)

## Impacto no contrato de API

```
GET    /api/v1/clientes?nome=&cpf_cnpj=&id_categoria_cliente=&status=&pagina=&limite=&ordenarPor=&direcao=
                                               lista paginada (número de página), busca/filtro/ordenação
POST   /api/v1/clientes                      cria cliente
GET    /api/v1/clientes/{id}                 detalhe
PUT    /api/v1/clientes/{id}                 atualiza cliente
DELETE /api/v1/clientes/{id}                 exclui; fallback para inativar se houver vínculo

GET    /api/v1/categorias-cliente            lista categorias do tenant
POST   /api/v1/categorias-cliente            cria categoria
PUT    /api/v1/categorias-cliente/{id}       renomeia categoria
```

Todos sob `/api/v1/**` (JWT de tenant, RLS ativo — P8). Erros em Problem Details (RFC 9457).
OpenAPI a escrever antes da implementação (contrato faz parte da feature, P4).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `cadastros.cliente.lista`**
  - Objetivo: encontrar e gerenciar os clientes já cadastrados.
  - Passos: (1) use a busca por nome ou CPF/CNPJ; (2) filtre por categoria ou status; (3)
    clique em um cliente para editar, ou no ícone de lixeira para excluir/inativar.
  - Erros comuns: "não encontro um cliente" → verifique o filtro de status (pode estar
    inativo); "não consigo excluir" → o cliente tem vendas, foi inativado em vez de excluído.
  - `url_video`: `NULL` por ora (botão "Assistir vídeo" mostra "em breve").
- **`chave_tela`: `cadastros.cliente.form`**
  - Objetivo: cadastrar um cliente novo ou editar um existente.
  - Passos: (1) escolha Pessoa Física ou Jurídica; (2) preencha nome/razão social e escolha
    (ou crie) uma categoria; (3) CPF/CNPJ e demais dados são opcionais, mas recomendados;
    (4) digite o CEP para autopreencher o endereço; (5) salve.
  - Erros comuns: "categoria não aparece" → crie uma pela opção "+ Nova categoria"; "CPF
    inválido" → confira os dígitos, o sistema valida o dígito verificador.
  - `url_video`: `NULL` por ora ("em breve").
- **`chave_tela`: `cadastros.cliente.categoria`** (modal)
  - Objetivo: criar ou renomear categorias de cliente.
  - Passos: (1) digite o nome da nova categoria e salve; (2) para renomear, edite o nome de
    uma categoria da lista e salve.
  - `url_video`: `NULL` por ora ("em breve").

Conteúdo servido por `GET /api/v1/ajuda/{chave_tela}` (catálogo `ajuda_tela`, ainda a criar —
🔴 ver §3.3.10/§3.7.1 da spec; enquanto não existir, o front pode embutir esse texto como
fallback estático, mas a tela já nasce com o gatilho de ajuda presente).

## Impacto no banco

`cliente` e `cfg_categoria_cliente` já existiam (V016), com RLS ativo (V024). Nenhuma
migration **nova** — mas com o banco ainda em construção, a migration V016 foi **editada**
duas vezes, cada uma exigindo recriar o banco de dev do zero (`docker volume rm niner_pgdata`
+ `flyway migrate`) para aplicar a mudança a uma migration já rodada. Ver
`db/migration/README.md`.
- **2026-07-20:** adiciona `cliente.complemento` (`text`, nullable, entre `numero` e `bairro`).
- **2026-07-21:** relaxa `cliente_dados_pessoais_ck` — antes exigia `data_nascimento IS NOT
  NULL AND genero IS NOT NULL` para pessoa física, agora só exige `genero IS NOT NULL`
  (data de nascimento passou a ser sempre opcional).

## Impacto nas integrações

Nenhum. Cliente não se comunica com adapters de canal nesta fase (marketplaces não expõem
cadastro de cliente do ERP).

## Non-goals desta feature

- Crediário em si (parcelas, juros, cobrança) — `limite_credito` só é armazenado, ainda sem
  nenhuma regra de negócio ligada (Fase 2).
- Vínculo automático de cliente a pedidos de marketplace (comprador de canal fica em
  `pedido.comprador` JSONB, sem ligação com `cliente` nesta fase).
- Exclusão de categoria de cliente (só criar/renomear nesta versão).
- Importação de clientes via planilha.

## Questões abertas

Nenhuma bloqueante. Todas as decisões de UX/validação foram fechadas nesta sessão
(2026-07-20) com o dono do produto — ver tabela de campos e seções acima.

## Métrica de sucesso

Tempo de cadastro de um cliente novo no balcão (só nome + categoria) em menos de 15 segundos
(CPF/CNPJ e demais dados opcionais não bloqueiam o fluxo rápido).
