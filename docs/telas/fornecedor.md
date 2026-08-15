# Spec: Cadastro de Fornecedor                          Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-07-21 · Módulo(s): `cadastros` (fornecedor) · Fase: 1 — Núcleo do ERP

## Problema

O cadastro de fornecedor é pré-requisito de compras/entrada de estoque
(`produto_movimento_mestre.id_fornecedor`) e de contas a pagar (`contas_pagar.id_fornecedor`,
V026). A tabela `fornecedor` existia no banco desde V016 (`fornecedor.id_plano_contas`
`NOT NULL` — nenhum fornecedor pode existir sem que um plano de contas já exista), mas sem
endpoint/UI. A tela de Plano de Contas (`docs/telas/plano-contas.md`, 2026-07-21) foi construída
primeiro exatamente para destravar esta. *(Na redação original constava "sem seed padrão";
**superado em 2026-08-14** — o signup copia as 76 contas de `cfg_plano_contas_padrao`,
`SignupService.java:99-116`, então todo tenant novo já nasce com plano de contas.)*

## Solução proposta

Quarta tela de domínio, no mesmo padrão consolidado por Cliente/Funcionário/Plano de Contas
(`docs/telas/cliente.md`). A única particularidade estrutural é o vínculo obrigatório com
plano de contas — resolvido com um seletor de plano de contas + criação rápida embutida (mesmo
mecanismo do "＋ Nova categoria" do cliente), e não com uma nova classe de tela. Papéis `ADMIN` e
`OPERADOR` têm acesso completo (R8 não se aplica).

## Particularidade estrutural: vínculo obrigatório com plano de contas

`fornecedor.id_plano_contas` é `NOT NULL` (FK composta `(id_tenant, id_plano_contas)` →
`cfg_plano_contas`). ~~**Não há linha padrão pré-cadastrada** — é responsabilidade do lojista
ter ao menos um plano de contas antes de cadastrar o primeiro fornecedor.~~ — **superado em
2026-08-14**: o signup copia as **76 contas** de `cfg_plano_contas_padrao`
(`SignupService.java:99-116`), então o lojista sempre tem conta pra escolher já no primeiro
fornecedor. Os mecanismos abaixo continuam valendo — o seletor porque o plano é grande demais
pra um `<select>` cru, e a criação rápida porque o lojista pode querer uma conta própria:
- No **formulário**, ao lado da Razão Social: o componente `SeletorPlanoContas`
  (`web/src/components/SeletorPlanoContas.tsx`, 2026-08-13 — antes era um `<select>` nativo
  populado por `GET /api/v1/planos-contas?limite=100`) — busca por prefixo de código ou por
  nome, carrega o plano inteiro (`limite=500`) e filtra no cliente, sem risco de o valor cair
  fora de uma página carregada;
- Um botão **"＋ Novo"** ao lado do seletor que abre `PlanoContasModal` — um modal de criação
  rápida (código, descrição, tipo de movimento, DRE/fluxo de caixa) que, ao salvar, já
  seleciona a conta recém-criada no formulário de fornecedor. A gestão completa (editar,
  excluir) continua exclusiva da tela própria `/planos-contas`;
- Um **filtro por plano de contas** na listagem de fornecedores, também com `SeletorPlanoContas`
  (migrado em 2026-08-13, junto do filtro equivalente em
  `financeiro.contacorrentemovimento.lista` — antes ambos eram `<select>` nativo com
  `limite=100`), não texto livre (os planos são uma lista fechada);
- O backend rejeita com **400** (não 500) um `idPlanoContas` que não existe
  (`DataIntegrityViolationException` da FK capturada e traduzida em `IllegalArgumentException`
  "Plano de contas informado não existe.").

## Campos do formulário

Tabela `fornecedor` (V016). **Foco automático** no campo Razão Social ao abrir o formulário
(criar ou editar — não há campo bloqueado nesta tela, diferente do Plano de Contas).

| Campo (banco) | Rótulo na tela | Componente | Obrigatório | Regra |
|---|---|---|---|---|
| `razao_social` | Razão Social | texto | **Sim** (NOT NULL) | MAIÚSCULAS |
| `id_plano_contas` | Plano de Contas | `SeletorPlanoContas` (busca código/nome) + botão "＋ Novo" | **Sim** (NOT NULL) | Deve existir; ver seção acima |
| `nome_fantasia` | Nome Fantasia | texto | Configurável | MAIÚSCULAS |
| `cnpj` | CNPJ | texto com máscara | Configurável | Alfanumérico (CLAUDE.md), 14 caracteres, dígito verificador, único por tenant; sempre pessoa jurídica — CPF não é aceito |
| `inscricao_estadual` | Inscrição Estadual | texto | Configurável | MAIÚSCULAS |
| `email` | E-mail | texto | Configurável | Formato validado |
| `telefone` | Telefone | texto com máscara | Configurável | **Fixo ou celular** (10–11 dígitos com DDD) — regra mais frouxa que a do cliente, que exige celular. ⚠️ Exceção: a Importação de Dados grava sem validar (ver abaixo) |
| `cep` | CEP | texto com máscara | Configurável | Preenche endereço via ViaCEP |
| `endereco`, `numero`, `bairro`, `cidade`, `estado` | Endereço, Número, Bairro, Cidade, UF | texto/select | Configuráveis | MAIÚSCULAS; UF por select |
| `ativo` | Fornecedor ativo | checkbox | — | Ativo ao criar por padrão |

Todos os campos exceto Razão Social e Plano de Contas são **configuráveis por tenant**
(`cfg_tela_campo`, chave `cadastros.fornecedor.form`) — visibilidade e obrigatoriedade,
com tela de configuração própria (`ADMIN`, ícone ⚙), mesma mecânica do Cliente/Funcionário.
A obrigatoriedade configurada é **reforçada no backend** (`FornecedorService.validar`).

## Tela de listagem

- **Colunas:** Razão Social, CNPJ, Plano de Contas, Telefone, Cidade/UF, Status — todas
  ordenáveis (allowlist no backend). Ordenação default: Razão Social ASC.
- **Busca:** único campo cobrindo razão social **ou** nome fantasia (`ILIKE` nos dois), em
  maiúsculas — o lojista muitas vezes só conhece o fornecedor pelo fantasia.
- **Filtro por plano de contas** (`SeletorPlanoContas`, busca código/nome) e **filtro de
  status** (Ativos/Inativos/Todos).
- Paginação em janela deslizante (50 fixos), layout fixo, três ícones de ação — idêntico ao
  padrão (`docs/telas/cliente.md`).
- **Botão de fechar (✕)** no cabeçalho da listagem e do formulário (`BotaoFecharTela`, volta
  pelo histórico com `navigate(-1)`, nunca por rota fixa — convenção de todo o sistema).

## Exceção à validação de telefone: Rotina de Importação de Dados

A regra de 10–11 dígitos vale para o **cadastro manual** (tudo que entra pelo
`FornecedorController`). A Rotina de Importação de Dados chama a sobrecarga
`FornecedorService.criar(req, validarTelefone = false)`
(`api/src/main/java/com/vetor/niner/cadastros/fornecedor/FornecedorService.java:153-162`) e
**grava o telefone como veio** — só normalizado para dígitos, sem exigir 10–11. Decisão do dono
do produto (2026-08-10): planilha migrada de outro sistema traz telefone em formato livre (ramal,
número incompleto), e travar a linha inteira por causa disso inviabilizaria a migração. Efeito
colateral aceito: um fornecedor importado pode ter telefone que a própria tela recusaria se
fosse digitado à mão — ao editá-lo, a validação volta a valer e o campo precisa ser corrigido.

## Exclusão de fornecedor

Segue o padrão de Cliente/Funcionário/Plano de Contas (com fallback): se o fornecedor tiver
vínculo em `produto_movimento_mestre` (compras/entradas
de estoque, V019) **ou** em `contas_pagar` (V026), o DELETE **inativa** (`ativo = false`) em
vez de apagar, e retorna `{"acao":"inativado","motivo":"..."}`. Sem vínculo, apaga de verdade
(`{"acao":"excluido"}`).

## Critérios de aceitação (viram testes)

- Dado razão social e plano de contas válidos, quando salvo, então o fornecedor é criado com
  razão social em MAIÚSCULAS.
- Dado um `idPlanoContas` vazio, quando salvo, então 400.
- Dado um `idPlanoContas` que não existe, quando salvo, então 400 com erro amigável (não 500).
- Dado um CNPJ alfanumérico válido (ex.: `12.ABC.345/01DE-35`), quando salvo, então aceito e
  normalizado sem máscara.
- Dado um CNPJ com dígito verificador inválido, quando salvo, então 400.
- Dado um CPF (11 dígitos) no campo CNPJ, quando salvo, então 400 (fornecedor é sempre pessoa
  jurídica).
- Dado um CNPJ já usado por outro fornecedor do mesmo tenant, quando salvo, então 409.
- Dado um telefone com 8 dígitos (sem DDD), quando salvo, então 400; com 10 ou 11, aceito.
- Dado um campo marcado obrigatório na configuração de tela, quando omitido, então 400
  (reforço no backend).
- Dado `ordenarPor`/`direcao`, então a listagem respeita a coluna e direção pedidas.
- Dado um fornecedor sem vínculo, quando excluído, então deixa de existir.
- Dado um fornecedor vinculado a uma movimentação de estoque, quando excluído, então é
  inativado, não apagado.

Cobertos por `FornecedorCrudTest` (12 testes) — suíte completa do projeto em **500/500 verdes
(2026-08-14)**.

## Impacto no contrato de API

```
GET    /api/v1/fornecedores?razaoSocial=&cnpj=&idPlanoContas=&status=&pagina=&limite=&ordenarPor=&direcao=
POST   /api/v1/fornecedores                  cria fornecedor
GET    /api/v1/fornecedores/{id}             detalhe
PUT    /api/v1/fornecedores/{id}             atualiza
DELETE /api/v1/fornecedores/{id}             exclui ou inativa (fallback com vínculo)
```

Todos sob `/api/v1/**` (JWT de tenant, RLS ativo — P8). Erros em Problem Details (RFC 9457).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `cadastros.fornecedor.lista`** — busca por razão social/fantasia; filtro
  por plano de contas e status; ícones de visualizar/editar/excluir; erro comum: exclusão
  vira inativação quando há vínculo. `url_video`: NULL.
- **`chave_tela`: `cadastros.fornecedor.form`** — razão social e plano de contas obrigatórios
  (com atalho "＋ Novo" para criar plano de contas sem sair da tela); CNPJ/contato/endereço
  opcionais; CEP preenche endereço automaticamente; erros comuns: CNPJ inválido, telefone
  precisa de DDD. `url_video`: NULL.

## Impacto no banco

Nenhum — `fornecedor` já existia (V016, RLS via V024) com todas as colunas necessárias.

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- Múltiplos contatos/telefones por fornecedor (um único conjunto de contato nesta versão).
- Vínculo com produtos/itens fornecidos (fica para o módulo de catálogo/compras).
- Condições comerciais (prazo de pagamento, desconto padrão) — fica para o módulo financeiro
  quando `contas_pagar` for além do CRUD básico.

## Questões abertas

Nenhuma bloqueante.

## Métrica de sucesso

Cadastro de um fornecedor completo em menos de 30 segundos (assumindo o plano de contas já
existente).
