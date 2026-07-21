# Spec: Cadastro de Plano de Contas                    Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-07-21 · Módulo(s): `cadastros` (cfg_plano_contas) · Fase: 1 — Núcleo do ERP

## Problema

O plano de contas é pré-requisito de outros cadastros e do financeiro: `fornecedor.id_plano_contas`
é `NOT NULL` **sem linha padrão pré-cadastrada** (V016) — ou seja, é impossível cadastrar um
fornecedor sem antes existir ao menos uma conta — e `contas_pagar` (V026) também o referencia.
Além disso, é a base dos relatórios futuros (DRE/fluxo de caixa, Q5/ADR-010). A tabela
`cfg_plano_contas` existia no banco desde V016, mas sem endpoint/UI e **sem os campos de
auditoria** (`criado_em`/`atualizado_em`) que o resto do domínio tem.

## Solução proposta

Terceira tela de domínio, no padrão consolidado por Cliente/Funcionário (`docs/telas/cliente.md`),
com as adaptações que o schema desta tabela exige (ver "Particularidades estruturais"). Papéis
`ADMIN` e `OPERADOR` têm acesso completo — mesma decisão dos demais cadastros (R8 não se aplica).

## Particularidades estruturais (diferenças em relação a Cliente/Funcionário)

Duas características do schema mudam o comportamento da tela — não são escolhas de UX:

1. **A PK é a chave de negócio** — `(id_tenant, id_plano_contas)`, onde `id_plano_contas` é
   `text` (o código contábil, ex.: `"3.1.001"`), sem surrogate `integer` (única tabela do
   domínio assim, ver `db/migration/README.md`). Consequências:
   - O **usuário digita o código** ao criar (campo obrigatório, único por tenant);
   - O código é **imutável após a criação** (é PK e é referenciado por FK composta em
     `fornecedor`/`contas_pagar`) — na edição o campo aparece bloqueado (`.campo-leitura`) e o
     backend ignora qualquer tentativa de troca via corpo da requisição (o código do path
     prevalece);
   - As rotas usam o próprio código: `/api/v1/planos-contas/3.1.001`, `/planos-contas/3.1.001`
     no front (pontos são URL-safe; o front ainda aplica `encodeURIComponent` por segurança).
2. **Não existe coluna `ativo`** — logo, **não existe o fallback de inativar** na exclusão:
   com vínculo em `fornecedor` ou `contas_pagar`, o `DELETE` responde **409** (Problem
   Details) e nada muda. O modal de confirmação avisa: "Se estiver em uso por fornecedor ou
   contas a pagar, a exclusão será bloqueada."

**Sem tela de configuração de campos (e sem ícone ⚙):** todos os campos da tabela são
`NOT NULL` — campos estruturalmente obrigatórios não são configuráveis
(`docs/telas/configuracao-tela.md`), então não sobra nada para configurar. Nenhuma chave foi
registrada em `ConfiguracaoTelaService.CAMPOS_POR_TELA`.

## Campos do formulário

Tabela `cfg_plano_contas` (V016; `criado_em`/`atualizado_em` adicionados em 2026-07-21 —
edição da própria V016, banco em construção, recriado do zero para aplicar).

**Foco automático:** ao **criar**, o foco vai para o Código; ao **editar**, direto para a
Descrição (o Código está bloqueado — não faz sentido receber foco).

| Campo (banco) | Rótulo na tela | Componente | Obrigatório | Regra |
|---|---|---|---|---|
| `id_plano_contas` | Código | texto (ex.: `3.1.001`) | **Sim** (PK) | Único por tenant (409 amigável se duplicar); MAIÚSCULAS; **imutável após criar** |
| `descricao` | Descrição | texto | **Sim** (NOT NULL) | MAIÚSCULAS |
| `tipo_movimento` | Tipo de movimento | select: CRÉDITO / DÉBITO / NEUTRO | **Sim** (NOT NULL) | Valores do ENUM `tipo_movimento_conta` (V013), **com acentos** — o backend valida contra a lista exata (não usa enum Java: identificadores acentuados foram evitados; DTO usa `String` + allowlist) |
| `inclui_dre` | Compõe a DRE | checkbox | — (NOT NULL, enviado sempre) | Agrupado com o campo abaixo na linha "Relatórios" |
| `inclui_fluxo_caixa` | Compõe o fluxo de caixa | checkbox | — (NOT NULL, enviado sempre) | |

**Layout:** duas seções — "Identificação" (Código 4 col + Descrição 8 col) e "Classificação"
(Tipo de movimento 4 col + checkboxes de relatórios lado a lado nas 8 restantes) — mais a
seção padrão **"Informações do registro"** (`InfoRegistro`, somente leitura: Código/Cadastrado
em/Última alteração). Para esta tela o `InfoRegistro` passou a aceitar **código texto** (a
prop `codigo` era `number`, virou `number | string`).

## Tela de listagem

- **Colunas:** Código, Descrição, Tipo de movimento, DRE (Sim/—), Fluxo de caixa (Sim/—) —
  todas ordenáveis (allowlist no backend). Ordenação default: **Código ASC** (a ordem
  hierárquica natural do plano contábil).
- **Busca:** campo único que procura **em código OU descrição** (`ILIKE` nos dois — o usuário
  pode digitar "3.1" ou "ALUGUEL"), sempre em maiúsculas. Parâmetro `busca` da API.
- **Sem filtro de status** (não existe `ativo`).
- Paginação (janela deslizante, 50 fixos), layout fixo, grid compacta, três ícones de ação —
  tudo idêntico ao padrão (ver `docs/telas/cliente.md`).

## Critérios de aceitação (viram testes)

- Dado um plano com código/descrição/tipo válidos, quando salvo, então é criado com descrição
  em MAIÚSCULAS e datas de auditoria preenchidas.
- Dado um código já existente no tenant, quando criado de novo, então 409 com erro amigável.
- Dado um `tipoMovimento` fora de CRÉDITO/DÉBITO/NEUTRO (ex.: "CREDITO" sem acento), quando
  salvo, então 400.
- Dado um PUT cujo corpo tenta trocar o código, quando salvo, então a descrição muda mas o
  código permanece o do path (e o código "novo" não existe).
- Dado um plano sem vínculo, quando excluído, então deixa de existir (`{"acao":"excluido"}`).
- Dado um plano vinculado a um fornecedor, quando o usuário tenta excluir, então **409** e o
  registro permanece intacto (sem inativar — não existe `ativo`).
- Dado uma busca por trecho do código ou da descrição, então encontra o registro.
- Dado `ordenarPor`/`direcao`, então a listagem respeita a coluna e direção pedidas.

Cobertos por `PlanoContasCrudTest` (8 testes) — suíte completa do projeto em 51/51 verdes.

## Impacto no contrato de API

```
GET    /api/v1/planos-contas?busca=&pagina=&limite=&ordenarPor=&direcao=
                                              lista paginada, busca (código OU descrição)/ordenação
POST   /api/v1/planos-contas                 cria conta (código no corpo)
GET    /api/v1/planos-contas/{codigo}        detalhe (codigo = id_plano_contas, texto)
PUT    /api/v1/planos-contas/{codigo}        atualiza (código imutável — o do path prevalece)
DELETE /api/v1/planos-contas/{codigo}        exclui; 409 se houver vínculo (sem fallback de inativar)
```

Todos sob `/api/v1/**` (JWT de tenant, RLS ativo — P8). Erros em Problem Details (RFC 9457).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `cadastros.planocontas.lista`** — busca por código/descrição; ícones de
  visualizar/editar/excluir; erro comum: exclusão bloqueada quando em uso. `url_video`: NULL.
- **`chave_tela`: `cadastros.planocontas.form`** — informar código (imutável depois),
  descrição, tipo de movimento e os checkboxes de relatórios; erros comuns: código duplicado,
  "não consigo mudar o código". `url_video`: NULL.

## Impacto no banco

`cfg_plano_contas` já existia (V016, RLS via V024). **A própria V016 foi editada** (banco em
construção — convenção do projeto) para adicionar `criado_em`/`atualizado_em`
(`timestamptz NOT NULL DEFAULT now()`), alinhando a tabela à convenção de auditoria do
domínio; banco de dev recriado do zero para aplicar (massa de teste restaurada em seguida:
tenants na mesma ordem, 110 clientes, funcionária, cliente com CNPJ alfanumérico). Ver
`db/migration/README.md`.

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- Hierarquia/árvore de contas (o código "3.1.001" carrega a hierarquia implícita, mas a tela
  lista plano — sem visualização em árvore nesta versão).
- Validação de formato do código (é texto livre; o lojista define sua própria máscara contábil).
- DRE/fluxo de caixa em si (os flags só são armazenados; relatórios ficam para a fase do
  financeiro completo — Q5/ADR-010).
- Seed de plano de contas padrão no signup (decisão herdada de V016: fornecedor exige conta
  criada manualmente antes).

## Questões abertas

Nenhuma bloqueante.

## Métrica de sucesso

Cadastro de uma conta nova em menos de 10 segundos (código + descrição + tipo).
