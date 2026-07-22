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

**Variação (SKU/`produto_barra`) e imagens (`produto_imagem`) ficam fora desta spec** — o
cadastro cobre só o produto "pai"; a matriz de variações e a galeria de imagens são o próximo
corte vertical (junto de Estoque).

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

## Particularidade 2: nome da variante — condicionado aos Parâmetros do Sistema

`produto.nome_variante_linha`/`nome_variante_coluna` (ex.: "Cor", "Tamanho") só fazem sentido se
o tenant realmente usa variação em linha/coluna — configurado **em outra tela**,
`cfg_geral.cfg_usa_variante_linha`/`cfg_usa_variante_coluna` (`configuracao.geral`,
docs/telas/configuracao-geral.md). Novo endpoint **aberto a qualquer papel** (diferente do
resto de `cfg_geral`, que é ADMIN-only): `GET /api/v1/config-geral/flags-variante` →
`{usaVarianteLinha, usaVarianteColuna}`. No formulário de produto:

- Os dois campos só aparecem (seção "Dimensões e Variantes") se a respectiva flag estiver
  ligada.
- O servidor **força `null`** em `nomeVarianteLinha`/`nomeVarianteColuna` quando a flag
  correspondente está desligada, mesmo que o cliente da API envie um valor — o campo está
  oculto, então qualquer valor é ignorado, não rejeitado.

## Particularidade 3: NCM — referência global, sem tela de manutenção

`cfg_produto_ncm(codigo_ncm PK text, descricao_ncm NOT NULL, aliquota_ibpt NUMERIC(10,2))` é a
**única tabela do domínio sem `id_tenant`/RLS** (mesma exceção de `plataforma.*`, P9, só que
fora daquele schema) — o código NCM (Nomenclatura Comum do Mercosul) é igual para qualquer
tenant. **Sem tela de manutenção por decisão explícita**: carregada/atualizada por script
(`db/scripts/seed_cfg_produto_ncm.sql`, rodando como `niner_owner` — único grant de escrita;
`niner_app` só tem `SELECT`).

- `produto.codigo_ncm` (nullable) ganhou `REFERENCES cfg_produto_ncm (codigo_ncm)` — FK simples
  (o alvo não tem `id_tenant`, diferente do resto do domínio).
- **Campo do formulário:** rótulo "NCM - Nomenclatura Comum do Mercosul"; entrada mascarada
  `9999.99.99` (8 dígitos), campo estreito e proporcional ao conteúdo, ao lado de Referência.
  Ao sair do campo (`onBlur`), busca `GET /api/v1/ncm/{codigo}` e mostra a descrição num campo
  somente-leitura ao lado. Código que não existe: **limpa o campo e avisa** ("Código NCM
  inválido — não encontrado."), em vez de deixar um código inválido no formulário.
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

## Campos do formulário

Tabela `produto` (V017). **Foco automático** no campo Descrição. Layout: "Produto ativo" em
linha própria; Descrição + Marca na mesma linha; Referência + NCM (estreito) + Descrição do NCM
na mesma linha; os 6 campos de Preços (Custo, % Venda, Venda, Início/Final da oferta, Preço de
Oferta) numa única linha que se reajusta quando os opcionais estão ocultos; Dimensões e
Variantes (Nome da Variante em Linha/Coluna, Peso Bruto/Líquido) numa única linha.

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
| `nome_variante_linha` | Nome da Variante em Linha | texto | Não configurável (controlado por `cfg_geral`) | Só aparece se `cfg_usa_variante_linha` |
| `nome_variante_coluna` | Nome da Variante em Coluna | texto | Não configurável (controlado por `cfg_geral`) | Só aparece se `cfg_usa_variante_coluna` |
| `ativo` | Produto ativo | checkbox | — | Ativo ao criar por padrão |
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
- Dado `cfg_geral.cfg_usa_variante_linha = false`, quando um `nomeVarianteLinha` é enviado,
  então o servidor grava `null` (ignora, não rejeita).
- Dado `ordenarPor`/`direcao`, então a listagem respeita a coluna e direção pedidas.
- Dado um produto sem vínculo, quando excluído, então deixa de existir.
- Dado um produto vinculado a uma variação, quando excluído, então é inativado, não apagado.

Cobertos por `ProdutoCrudTest` (20 testes) — suíte completa do projeto em **89/89 verdes**.

## Impacto no contrato de API

```
GET    /api/v1/produtos?descricao=&marca=&idCategoria=&status=&pagina=&limite=&ordenarPor=&direcao=
POST   /api/v1/produtos                        cria produto (+ categorias, na ordem enviada)
GET    /api/v1/produtos/{id}                   detalhe (categorias ordenadas por indice)
PUT    /api/v1/produtos/{id}                   atualiza (substitui a lista de categorias)
DELETE /api/v1/produtos/{id}                   exclui ou inativa (fallback com vínculo)

GET    /api/v1/categorias-produto              lista
POST   /api/v1/categorias-produto              cria
PUT    /api/v1/categorias-produto/{id}         renomeia

GET    /api/v1/ncm/{codigo}                    consulta (404 se não cadastrado) — só leitura

GET    /api/v1/config-geral/flags-variante     {usaVarianteLinha, usaVarianteColuna} — qualquer papel
```

Todos sob `/api/v1/**` (JWT de tenant, RLS ativo — P8, exceto `cfg_produto_ncm`/NCM que é
global). Erros em Problem Details (RFC 9457).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `catalogo.produto.lista`** — busca por descrição; filtro por categoria e
  status; ícones de visualizar/editar/excluir; erro comum: exclusão vira inativação quando há
  variação/imagem vinculada. `url_video`: NULL.
- **`chave_tela`: `catalogo.produto.form`** — descrição e preços obrigatórios; categorias
  múltiplas com ordem; nome de variante controlado pelos Parâmetros do Sistema; NCM com busca
  automática de descrição; regra da oferta (tudo ou nada); peso líquido ≤ peso bruto.
  `url_video`: NULL.

## Impacto no banco

`produto`, `produto_categoria`, `cfg_categoria_produto` já existiam desde V016/V017 (RLS via
V024). Alterações feitas **dentro da própria V017** (banco ainda em construção, sem migration
nova — ver `docs/PROGRESSO.md`):
- `produto_categoria.indice SMALLINT NOT NULL DEFAULT 0` + `UNIQUE(id_tenant, id_produto, indice)`.
- Nova tabela `cfg_produto_ncm` (global, sem RLS) + `produto.codigo_ncm` ganhou
  `REFERENCES cfg_produto_ncm (codigo_ncm)`.

## Impacto nas integrações

Nenhuma ainda — a publicação em canal (Mercado Livre/Shopee) depende da variação (`produto_barra`,
próximo corte), não do produto "pai".

## Non-goals desta feature

- Variação/SKU (`produto_barra`) e galeria de imagens (`produto_imagem`) — schema pronto desde
  V017, sem CRUD ainda; ficam para o próximo corte vertical (Estoque).
- Reajuste em massa de preços, histórico de preço (`reajustado_em` existe na coluna, sem uso
  ainda).
- Importação em lote (planilha).

## Questões abertas

Nenhuma bloqueante.

## Métrica de sucesso

Cadastro de um produto completo (com categorias e NCM) em menos de 1 minuto.
