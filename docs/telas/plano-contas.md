# Spec: Cadastro de Plano de Contas                    Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-07-21 · Revisão: 2026-07-31 · Módulo(s): `cadastros` (cfg_plano_contas) · Fase: 1 — Núcleo do ERP

## Problema

O plano de contas é pré-requisito de outros cadastros e do financeiro: `fornecedor.id_plano_contas`
é `NOT NULL` (V016) — é impossível cadastrar um fornecedor sem antes existir ao menos uma conta —
e `contas_pagar`/`caixa_detalhe`/`conta_corrente_movimento` também o referenciam. A versão original
(2026-07-21) era um cadastro simples: código texto livre + descrição + tipo de movimento + dois
flags soltos (`inclui_dre`/`inclui_fluxo_caixa`), sem hierarquia, sem validação de formato, sem
seed padrão. Isso bastava pra desbloquear fornecedor/contas a pagar, mas não sustentava DRE/DFC de
verdade: os flags eram só um "sim/não" sem classificação (não dava pra montar uma demonstração de
resultado nem um fluxo de caixa por grupo a partir deles), e não havia trava contra contar a mesma
movimentação duas vezes (ex.: compra de mercadoria e CMV).

**Revisão de 2026-07-31**, pedido direto do dono do produto com um script SQL completo já escrito:
o plano de contas virou gerencial de verdade — hierarquia de 4 níveis via máscara fixa, DRE e
fluxo de caixa como classificação (grupo + sinal, não só flag), e um plano padrão de ~200 contas
pra varejo de calçados/confecções pronto pra carregar. Este documento descreve o desenho **atual**
(pós-revisão); a seção "Histórico — desenho original (2026-07-21)" no fim registra o que existia
antes, pra contexto.

## Solução proposta

Continua a mesma tela consolidada (`docs/telas/cliente.md`), papéis `ADMIN`/`OPERADOR` com acesso
completo (R8 não se aplica) — só os campos e a validação do formulário mudaram. **Decisão de
escopo confirmada com o dono do produto (2026-07-31):** a tela continua **lista plana** (busca +
paginação + formulário), **sem** navegação em árvore — a hierarquia existe no banco (máscara,
níveis, view `vw_plano_contas_arvore`) e é usada pra validação/apuração, mas a tela não ganhou um
componente de árvore nesta rodada. Non-goal explícito, ver seção própria abaixo.

## Desenho atual (2026-07-31)

### Máscara e hierarquia

`id_plano_contas` passou de texto livre para máscara fixa de 12 caracteres:
**`9.99.999.999`** (conta.subconta.item.subitem — 1+2+3+3 dígitos). `nivel` (1–4) e
`id_plano_contas_pai` são **colunas geradas** (`GENERATED ALWAYS AS ... STORED`) — nunca digitados,
sempre derivados do próprio código:

| Nível | Regra da máscara | Exemplo |
|---|---|---|
| 1 (conta) | subconta = "00" | `3.00.000.000` |
| 2 (subconta) | item = "000" | `3.03.000.000` |
| 3 (item) | subitem = "000" | `3.03.001.000` |
| 4 (subitem) | senão | `3.03.001.001` |

A FK `cfg_plano_contas_pai_fk` (auto-relacionamento, `DEFERRABLE INITIALLY DEFERRED`) exige que o
pai já exista — **a hierarquia se constrói de cima para baixo**: pra criar uma conta de nível 4 é
preciso que os níveis 1/2/3 acima já existam. O servidor pré-checa isso e devolve 400 amigável
("Crie primeiro a conta pai X") em vez de deixar a violação de FK crua estourar.

**Convenção de extensão** (documentada no seed, `db/scripts/seed_plano_contas_padrao.sql`): dentro
de cada grupo, subcontas `.90`–`.99` e itens `.900`–`.999` ficam reservados para contas específicas
do tenant — uma atualização futura do plano padrão nunca ocupa essas faixas, então nunca colide com
customização. Grupo `9` inteiro também fica reservado (o seed só usa grupos `1` a `8`).

### DRE e fluxo de caixa — classificação, não mais flag solto

`inclui_dre`/`inclui_fluxo_caixa` continuam existindo, mas agora cada um tem um **grupo** obrigatório
quando `true` (`grupo_dre`/`grupo_dfc`, ENUMs `grupo_dre_conta`/`grupo_dfc_conta`) e forçado pra
`NAO_APLICA` quando `false` — o servidor ignora o que o cliente mandar nesse caso. **Princípio
central: os dois são independentes** — é isso que evita duplicação entre resultado e caixa:

- Compra de mercadoria: entra no **fluxo de caixa** (`3.03`), nunca na **DRE**.
- CMV: entra na **DRE** (`3.01`), nunca no **fluxo de caixa**.
- Amortização de principal: caixa sim, DRE não (os juros, sim, entram na DRE — `5.01` vs `5.02`).
- Depreciação: DRE sim, caixa não (o desembolso já ocorreu no CAPEX, grupo `6`).

`sinal` (+1/-1/0) e `aceita_lancamento` (boolean) são sempre **derivados no servidor** —
`sinal` de `tipo_movimento` (CREDITO→+1, DEBITO→-1, NEUTRO→0), `aceita_lancamento` de `natureza`
(`ANALITICA`→true, `SINTETICA`→false) — nunca aparecem como campo do formulário. `natureza`
(`SINTETICA`/`ANALITICA`) é novo: sintética agrupa (não recebe lançamento), analítica recebe.
Conta `NEUTRO` nunca pode compor a DRE (trava por CHECK — `tipo_movimento_conta` sem acento desde
esta revisão, ver "Impacto no banco").

### Campos novos opcionais

`descricao_curta` (cupom/PDV/relatório estreito), `exige_centro_custo`/`exige_contraparte`
(transferência exige conta de destino)/`exige_documento` (NF/contrato), `id_conta_contabil`
(de-para SPED ECD), `id_plano_referencial` (de-para plano referencial da RFB), `observacao`.
Nenhum é obrigatório — ficam numa seção própria do formulário ("Integrações (opcional)").

### `ativo` — a tela deixou de ser a exceção

A tabela ganhou `ativo boolean NOT NULL DEFAULT true`. Antes desta revisão, `cfg_plano_contas` era
a **única** tela de cadastro sem essa coluna — exclusão sempre era de verdade ou 409. Agora segue o
mesmo padrão de Cliente/Funcionário/Fornecedor: a exclusão cai no fallback de **inativar** quando:

1. a conta é `padrao_sistema = true` (só o seed grava isso — nunca vem do formulário);
2. a conta tem contas filhas (`id_plano_contas_pai` de outra conta aponta pra ela);
3. a conta está vinculada a `fornecedor`/`contas_pagar`/`caixa_detalhe`/`conta_corrente_movimento`.

Sem nenhum desses três, exclui de verdade. O gatilho de banco `trg_cfg_plano_contas_guarda`
(`CONSTRAINT TRIGGER`) reforça o mesmo — 1 e 2 na prática nunca devem disparar via API porque o
serviço já pré-checa antes, mas continuam como rede de segurança contra SQL direto.

## Particularidades estruturais (ainda válidas da versão original)

1. **A PK é a chave de negócio** — `(id_tenant, id_plano_contas)`, sem surrogate `integer`. O
   **usuário digita o código** ao criar (com a máscara nova), **imutável após a criação** (é PK e
   é referenciada por FK composta em `fornecedor`/`contas_pagar`/`caixa_detalhe`/
   `conta_corrente_movimento`) — na edição o campo aparece bloqueado (`.campo-leitura`).
2. Rotas usam o próprio código: `/api/v1/planos-contas/3.03.001.001`.

**Sem tela de configuração de campos** (sem ícone ⚙): os campos estruturalmente obrigatórios
(código/descrição/tipo de movimento/natureza, e grupo DRE/DFC quando a flag correspondente está
ligada) continuam fora do padrão configurável — não há registro em
`ConfiguracaoTelaService.CAMPOS_POR_TELA`.

## Campos do formulário

| Campo (banco) | Rótulo na tela | Componente | Obrigatório | Regra |
|---|---|---|---|---|
| `id_plano_contas` | Código | texto mascarado `9.99.999.999` | **Sim** (PK) | Único por tenant; **imutável após criar**; pai precisa existir |
| `descricao` | Descrição | texto | **Sim** | MAIÚSCULAS |
| `descricao_curta` | Descrição curta | texto | — | Opcional, MAIÚSCULAS |
| `tipo_movimento` | Tipo de movimento | select: Crédito/Débito/Neutro | **Sim** | Deriva `sinal` no servidor |
| `natureza` | Natureza | select: Sintética/Analítica | **Sim** | Deriva `aceita_lancamento` no servidor |
| `inclui_dre` + `grupo_dre` | Compõe a DRE + Grupo da DRE | checkbox + select condicional | Grupo obrigatório só se marcado | Neutro nunca pode compor a DRE |
| `inclui_fluxo_caixa` + `grupo_dfc` | Compõe o fluxo de caixa + Grupo | checkbox + select condicional | Grupo obrigatório só se marcado | Independente da DRE |
| `exige_centro_custo`/`exige_contraparte`/`exige_documento` | Exigências no lançamento | 3 checkboxes | — | |
| `id_conta_contabil`/`id_plano_referencial` | Integrações | texto | — | Opcionais |
| `observacao` | Observação | textarea | — | Opcional |

**Foco automático:** ao **criar**, o foco vai para o Código; ao **editar**, direto para a
Descrição. **Layout:** seções "Identificação", "Classificação", "DRE e fluxo de caixa" (com nota
explicando a independência), "Exigências no lançamento", "Integrações (opcional)", mais a seção
padrão **"Informações do registro"** (`InfoRegistro`).

## Tela de listagem

- **Colunas:** Código (indentado por nível), Descrição (🔒 quando `padrao_sistema`), Tipo de
  movimento, Natureza, DRE (Sim/—), Fluxo de caixa (Sim/—), Status (badge Ativo/Inativo) — todas
  ordenáveis. Ordenação default: **Código ASC**.
- **Busca:** código OU descrição (`ILIKE`), maiúsculas. **Filtro de status** (Ativos/Inativos/
  Todos, novo — mesmo padrão de Funcionário), padrão "Ativos".
- Paginação (janela deslizante, 50 fixos), layout fixo, grid compacta, três ícones de ação.

## Regras de negócio

### Hierarquia de cima para baixo

Criar uma conta de nível > 1 exige que o pai (derivado da máscara) já exista — 400 amigável
("Crie primeiro a conta pai X") se não existir. Nível 1 (`subconta = "00"`) nunca tem pai.

### DRE/DFC — grupo obrigatório só quando a flag está ligada

`incluiDre = true` sem `grupoDre` válido → 400. `incluiDre = false` → `grupoDre` sempre gravado
como `NAO_APLICA`, ignorando o que o cliente mandar (idem `incluiFluxoCaixa`/`grupoDfc`). Conta
`NEUTRO` com `incluiDre = true` → 400 (trava de negócio, reforçada por CHECK no banco).

### Exclusão com fallback de inativar

Ver "Desenho atual" acima — motivo devolvido: conta padrão do sistema / conta possui contas
filhas / conta em uso por fornecedor-contas a pagar-caixa-conta corrente.

## Critérios de aceitação (viram testes)

- Dado um código no formato `9.99.999.999` com todos os campos coerentes, quando salvo, então é
  criado com `nivel`/`idPlanoContasPai`/`sinal`/`aceitaLancamento` corretamente derivados.
- Dado um código fora da máscara (inclusive o formato antigo "3.1.001"), quando salvo, então 400.
- Dado `tipoMovimento`/`natureza` fora dos ENUMs válidos, então 400.
- Dado uma conta `NEUTRO` com `incluiDre = true`, então 400.
- Dado `incluiDre = true` sem `grupoDre`, então 400.
- Dado uma conta de nível 4 cujo pai (nível 3) não existe, então 400 com o código do pai na
  mensagem.
- Dado uma hierarquia completa (grupo→subconta→item→subitem) criada de cima para baixo, então a
  conta folha é criada com `nivel = 4` e o pai correto.
- Dado um código já existente no tenant, quando criado de novo, então 409.
- Dado um PUT cujo corpo tenta trocar o código, então a descrição muda mas o código permanece o
  do path.
- Dado um plano sem nenhum obstáculo, quando excluído, então deixa de existir.
- Dado um plano padrão do sistema, com contas filhas, ou vinculado a fornecedor/contas a pagar/
  caixa/conta corrente, quando excluído, então é **inativado** (não apagado), com o motivo
  correspondente.
- Dado o filtro de status (Ativos/Inativos/Todos), então a listagem respeita.
- Dado uma busca por trecho do código ou da descrição, então encontra o registro.
- Dado `ordenarPor`/`direcao`, então a listagem respeita.
- Dado o plano de contas de um tenant, então não aparece nem pode ser buscado por outro tenant.

Cobertos por `PlanoContasCrudTest` (19 testes) — suíte completa do projeto em 276/276 verdes
(2026-07-31). `FornecedorCrudTest`/`ContaCorrenteCrudTest`/`ContaCorrenteMovimentoCrudTest`
tiveram os códigos de teste convertidos pro formato novo (dependiam de um plano de contas
existente pra testar suas próprias FKs).

## Impacto no contrato de API

```
GET    /api/v1/planos-contas?busca=&status=&pagina=&limite=&ordenarPor=&direcao=
POST   /api/v1/planos-contas                 cria conta (código no corpo, formato 9.99.999.999)
GET    /api/v1/planos-contas/{codigo}        detalhe
PUT    /api/v1/planos-contas/{codigo}        atualiza (código imutável — o do path prevalece)
DELETE /api/v1/planos-contas/{codigo}        exclui, ou inativa (ver regras de negócio)
```

`PlanoContasRequest`: `codigo`, `descricao`, `descricaoCurta?`, `tipoMovimento`, `natureza`,
`incluiDre`, `grupoDre?`, `incluiFluxoCaixa`, `grupoDfc?`, `exigeCentroCusto`, `exigeContraparte`,
`exigeDocumento`, `idContaContabil?`, `idPlanoReferencial?`, `observacao?`. `sinal`/
`aceitaLancamento`/`ativo`/`padraoSistema` nunca são aceitos no corpo — sempre derivados/
controlados no servidor. `PlanoContasResponse` inclui todos os campos acima mais `nivel`,
`idPlanoContasPai`, `sinal`, `aceitaLancamento`, `padraoSistema`, `ativo`, `criadoEm`,
`atualizadoEm`. Erros em Problem Details (RFC 9457): 400 (máscara/ENUM/grupo inválido, pai
inexistente), 404, 409 (código duplicado).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `cadastros.planocontas.lista`** — busca por código/descrição, filtro de
  status; ícones de visualizar/editar/excluir; erro comum: pai inexistente ao criar. `url_video`: NULL.
- **`chave_tela`: `cadastros.planocontas.form`** — código imutável depois de criado, grupo da
  DRE/DFC só aparece quando a flag correspondente está marcada. `url_video`: NULL.

## Impacto no banco

**Revisão 2026-07-31** (banco em construção — `V013__dominio_tipos_enum.sql` e
`V016__cadastros.sql` editados no lugar, convenção do projeto):

- `tipo_movimento_conta` (V013) perdeu os acentos (`CRÉDITO`/`DÉBITO` → `CREDITO`/`DEBITO`) via
  `ALTER TYPE ... RENAME VALUE` no banco de dev — dado existente preservado, só o rótulo mudou.
- 3 ENUMs novos em V013: `natureza_conta`, `grupo_dre_conta`, `grupo_dfc_conta`.
- `cfg_plano_contas` (V016) ganhou: `descricao_curta`, `natureza`, `nivel`/`id_plano_contas_pai`
  (colunas geradas), `grupo_dre`/`grupo_dfc`, `sinal`, `aceita_lancamento`, `exige_centro_custo`/
  `exige_contraparte`/`exige_documento`, `id_conta_contabil`/`id_plano_referencial`,
  `padrao_sistema`, `ativo`, `observacao`. CHECKs novos amarrando tudo isso (máscara, sinal↔tipo,
  aceita_lancamento↔natureza, neutro↔dre, grupo↔flag). FK de hierarquia
  (`cfg_plano_contas_pai_fk`, auto-relacionamento, deferrable). Índices novos: pai (navegação),
  lançável (combo/autocomplete), DRE/DFC (apuração), busca trigram tolerante a acento
  (`unaccent_imutavel`, wrapper IMMUTABLE — `unaccent()` sozinha não pode ir num índice de
  expressão), irmãos sem descrição duplicada, de-para contábil único. Trigger de auditoria
  (`atualizado_em`) e trigger de guarda (bloqueia excluir conta padrão/com filhos — rede de
  segurança, o serviço já pré-checa). View `vw_plano_contas_arvore` (árvore recursiva, ainda sem
  consumidor na tela — non-goal desta rodada).
- RLS continua centralizada em V024 (não duplicada em V016).
- **Dado existente preservado**: a única conta que havia no banco antes desta revisão
  (`3.1.001 — COMPRA MERCADORIA REVENDA`, vinculada a 1 fornecedor) foi migrada — via `UPDATE`,
  nunca `DELETE` — pro código `3.03.001.001` (a conta padrão equivalente do plano novo, decisão
  confirmada com o dono do produto), com o fornecedor atualizado na mesma operação. Nada foi
  apagado.
- Novo `db/scripts/seed_plano_contas_padrao.sql` (fora das migrations — dado de negócio por
  tenant, não estrutura de schema; mesmo padrão de `seed_cfg_produto_ncm.sql`): plano padrão de
  ~200 contas pra varejo de calçados/confecções, carregado pro tenant 1 (idempotente, `ON
  CONFLICT DO NOTHING`). **Não** foi ligado ao `SignupService` — seed continua manual por tenant
  (o próprio script comenta o passo de "clonar pra outro tenant").

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- **Navegação em árvore na tela** — decisão confirmada com o dono do produto (2026-07-31): a
  hierarquia existe no banco (máscara/níveis/view), mas a tela continua lista plana nesta rodada.
- **Seed automático no signup** — cada tenant novo continua nascendo sem plano de contas; o seed
  padrão é aplicado manualmente por tenant via script.
- **Apuração de DRE/DFC em relatório** — os grupos/sinal já existem e são coerentes o suficiente
  pra sustentar uma consulta de apuração (esqueleto comentado no próprio script de seed), mas
  nenhuma tela de relatório foi construída.
- **Criação assistida de hierarquia** (ex.: escolher o pai num combo e o sistema sugerir o
  próximo código livre) — o usuário digita o código completo à mão; se o pai não existir, cria-se
  o pai primeiro, manualmente.

## Questões abertas

Nenhuma bloqueante.

## Métrica de sucesso

Cadastro de uma conta nova (incluindo a decisão de DRE/fluxo de caixa) em menos de 20 segundos,
sem duplicar contagem entre resultado e caixa.

---

## Histórico — desenho original (2026-07-21)

Antes da revisão de 2026-07-31, `cfg_plano_contas` era: `id_plano_contas` texto livre (sem
máscara, sem hierarquia), `descricao`, `tipo_movimento` (com acentos), `inclui_dre`/
`inclui_fluxo_caixa` como flags soltos sem grupo, sem `natureza`/`sinal`/`aceita_lancamento`
derivados, sem `ativo` (exclusão sempre de verdade ou 409, sem fallback de inativar — a única
tela de cadastro assim). Non-goals da época, todos **superados** por esta revisão: "hierarquia/
árvore de contas", "validação de formato do código", "seed de plano de contas padrão no signup"
(este último continua non-goal, mas agora existe um script de seed manual pronto, o que não
existia antes). Ver `git log -p docs/telas/plano-contas.md` pro texto original completo.
