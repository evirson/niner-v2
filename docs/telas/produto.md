# Spec: Cadastro de Produto                            Status: Implementada
Autor: Claudio Calixto (dono do produto) · Data: 2026-07-22 · Módulo(s): `catalogo` (produto, categoria, NCM) · Fase: 1 — Núcleo do ERP

## Problema

O ERP precisa de um catálogo de produtos central (P1 — fonte única de verdade de estoque e
preço) antes de qualquer módulo de estoque/pedido/canal poder existir. A tabela `produto`
existia no banco desde V017, mas sem endpoint/UI. Esta é a quinta tela de domínio e o primeiro
corte vertical do núcleo do produto (catálogo), distinta das quatro anteriores por ter uma
relação N:N com ordenação (categorias) e campos cuja visibilidade depende de configuração de
**outra** tela (Parâmetros do Sistema).

## Solução proposta

Quinta tela de domínio, no mesmo padrão consolidado por Cliente/Funcionário/Plano de
Contas/Fornecedor (`docs/telas/cliente.md`): paginação por página, ordenação por coluna, três
ícones de ação, modo somente-leitura, configuração de campos por tenant, `InfoRegistro`. As
particularidades ficam por conta de três mecanismos novos: categorias múltiplas ordenadas,
nome de variante condicionado aos Parâmetros do Sistema, e uma tabela de referência global
(NCM) sem tela própria. Papéis `ADMIN` e `OPERADOR` têm acesso completo (R8 não se aplica).

**Variação (SKU/`produto_barra`) fica fora desta spec** — o cadastro cobre só o produto "pai"; a
matriz de variações é o próximo corte vertical (junto de Estoque). ~~E imagens
(`produto_imagem`)~~ — **superado em 2026-07-23**: a **galeria de imagens** entrou no próprio
formulário de produto (`web/src/components/GaleriaImagensProduto.tsx`,
`api/.../catalogo/produto/ProdutoImagemController.java:18-38`), com upload multipart para object
storage; ver `docs/infra/armazenamento-imagens.md`.

## Particularidade 1: categorias — N:N ordenada, gerida embutida

Um produto pode ter **N categorias**, numa ordem escolhida pelo usuário (não alfabética nem por
ID). O modelo:

- `cfg_categoria_produto(id_categoria PK, nome_categoria)` — catálogo de categorias do tenant,
  igual em espírito a `cfg_categoria_cliente`: CRUD embutido (criar/listar/renomear, **sem**
  exclusão — uma categoria em uso é protegida pela própria FK), sem tela própria, via modal
  "＋ Gerenciar categorias" (`CategoriaProdutoModal.tsx`) no formulário de produto.
- `produto_categoria(id_produto FK, id_categoria FK, id_tenant, indice SMALLINT, PK(id_produto,
  id_categoria), UNIQUE(id_tenant, id_produto, indice))` — a tabela de ligação. `indice`
  controla a ordem de exibição (menor primeiro), mesmo padrão já usado em
  `produto_imagem.indice` para a galeria.
- **Contrato:** o request de produto (`POST/PUT /api/v1/produtos`) recebe `categorias` como uma
  lista de `idCategoria` **na ordem escolhida pelo usuário** — o servidor deriva o `indice` da
  posição na lista (0, 1, 2…); o cliente da API nunca escolhe números de índice. A cada
  criação/atualização, o servidor apaga todas as linhas de `produto_categoria` daquele produto
  e reinsere na ordem recebida (substituição total, não *diff*).
- **UI:** lista das categorias escolhidas com setas ▲/▼ para reordenar e um ✕ para remover; um
  seletor "Adicionar categoria" (só mostra categorias ainda não escolhidas) + botão "＋
  Adicionar"; botão separado "＋ Gerenciar categorias" abre o modal de CRUD completo.
- Categoria duplicada na lista, ou uma categoria que não existe (mais provável: removida do
  cache do navegador depois de excluída em outra aba) → 400 do servidor, não 500.

## Particularidade 2: cor + grade — condicionado aos Parâmetros do Sistema (revisado 2026-08-08)

**Substituiu o modelo anterior de "variante em linha/coluna" genérica.** O par
`nome_variante_linha`/`nome_variante_coluna` (rótulo livre por produto, ex.: "Cor"/"Tamanho" mas
também podia ser qualquer outra coisa) resolvia mal o caso real do dono do produto: segmentos como
calçados/confecções variam por **cor × grade** (uma curva de numeração nomeada e ORDENADA, ex.
"Grade 36-44" = tamanhos 36,37,38…44 em ordem, não uma lista solta), enquanto segmentos como
utilidades domésticas/brinquedos/cosméticos/óticas normalmente não variam (1 produto = 1
SKU). Variantes de voltagem (110V/220V) ficaram **fora de escopo** — como o preço muda entre elas,
viram produtos separados, não uma variação do mesmo produto.

Modelo novo:

- `cfg_cor(id_cor, id_tenant, descricao)` / `cfg_tamanho(id_tamanho, id_tenant, descricao)` —
  catálogos simples do tenant (nome livre, ex. "AZUL", "38"). `cfg_tamanho` ainda **não tem tela
  de cadastro própria** — nasce embutida no popup "＋ Gerenciar Grades" do próprio formulário de
  Produto (ver abaixo); `cfg_cor` nasce embutida na Emissão de Etiqueta
  (`docs/telas/etiqueta-emissao.md`) como válvula de escape **temporária**, até a tela de Entrada
  de Produtos (onde cor nasceria na prática, na compra) existir.
- `cfg_grade(id_grade, id_tenant, descricao, id_tamanho1..id_tamanho20)` — uma grade é uma
  **curva nomeada e ordenada** de até 20 tamanhos (20 colunas fixas com FK para `cfg_tamanho`,
  nullable — não uma tabela de associação N:N solta, porque a ORDEM importa e é pequena/estável o
  suficiente pra não justificar uma tabela filha). Mantida via popup "＋ Gerenciar Grades"
  (`GradeModal.tsx`) no formulário de Produto: lista/cria/edita grades, com a lista de tamanhos
  reordenável (▲/▼) e um "+ Novo tamanho" inline.
- `produto.id_grade` (FK `cfg_grade`) substitui `nome_variante_linha`/`nome_variante_coluna` — um
  produto usa variação de verdade se, e só se, tiver uma grade **diferente da PADRÃO** escolhida
  (ver nota abaixo).
- `produto_barra.id_cor`/`id_tamanho` (FKs) substituem `id_variante_linha`/`id_variante_coluna`;
  `produto_barra_variacao_uk` virou `UNIQUE(id_produto, id_cor, id_tamanho)`.

> **Cor/Tamanho/Grade "PADRÃO", sentinela código 1 (2026-08-13).** `cfg_cor`/`cfg_tamanho`/
> `cfg_grade` deixaram de ser nullable em `produto`/`produto_barra` — todo tenant nasce (via
> `SignupService`) com uma linha `id_cor=1` (nome vazio), `id_tamanho=1` (nome "UN") e
> `id_grade=1` (descrição "PADRÃO", único tamanho "UN"), e `produto.id_grade`/
> `produto_barra.id_cor`/`id_tamanho` são `NOT NULL DEFAULT 1`. Quando o tenant não usa cor/grade
> (`cfg_geral.cfg_usa_cor_grade = false`), TODO produto/variação grava esse `1` internamente —
> nunca `null` — mas nenhuma tela mostra ou menciona essas linhas (listagens/JOINs excluem
> `id=1`, a API traduz `1` de volta pra `null` na resposta). Motivo: se o tenant ligar cor/grade
> no futuro, o dado já está estruturalmente consistente, sem migração. `id_cor`/`id_tamanho`/
> `id_grade` são calculados em Java por tenant (`COALESCE(MAX(id)+1, 1)`), não mais
> `GENERATED ALWAYS AS IDENTITY` — a PK de cada tabela virou composta `(id_tenant, id_col)`. Ver
> `[[project_cor_grade_tamanho]]` na memória.
- **Cor é obrigatória sempre que o produto tem grade** (decisão do dono do produto, confirmada
  explicitamente) — diferente de tamanho, que é restrito às opções da grade do produto mas
  igualmente obrigatório quando há grade. Regra e mensagens em
  `ProdutoBarraService.validarObrigatoriedade` (também usada aqui indiretamente — é o mesmo
  service que a Emissão de Etiqueta consome).
- `cfg_geral.cfg_usa_cor_grade` (renomeado de `cfg_usa_variante_linha`/`cfg_usa_variante_coluna`
  — os dois viraram um único checkbox, já que a decisão passou a ser binária: usa cor+grade, ou
  não usa nada) controla a **visibilidade do campo Grade** no formulário de Produto **e também a
  obrigatoriedade**: com o flag ligado o backend rejeita salvar produto sem `idGrade` ("Grade é
  obrigatória para este tenant.", `api/src/main/java/com/vetor/niner/catalogo/ProdutoService.java:279-281`).
  Na variação a obrigatoriedade é por produto: se o produto tem grade, cor+tamanho são
  obrigatórios; sem grade, são forçados à **sentinela `1`** (não a `null` — as colunas são
  `NOT NULL DEFAULT 1`, ver `ProdutoBarraService.java:97-99` e a nota da sentinela PADRÃO acima).
  Endpoint aberto a qualquer papel:
  `GET /api/v1/config-geral/usa-cor-grade` → `{usaCorGrade}`.
- Geração em lote de todas as combinações cor×grade de uma vez (`Entrada de Produtos`) e uma tela
  de cadastro própria pra `cfg_cor` ficaram **deliberadamente fora desta rodada** — vão nascer
  junto da tela de Entrada de Produtos (individual ou por XML), ainda não construída.

## Particularidade 3: NCM — referência global, sem tela de manutenção

`cfg_produto_ncm(codigo_ncm PK text, descricao_ncm NOT NULL, alq_federal_nacional NUMERIC(10,2),
alq_federal_importado NUMERIC(10,2), alq_estadual NUMERIC(10,2), alq_municipal NUMERIC(10,2))`
(alíquotas IBPT desde 2026-08-19, ver `docs/PROGRESSO.md` e `docs/MODULOFISCAL.md` §8.6) é a uma
das **quatro tabelas do domínio sem `id_tenant`/RLS** (mesma exceção de `plataforma.*`, P9,
só que fora daquele schema) — o código NCM (Nomenclatura Comum do Mercosul) é igual para
qualquer tenant. As outras três, pelo mesmo motivo (dado global, não do lojista), são:
`cfg_ean_gerador` (`db/migration/V017__catalogo.sql:135` — sequencial de código de barras por
**instância de banco**, ver `gerar_ean13_interno()`), `cfg_plano_contas_padrao`
(`db/migration/V016__cadastros.sql:331` — modelo de plano de contas copiado no signup) e
`cfg_banco` (`db/migration/V028__financeiro_conta_corrente.sql:13` — códigos FEBRABAN, ver
`docs/telas/conta-corrente.md`). São essas quatro e mais nenhuma: a lista pode ser reconferida
procurando `CREATE TABLE` sem `id_tenant` fora do schema `plataforma`.
**Sem tela de manutenção por decisão explícita**: carregada/atualizada por script
(`db/scripts/seed_cfg_produto_ncm.sql`, rodando como `niner_owner` — único grant de escrita;
`niner_app` só tem `SELECT`).

- `produto.codigo_ncm` (nullable) ganhou `REFERENCES cfg_produto_ncm (codigo_ncm)` — FK simples
  (o alvo não tem `id_tenant`, diferente do resto do domínio).
- **Campo do formulário:** rótulo "NCM - Nomenclatura Comum do Mercosul"; entrada mascarada
  `9999.99.99` (8 dígitos), campo estreito e proporcional ao conteúdo, ao lado de Referência.
  Ao sair do campo (`onBlur`), busca `GET /api/v1/ncm/{codigo}` e mostra a descrição num campo
  somente-leitura ao lado. Código que não existe: **limpa o campo e avisa** ("Código NCM
  inválido — não encontrado."), em vez de deixar um código inválido no formulário.
- **Pesquisa por nome (`PesquisaNcmModal.tsx`, 2026-08-13)** — quem cadastra um produto nem sempre
  sabe o código de cabeça: ao lado do campo há uma lupa que abre o modal, onde se digita parte do
  nome da mercadoria (mínimo 3 caracteres, busca ao vivo em `GET /api/v1/ncm?busca=`) e se clica
  na linha pra usar o código. Mesmo padrão do `PesquisaProdutoModal` do PDV, e reusado tanto por
  esta tela quanto pelo cadastro rápido de produto embutido na Entrada de Produtos.
- NCM inexistente ao salvar o produto → 400 ("NCM informado não existe."), não 500 — mesmo
  princípio de tradução de violação de FK já usado para categoria (`ClienteService.duplicidade`).

## Particularidade 4: preço de venda calculado automaticamente

Regra de precificação (varejo): o preço de venda é derivado do custo + markup, mas também pode
ser editado direto (com o markup recalculado de volta) — cálculo **ao vivo**, a cada tecla, só
no frontend (o backend não recalcula, apenas persiste os três valores enviados):

- Editar **Preço de Custo** ou **% de Venda** → recalcula `Preço de Venda = Custo × (1 + %/100)`
  (só quando há custo informado > 0; sem custo, não há base para calcular).
- Editar **Preço de Venda** direto → recalcula `% de Venda = ((Venda − Custo) / Custo) × 100`
  (só quando há custo informado > 0).

**Piso do preço de venda (2026-08-12, regra do projeto inteiro):** o preço de venda **nunca pode
ficar abaixo do preço de custo** — salvar com `precoVenda < precoCusto` devolve 400 ("Preço de
venda não pode ser menor que o preço de custo."). Igual é aceito (margem zero). A checagem existe
nos dois lados: bloqueia no cliente (`ProdutoForm.tsx` e `ProdutoQuickCreateModal.tsx`) e é
reforçada como defesa em profundidade no servidor
(`ProdutoService.validarPrecos()`, `api/src/main/java/com/vetor/niner/catalogo/ProdutoService.java:296-305`),
valendo portanto também para o cadastro rápido embutido em outras telas e para qualquer chamada
direta à API.

**Bug corrigido em 2026-08-05:** `percentualVenda` tinha `@DecimalMax("100")` no backend
(`ProdutoDtos.java`), rejeitando qualquer margem acima de 100% (venda > 2× o custo) com um erro
genérico do Spring ("Invalid request content.", a mesma mensagem de JSON malformado — por isso
difícil de diagnosticar; não tem `detail` por campo nesse tipo de falha de validação). Confirmado
com o dono do produto que margem acima de 100% é um caso de uso real (ex.: calçados com markup de
150%); a restrição de `@DecimalMax` foi removida — a coluna já é `numeric(5,2)` (até 999,99%), sem
necessidade de mudança de schema.

## Particularidade 5: regra da oferta (início/final/preço) — tudo ou nada

`data_inicio_oferta`, `data_final_oferta` e `preco_oferta` só são válidos **em conjunto**:

1. Preencheu um dos três → os três viram obrigatórios ("Obrigatório para a oferta ser
   válida.").
2. Início da oferta não pode ser no passado (comparado à data de hoje, fuso local).
3. Final da oferta não pode ser anterior ao início.
4. Preço de oferta tem que ser **menor que** o preço de venda (não `<=`).

Validado no frontend (`ProdutoForm.tsx#errosOferta`, ao vivo por campo) **e** reforçado no
backend (`ProdutoService.validarOferta`, 400 com mensagem específica por regra) — defesa em
profundidade, igual ao resto do sistema.

## Particularidade 6: peso bruto/líquido — peso líquido ≤ peso bruto

`peso_bruto`/`peso_liquido` (`numeric(14,3)`, 3 casas — diferente de moeda/percentual, que têm
2) usam a mesma digitação natural (§3.7) com a máscara própria (`mascararPeso`/`completarPeso`).
Regra de negócio: **peso líquido não pode ser maior que o peso bruto** — validado ao vivo (ao
sair de qualquer um dos dois campos) e reforçado no backend (400).

## Particularidade 7: "O que é este item?" é o PRIMEIRO campo (2026-08-31)

Pedido do dono do produto: *"deixe o campo 'O Que é este Item?' como primeiro campo da sequência
para digitação, e só deixar este campo ativo se o módulo de serviço estiver ativo"*.

⭐ **Por que ele vem antes de tudo:** o tipo **governa o resto do formulário**. Serviço não tem
estoque, não tem grade, não sai em etiqueta, não entra na nota de mercadoria e — desde esta data —
**não pede foto**. Perguntar isso depois da descrição fazia o operador preencher campos que iam
sumir da tela.

**A seção inteira só existe com `cfg_usa_servicos` ligado.** Numa loja de calçados, "O que é este
item?" é uma pergunta que ela nunca precisa responder — é a mesma regra do `filtrarPorModulo` no
menu: esconder o que não faz sentido para o tenant, em vez de mostrar desabilitado.

⚠️ **O foco não é `autoFocus`, e há um motivo:** `cfgUsaServicos` chega por consulta, e no primeiro
render ainda é `undefined` — a seção nem existe no DOM. Como `autoFocus` só age na montagem, o
campo nasceria **sem foco justamente na loja que usa serviços**. Um efeito espera a resposta e move
o foco, com duas guardas: **age uma vez só** e **só se o foco ainda estiver onde a montagem o
deixou** (o `body` ou a Descrição ainda vazia) — efeito de foco que redispara é o defeito que já
apagou a contagem digitada do Fechamento de Caixa.

⚠️ **Ao editar, o foco continua na Descrição:** o tipo é imutável depois de criado (trigger da
V085), o `<select>` está `disabled`, e focar campo desabilitado não funciona.

## Particularidade 8: serviço não pede foto (2026-08-31)

A galeria (`GaleriaImagensProduto`) **não é renderizada** quando `tipoItem === 'SERVICO'`. Foto de
produto existe para o PDV, a etiqueta e a vitrine — nenhum dos três alcança mão de obra.

⚠️ **Esconder a galeria não bastava.** Em produto **novo**, o upload acontece **depois** do POST,
num laço sobre os arquivos escolhidos: quem selecionasse fotos e **só então** marcasse "Serviço"
teria as fotos enviadas assim mesmo, com a galeria escondida e ninguém vendo. Por isso a troca de
tipo **descarta os arquivos locais** (`trocarTipoItem`).

⛔ **E a trava que vale é a do servidor (P4):** `ProdutoImagemService.adicionar` recusa foto em item
`SERVICO` — sem isso o endpoint continuaria aceitando por qualquer outro caminho, e o produto
ficaria com uma vitrine que nenhuma tela mostra.
⭐ **Só o CRIAR é travado, nunca o desfazer:** `remover` e `reordenar` seguem livres, porque um
serviço com foto de antes desta regra precisa poder se livrar dela — a mesma direção decidida
quando o módulo de serviços passou a barrar a **criação** de OS, e não o cancelamento.

Prende a regra `ProdutoImagemCrudTest.servicoNaoAceitaFoto`, com o **par positivo** (a mesma
chamada numa mercadoria continua 201) e verificado por sabotagem: sem a trava, responde 201.

## Particularidade 9: serviço não tem sete campos da mercadoria (2026-08-31)

Pedido do dono do produto, item a item: no cadastro classificado como **serviço** não há **Marca**,
**Referência**, **NCM**, **Início da oferta**, **Final da oferta**, **Preço de oferta** nem
**Categorias**. A tela some com os sete.

**Por que o NCM sai:** a NCM classifica **mercadoria** (é o código do Mercosul para bens); serviço
não tem uma. Categoria é vitrine de produto; oferta é promoção de preço de mercadoria; marca e
referência são do fabricante.

⏭️ **E o que entra no lugar do NCM ainda não está decidido — de propósito.** O classificador do
serviço (NBS, ou o que a nota exigir) **sai junto com a NFS-e**, que está sendo construída em
paralelo: decisão do dono do produto em 2026-08-31, para o campo não nascer definido em dois
lugares e as duas definições divergirem depois. Por ora o serviço simplesmente **não tem
classificador na tela**.

### Onde a regra fica travada — três camadas, e cada uma por um motivo

1. **A tela esconde** os sete campos quando `tipoItem === 'SERVICO'`.
2. **`paraRequisicao` anula** os sete no corpo enviado. ⚠️ Esconder não bastava: os valores
   continuam no estado do formulário, e quem preenchesse descrição, marca e NCM e **só então**
   marcasse "Serviço" mandaria tudo assim mesmo — com os campos invisíveis e ninguém vendo. É a
   mesma armadilha das fotos, que seriam enviadas depois do POST.
   (`trocarTipoItem` também limpa o estado, mas por outro motivo: para a tela não guardar dado
   fantasma que reaparece se o operador voltar para "Mercadoria".)
3. **O servidor recusa**, nomeando os campos que vieram (P4 — a trava que vale é a de lá).
   ⭐ Recusar em vez de limpar em silêncio é deliberado: o silêncio faria o integrador achar que
   gravou.

### ⚠️ A metade menos óbvia, e a que mais doeria

Os `exigirSeObrigatorio` de `cfg_tela_campo` **não valem para serviço**. Num tenant que marcou
"Marca" como **obrigatória** (comum numa loja de calçados que também virou petshop), exigir marca
de um serviço recusaria o cadastro com *"Campo obrigatório"* apontando para um campo que a tela
**não mostra** — o operador leria a mensagem, procuraria o campo e não acharia. A configuração por
tenant é da tela de **mercadoria**.

Preso por `servicoCadastraMesmoComMarcaObrigatoriaNoTenant`, que traz o par: a **mercadoria** sem
marca continua sendo recusada — sem isso o teste passaria mesmo que a configuração não tivesse
pegado.


## Campos do formulário

Tabela `produto` (V017). **Foco automático** em "O que é este item?" quando o módulo de serviços
está ligado e o cadastro é novo; na Descrição nos demais casos (ver Particularidade 7). Layout:
"Tipo de Item" é a primeira seção (só com o módulo ligado); "Produto ativo" em
linha própria; Descrição + Marca na mesma linha; Referência + NCM (estreito) + Descrição do NCM
na mesma linha; os 6 campos de Preços (Custo, % Venda, Venda, Início/Final da oferta, Preço de
Oferta) numa única linha que se reajusta quando os opcionais estão ocultos; Dimensões e
Variantes (select de Grade + botão "＋ Gerenciar Grades", Peso Bruto/Líquido) numa única linha.

| Campo (banco) | Rótulo na tela | Componente | Obrigatório | Regra |
|---|---|---|---|---|
| `descricao` | Descrição | texto | **Sim** (NOT NULL) | MAIÚSCULAS |
| `preco_custo` | Preço de Custo (R$) | moeda | **Sim** (estrutural, não configurável) | Digitação natural (§3.7); recalcula preço de venda |
| `percentual_venda` | % de Venda | percentual | **Sim** (estrutural) | Digitação natural; recalcula preço de venda |
| `preco_venda` | Preço de Venda (R$) | moeda | **Sim** (estrutural) | Digitação natural; recalcula % de venda ao editar direto |
| `marca` | Marca | texto | Configurável | MAIÚSCULAS |
| `referencia` | Referência | texto | Configurável | MAIÚSCULAS |
| `codigo_ncm` | NCM - Nomenclatura Comum do Mercosul | texto mascarado `9999.99.99` | Configurável | FK para `cfg_produto_ncm`; busca descrição ao sair do campo |
| `data_inicio_oferta` | Início da oferta | texto mascarado `dd/mm/aaaa` | Condicional (regra da oferta) | Não pode ser no passado |
| `data_final_oferta` | Final da oferta | texto mascarado `dd/mm/aaaa` | Condicional (regra da oferta) | Não pode ser anterior ao início |
| `preco_oferta` | Preço de Oferta (R$) | moeda | Condicional (regra da oferta) | Menor que o preço de venda |
| `peso_bruto` | Peso Bruto (kg) | peso (3 casas) | Configurável | ≥ peso líquido |
| `peso_liquido` | Peso Líquido (kg) | peso (3 casas) | Configurável | ≤ peso bruto |
| `id_grade` | Grade | select + "＋ Gerenciar Grades" | Não configurável (controlado por `cfg_geral`) | Só aparece se `cfg_usa_cor_grade`; FK `cfg_grade` |
| `ativo` | Produto ativo | checkbox | — | Ativo ao criar por padrão |
| `tipo_item` | O que é este item? | select | — | **Primeiro campo da digitação**; só existe com `cfg_usa_servicos`; imutável depois de criado (V085). Ver Particularidade 7 |
| `duracao_minutos` | Duração (minutos) | inteiro | Não | Só com `tipo_item = SERVICO`; é **estimativa**, não agenda |
| `perc_comissao_servico` | % Comissão do Serviço | percentual | Não | Só com `SERVICO`; em branco vale a comissão do funcionário |
| *(N:N)* `produto_categoria` | Categorias | lista ordenável + seletor | Não | Ver Particularidade 1 |

`marca`, `referencia`, `codigoNcm`, `pesoBruto`, `pesoLiquido`, `dataInicioOferta`,
`dataFinalOferta`, `precoOferta` são **configuráveis por tenant** (`cfg_tela_campo`, chave
`catalogo.produto.form`) — visibilidade e obrigatoriedade, com tela de configuração própria
(`ADMIN`, ícone ⚙). A obrigatoriedade da regra da oferta **substitui** (é mais específica que) a
checagem genérica de `cfg_tela_campo` para os 3 campos de oferta.

## Tela de listagem

- **Colunas:** Descrição, Marca, Referência, Preço de Venda, Status — ordenáveis (allowlist no
  backend); mais a coluna **Categorias** (nomes concatenados, só leitura, sem ordenação).
  Ordenação default: Descrição ASC.
- **Busca** por descrição (maiúsculas) e **filtro por categoria** (select) e **status**
  (Ativos/Inativos/Todos).
- Paginação em janela deslizante (50 fixos), layout fixo, três ícones de ação — idêntico ao
  padrão (`docs/telas/cliente.md`).
- **Botão de fechar (✕)** no cabeçalho da listagem e do formulário (`BotaoFecharTela`,
  `navigate(-1)` — histórico real, nunca rota fixa; convenção de todo o sistema).

## Exclusão de produto

Segue o padrão de Cliente/Funcionário/Fornecedor (**com** fallback): se o produto tiver
variação (`produto_barra`) ou imagem (`produto_imagem`) vinculada, o DELETE **inativa**
(`ativo = false`) em vez de apagar. Sem vínculo, apaga de verdade — e as linhas de
`produto_categoria` são sempre apagadas junto (a relação só existe por causa do produto).

## Critérios de aceitação (viram testes)

- Dado descrição e preços válidos, quando salvo, então o produto é criado com descrição em
  MAIÚSCULAS.
- Dado uma lista de categorias, quando salvo, então `produto_categoria.indice` reflete a ordem
  enviada; reordenar e salvar de novo atualiza os índices.
- Dado um `idCategoria` inexistente ou duplicado na lista, quando salvo, então 400.
- Dado um NCM cadastrado, quando digitado e o campo perde o foco, então a descrição aparece;
  dado um NCM não cadastrado, então o campo é limpo e um aviso aparece.
- Dado um NCM inexistente no payload de salvar, então 400 ("NCM informado não existe.").
- Dado custo e % de venda, quando editados, então o preço de venda recalcula ao vivo; dado o
  preço de venda editado direto (com custo informado), então o % de venda recalcula.
- Dado só um dos três campos de oferta preenchido, quando salvo, então 400 ("Obrigatório para
  a oferta ser válida.").
- Dado início da oferta no passado, quando salvo, então 400.
- Dado final da oferta anterior ao início, quando salvo, então 400.
- Dado preço de oferta maior ou igual ao preço de venda, quando salvo, então 400.
- Dado peso líquido maior que peso bruto, quando salvo, então 400; igual é aceito.
- Dado `cfg_geral.cfg_usa_cor_grade = false`, quando um `idGrade` é enviado, então o servidor
  ignora (não rejeita) e grava a **sentinela `1`** (grade PADRÃO) — `produto.id_grade` é
  `NOT NULL DEFAULT 1`, nunca `null` no banco
  (`api/src/main/java/com/vetor/niner/catalogo/ProdutoService.java:379`). O `null` só aparece na
  **resposta** da API, onde a sentinela é traduzida de volta (`ProdutoService.java:429`) — a tela
  nunca vê nem mostra a grade PADRÃO.
- Dado `ordenarPor`/`direcao`, então a listagem respeita a coluna e direção pedidas.
- Dado um produto sem vínculo, quando excluído, então deixa de existir.
- Dado um produto vinculado a uma variação, quando excluído, então é inativado, não apagado.

Cobertos por `ProdutoCrudTest` (25 testes) — suíte completa do projeto em **500/500 verdes
(2026-08-14)** (inclui `CorGradeTamanhoCrudTest`, novo em 2026-08-08).

## Impacto no contrato de API

```
GET    /api/v1/produtos?descricao=&marca=&idCategoria=&status=&pagina=&limite=&ordenarPor=&direcao=
POST   /api/v1/produtos                        cria produto (+ categorias, na ordem enviada)
GET    /api/v1/produtos/marcas                 lista as marcas já usadas (datalist do formulário)
GET    /api/v1/produtos/{id}                   detalhe (categorias ordenadas por indice)
PUT    /api/v1/produtos/{id}                   atualiza (substitui a lista de categorias)
DELETE /api/v1/produtos/{id}                   exclui ou inativa (fallback com vínculo)

POST   /api/v1/produtos/{idProduto}/variacoes  cria (ou reaproveita) a variação cor×tamanho e
                                               gera o EAN interno — 201

POST   /api/v1/produtos/{idProduto}/imagens          multipart (`arquivo`) — 201, devolve a
                                                     galeria já reordenada
DELETE /api/v1/produtos/{idProduto}/imagens/{idImagem}  devolve a galeria restante
PUT    /api/v1/produtos/{idProduto}/imagens/ordem       reordena (`idsImagem` na ordem desejada)

GET    /api/v1/categorias-produto              lista
POST   /api/v1/categorias-produto              cria
PUT    /api/v1/categorias-produto/{id}         renomeia

GET    /api/v1/ncm?busca=                      busca por descrição (`PesquisaNcmModal`)
GET    /api/v1/ncm/{codigo}                    consulta (404 se não cadastrado) — só leitura

GET    /api/v1/config-geral/usa-cor-grade      {usaCorGrade} — qualquer papel

GET    /api/v1/cores                           lista cfg_cor do tenant
POST   /api/v1/cores                           cria
PUT    /api/v1/cores/{id}                      renomeia

GET    /api/v1/tamanhos                        lista cfg_tamanho do tenant
POST   /api/v1/tamanhos                        cria
PUT    /api/v1/tamanhos/{id}                   renomeia

GET    /api/v1/grades                          lista cfg_grade do tenant (com tamanhos ordenados)
GET    /api/v1/grades/{id}                     detalhe
POST   /api/v1/grades                          cria (descricao + até 20 idsTamanho ordenados)
PUT    /api/v1/grades/{id}                     atualiza
```

Todos sob `/api/v1/**` (JWT de tenant, RLS ativo — P8, exceto `cfg_produto_ncm`/NCM que é
global). Erros em Problem Details (RFC 9457).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `catalogo.produto.lista`** — busca por descrição; filtro por categoria e
  status; ícones de visualizar/editar/excluir; erro comum: exclusão vira inativação quando há
  variação/imagem vinculada. `url_video`: NULL.
- **`chave_tela`: `catalogo.produto.form`** — descrição e preços obrigatórios; categorias
  múltiplas com ordem; grade (cor+tamanho) controlada pelos Parâmetros do Sistema, com "＋
  Gerenciar Grades" embutido; NCM com busca automática de descrição; regra da oferta (tudo ou
  nada); peso líquido ≤ peso bruto. `url_video`: NULL.

## Impacto no banco

`produto`, `produto_categoria`, `cfg_categoria_produto` já existiam desde V016/V017 (RLS via
V024). Alterações feitas **dentro da própria V017** (banco ainda em construção, sem migration
nova — ver `docs/PROGRESSO.md`):
- `produto_categoria.indice SMALLINT NOT NULL DEFAULT 0` + `UNIQUE(id_tenant, id_produto, indice)`.
- Nova tabela `cfg_produto_ncm` (global, sem RLS) + `produto.codigo_ncm` ganhou
  `REFERENCES cfg_produto_ncm (codigo_ncm)`.
- **2026-08-08 (dentro da própria V017, banco recriado):** `cfg_variante_linha`/
  `cfg_variante_coluna` → `cfg_cor(id_cor, id_tenant, descricao)` /
  `cfg_tamanho(id_tamanho, id_tenant, descricao)`; nova `cfg_grade(id_grade, id_tenant,
  descricao, id_tamanho1..id_tamanho20)` (20 colunas fixas nullable, FK cada uma pra
  `cfg_tamanho`); `produto.nome_variante_linha`/`nome_variante_coluna` → `produto.id_grade` (FK
  `cfg_grade`); `produto_barra.id_variante_linha`/`id_variante_coluna` → `id_cor`/`id_tamanho`
  (FKs `cfg_cor`/`cfg_tamanho`); `produto_barra_variacao_uk` virou `UNIQUE(id_produto, id_cor,
  id_tamanho)`. `cfg_geral.cfg_usa_variante_linha`/`cfg_usa_variante_coluna` (V023) viraram um
  único `cfg_usa_cor_grade boolean NOT NULL DEFAULT false`. RLS (V024): tabela array trocou
  `cfg_variante_linha`/`cfg_variante_coluna` por `cfg_cor`/`cfg_tamanho`/`cfg_grade`.

## Impacto nas integrações

Nenhuma ainda — a publicação em canal (Mercado Livre/Shopee) depende da variação (`produto_barra`,
próximo corte), não do produto "pai".

## Non-goals desta feature

- Variação/SKU (`produto_barra`) ~~e galeria de imagens (`produto_imagem`)~~ — schema pronto desde
  V017, sem CRUD ainda; ficam para o próximo corte vertical (Estoque). **Superado para as imagens
  em 2026-07-23:** a galeria tem CRUD completo — `ProdutoImagemController.java:18-38` (listar,
  upload multipart, excluir) + `web/src/components/GaleriaImagensProduto.tsx` (miniaturas,
  lightbox, confirmação ao excluir), embutida no formulário desta tela. **Atualizado 2026-08-05:**
  nasceu `ProdutoBarraService` (`com.vetor.niner.catalogo`, `obterOuCriar`), mas é infraestrutura
  de domínio consumida pela tela **Emissão de Etiqueta de Produtos**
  (`docs/telas/etiqueta-emissao.md`) — acha ou cria a variação (chamando
  `gerar_ean13_interno()`) na hora de emitir uma etiqueta; continua **sem tela própria** de
  listar/editar/excluir variação, e o cadastro de Produto em si segue sem tocar em
  `produto_barra`.
- **Entrada de Produtos** (individual ou por XML) — onde `cfg_cor` nasceria naturalmente (na
  compra) e onde faria sentido gerar em lote todas as combinações cor×grade de um produto de
  uma vez. Adiada de propósito (2026-08-08): a geração em lote **não** entrou nesta rodada;
  `cfg_cor` usa a Emissão de Etiqueta como válvula de escape até lá.
- Tela de cadastro dedicada para `cfg_cor` — nasce embutida (Emissão de Etiqueta), sem tela
  própria, pelo mesmo motivo acima.
- Reajuste em massa de preços e histórico de preço (a tela não os oferece). ~~(`reajustado_em`
  existe na coluna, sem uso ainda)~~ — **superado**: a coluna é escrita hoje sempre que o custo/preço
  muda — na **Entrada de Produtos por Compra** (`EntradaMercadoriaService.java:181`) e no próprio
  cadastro (`ProdutoService.java:404,436`).
- Importação em lote (planilha).

## Questões abertas

Nenhuma bloqueante.

## Métrica de sucesso

Cadastro de um produto completo (com categorias e NCM) em menos de 1 minuto.

---

## Revisão 2026-08-22 — cadastro rápido virou atômico (auditoria, itens 28 e 24)

**Item 28.** O cadastro rápido (PDV, Entrada de Produtos) fazia **dois POSTs** sem transação:
`POST /produtos` e depois `POST /produtos/{id}/variacoes`. Falhando o segundo — o caso real é EAN
repetido vindo de planilha ou XML de terceiro, que `produto_barra` recusa —, sobrava um produto
**sem variação**: sem SKU, sem código de barras, invisível no PDV e na busca. E a tela dizia "Não
foi possível criar o produto", então clicar de novo criava um **segundo** produto órfão.

Novo `POST /api/v1/produtos/com-variacao`, transação única, devolvendo a **variação** (que já
carrega descrição, marca, referência e preço — é com ela que o chamador segue).

⚠️ **Endpoint separado de propósito**, não um campo opcional no `POST /produtos`: o formulário
completo de Produto e todos os importadores mandam `ProdutoRequest` como **corpo raiz**, e aninhá-lo
num envelope quebraria todos de uma vez para resolver um problema que é só do cadastro rápido.

**Item 24 — campos configuráveis sem revalidação no servidor.** `exigirSeObrigatorio` só tinha
versão para `String`, então todo campo configurável que é **número ou data** ficava sem checagem no
servidor, por construção — apesar de o `CLAUDE.md` afirmar que a bandeira é aplicada "de novo no
servidor". `exigirSeObrigatorioValor` cobre agora `pesoBruto`, `pesoLiquido`, `precoOferta`,
`dataInicioOferta` e `dataFinalOferta` (e `limiteCredito` em Cliente, `percComissao` em
Funcionário).

⚠️ **Ausente é `null`; zero é valor legítimo** — decisão do dono do produto: *"se não informados,
marcar como zero."* Quem quer o campo **fora do cadastro** usa `cfg_tela_campo.visivel = false`, que
é a dimensão certa para isso — a tabela tem as duas, com CHECK garantindo que obrigatório implica
visível.


---

## ⛔ 2026-09-02 — ICMS-ST já retido (CSOSN 500): três campos que decidem se a NF-e sai

**Por que existe.** Mercadoria comprada com ICMS-ST já retido pelo fornecedor sai com **CSOSN 500**
no Simples. No **modelo 55** a SEFAZ exige, junto do código, o que foi retido lá atrás —
`vBCSTRet`, `pST`, `vICMSSTRet` — e rejeita com `cStat 938` quando falta. Não é hipótese: foram
duas rejeições reais desta loja, em 24/08 e 27/08.

**Onde ficam.** Seção **Fiscal** do formulário, logo abaixo do Perfil Fiscal, e **só para
mercadoria** (ST retido é ICMS; serviço é ISS, e o servidor recusa nomeando o campo).

| Campo | O que é |
|---|---|
| Base do ST por unidade | `vBCSTRet` de **uma** unidade |
| ICMS-ST por unidade | `vICMSSTRet` de **uma** unidade |
| Alíquota do ST (%) | `pST`. Em branco, é **derivada** (valor ÷ base × 100) |

⭐ **São a RESERVA, e a tela diz isso.** A fonte preferida é a **entrada por XML** daquele produto —
é o ICMS-ST que o fornecedor de fato reteve, e está em `entrada_nfe_item`. Estes campos valem para
a mercadoria que **nunca entrou por XML**, caso em que só o contador sabe o número. Decisão do dono
do produto em 2026-09-02, conferida com o contador dele.

⚠️ **Por unidade, e não por nota.** A entrada guarda o total do item (o ST de 12 unidades) e a venda
pode ser de 1. Usar o total direto declararia 12× o ST numa venda de uma peça — e a nota seria
**autorizada**, porque a SEFAZ não sabe quantas unidades a compra teve.

⚠️ **Em branco ≠ zero.** Em branco é *"ninguém informou"*, e a NF-e 55 é **recusada antes de
reservar número**, com a mensagem nomeando o produto e mandando preencher aqui. Zero é *"o contador
conferiu e não há retenção"*, e a nota sai com 0,00. O banco tem CHECK garantindo que **base e
valor andem juntos**, e a tela avisa antes de deixar salvar pela metade.

⛔ **A NFC-e 65 não é afetada** — medido: 9 itens com CSOSN 500 saíram em NFC-e **autorizadas**
contra 16 em NF-e 55 rejeitadas. O balcão continua vendendo sem estes campos.

---

**Revisão 2026-09-04 — a grid navega por ↑/↓.** As setas percorrem as linhas e a linha corrente
fica realçada (`.linha-focada`: fundo translúcido + faixa à esquerda), sem tirar o foco do campo de
busca. O mecanismo é comum às 18 telas de lista e está descrito no arquivo-padrão
`docs/telas/cliente.md`; a implementação é `web/src/lib/useNavegacaoDeGrid.ts`.

✅ **Aberta e medida no navegador em 09-04.**
