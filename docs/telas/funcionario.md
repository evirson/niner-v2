# Spec: Cadastro de Funcionários                    Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-07-21 · Módulo(s): `cadastros` (funcionario) · Fase: 1 — Núcleo do ERP

## Problema

O lojista precisa identificar quem trabalha na loja para atribuir comissão por venda/item
(`produto_movimento_detalhe.id_funcionario`, já existente desde V019) e, futuramente, para
relatórios de desempenho por vendedor. Hoje não existe nenhuma tela: a tabela `funcionario`
existe no banco (V016) mas sem nenhum endpoint/UI.

## Solução proposta

Segunda tela de domínio do projeto, construída **replicando o padrão já consolidado no
cadastro de Cliente** (`docs/telas/cliente.md`) — mesma arquitetura de listagem/formulário,
paginação, ordenação, ícones de ação, configuração de tela e validação em profundidade —
mas com o schema mais simples de `funcionario` (sem categoria, sem CNPJ, sem endereço). Papéis
`ADMIN` e `OPERADOR` têm acesso completo, mesma decisão de produto do cliente (operação do
dia a dia, não cai na restrição de R8).

## User stories

- Como **gestor**, quero cadastrar um funcionário com nome, CPF, celular, cargo e percentual
  de comissão, para vincular vendas/movimentações de estoque a ele.
- Como **gestor**, quero inativar um funcionário que saiu da empresa em vez de precisar
  excluir, principalmente se ele já tiver movimentações associadas.
- Como **gestor**, quero ordenar a lista de funcionários por qualquer coluna (nome, cargo,
  % comissão etc.) para organizar a visualização como preciso no momento.

## Campos do formulário

Tabela `funcionario` (V016) — schema bem mais enxuto que `cliente`: sem `fisica_juridica`
(funcionário é sempre pessoa física), sem categoria, sem endereço/redes sociais/e-mail.
`id_empresa` existe na tabela mas **não aparece no formulário** — é preenchido
automaticamente pelo backend com a única empresa do tenant (Q6: `tenant 1:N empresa`, 1:1 no
v1); não há seletor de empresa enquanto só existir uma.

**Foco automático:** ao abrir a tela, o foco vai para o campo **Nome** — mesma convenção do
cadastro de Cliente.

| Campo (banco) | Rótulo na tela | Componente | Máscara / formato | Obrigatório | Regra |
|---|---|---|---|---|---|
| `ativo` | Funcionário ativo | checkbox | — | default `true` | Primeiro campo do formulário, mesma convenção do Cliente |
| `nome` | Nome | texto | — | **Sim** (NOT NULL no banco) | Normalizado para MAIÚSCULAS |
| `cpf` | CPF | texto | `000.000.000-00` | Não (nullable; configurável) | Se preenchido, **valida dígito verificador** (reaproveita `Documentos`, o mesmo validador de CPF do Cliente). **Não é único por tenant** — decisão de produto já registrada em V016/§3.3.9 ("o CPF deixou de ser único"), ao contrário do cliente |
| `telefone` | Celular | texto | `(00) 00000-0000` | Não (configurável) | Quando preenchido: 11 dígitos com o 3º dígito = 9 (mesma regra do Cliente) |
| `cargo` | Cargo | texto | — | Não (configurável) | Texto livre, MAIÚSCULAS — sem tabela de cargos pré-cadastrados |
| `perc_comissao` | % Comissão | numérico, percentual (mascarado — dígitos digitados = centésimos) | `NUMERIC(5,2)`, default `0` | Não (configurável) | Deve estar entre **0 e 100** (validado no front e no back); mesma técnica de máscara do "Limite de crédito" do Cliente, adaptada para percentual (`mascararPercentual`/`formatarPercentual`/`desmascararPercentual` em `web/src/lib/masks.ts`) |

`id_empresa` não aparece no formulário. `id_funcionario`, `criado_em` e `atualizado_em`
aparecem como **campos informativos somente leitura** no fim do formulário — ver "Informações
do registro" abaixo.

**Layout do formulário (2026-07-21):** três linhas simétricas de 12 colunas cada —
**Nome** sozinho ocupando a linha inteira, **CPF + Celular** dividindo a linha (6+6) e
**Cargo + % Comissão** dividindo a linha (8+4). O CPF ficou junto do Celular na seção
"Identificação" (não há seção "Contato" separada, como no Cliente — com só um campo de
contato não se justificava uma seção própria).

**CPF reaproveita o validador do Cliente.** `Documentos` (antes package-private em
`cadastros.cliente`) foi tornado `public` especificamente para isso — evita duplicar o
algoritmo de dígito verificador. Funcionário é sempre pessoa física, então só usa
`Documentos.valido()`/`somenteDigitos()`; a lógica de CNPJ alfanumérico (`somenteAlfanumerico`,
ver `docs/telas/cliente.md`) não se aplica aqui.

**Maiúsculas:** nome e cargo são normalizados para MAIÚSCULAS (onChange no front + defesa em
profundidade no backend), mesma convenção do projeto inteiro (§3.7).

**Validação por campo:** nome/CPF/celular/% comissão validam ao sair do campo (`blur`) e de
novo no submit, mesmo padrão do Cliente. Sem checagem assíncrona de duplicidade (diferente do
Cliente) — CPF de funcionário não precisa ser único.

**Validação replicada no backend:** `FuncionarioService.validar` cobre formato de CPF/celular,
faixa de % comissão (0–100) e a obrigatoriedade configurável por tenant (`cfg_tela_campo`,
chave `cadastros.funcionario.form`) — mesma defesa em profundidade do Cliente.

**Cabeçalho com Cancelar/Salvar no topo, layout fixo:** título **"Funcionário"** com ícone de
identificação (maleta) à esquerda, Cancelar/Salvar ao lado dos ícones de ajuda/configuração,
topo fixo e só o corpo do formulário rola — mesma arquitetura (`.lista-tela`/`.lista-topo`/
`.lista-corpo`) do Cliente.

**Informações do registro (2026-07-21) — convenção de todo o projeto.** Última seção do
formulário, com os campos de auditoria gerados pelo banco: **Código** (`id_funcionario`),
**Cadastrado em** (`criado_em`) e **Última alteração** (`atualizado_em`). São **somente
leitura** (`readOnly` + `tabIndex={-1}`, fora da navegação por Tab, com visual apagado
`.campo-leitura`) — nunca editáveis, nem no modo de edição. A seção **some ao incluir** um
registro novo, quando ainda não existem valores. Ver "Convenção" em `docs/telas/cliente.md`.

## Tela de listagem

- **Colunas:** Nome, CPF, Celular, Cargo, % Comissão, Status (Ativo/Inativo).
- **Ordenação por coluna:** qualquer cabeçalho é clicável e ordena ASC/DESC; default é `nome`
  ASC, empate resolvido por `id_funcionario`. Cabeçalho em destaque visual com ícone "⇅"/
  "▲"/"▼" — mesmo componente visual do Cliente. Backend:
  `GET /api/v1/funcionarios?ordenarPor=&direcao=`, allowlist de colunas.
- **Busca:** por nome (contém, digitação sempre em maiúsculas).
- **Filtros:** por status (Ativo/Inativo/Todos — default só Ativos). Sem filtro de categoria
  (não existe categoria de funcionário).
- **Paginação:** por número de página (`GET /api/v1/funcionarios?pagina=&limite=`), janela
  deslizante com primeira/anterior/próxima/última, **tamanho fixo em 50 itens** — idêntico ao
  Cliente.
- **Layout fixo:** cabeçalho (título + filtros) e rodapé (contagem + paginação) não rolam; só
  a tabela tem scroll próprio.
- **Grid compacta:** mesmo `.table-compacta` do Cliente.
- **Ações por linha:** três ícones — **verde** (olho) visualizar em modo somente-leitura
  (`/funcionarios/:id/visualizar`), **azul** (lápis) editar, **vermelho** (lixeira) excluir.

## Exclusão de funcionário

Mesma decisão de produto do Cliente: **tenta excluir de verdade; se houver vínculo, inativa
em vez de excluir.** Vínculo aqui é com `produto_movimento_detalhe.id_funcionario` (ledger de
estoque, V019) em vez de `venda` — funcionário não se liga direto a vendas, só a itens
movimentados (comissão por item).

- `DELETE /api/v1/funcionarios/{id}` verifica antes se existe `produto_movimento_detalhe`
  vinculado (mesmo motivo do Cliente: FK violada aborta o resto da transação no Postgres).
- Com vínculo: `UPDATE funcionario SET ativo = false`, respondendo `200 OK` com
  `{"acao": "inativado", "motivo": "Funcionário possui movimentações associadas."}`.
- Sem vínculo: `DELETE` de verdade, `{"acao": "excluido", "motivo": null}`.

## Critérios de aceitação (viram testes)

- Dado um funcionário só com nome, quando salvo, então é aceito (demais campos opcionais).
- Dado um CPF com dígito verificador incorreto, quando salvo, então a API rejeita.
- Dado um celular com menos de 11 dígitos ou 3º dígito diferente de 9, quando salvo, então a
  API rejeita.
- Dado um percentual de comissão maior que 100 (ou negativo), quando salvo, então a API
  rejeita.
- Dado dois funcionários do mesmo tenant com o mesmo CPF, quando ambos são salvos, então
  **ambos são aceitos** (CPF não é único, ao contrário do cliente).
- Dado um campo marcado obrigatório na configuração de tela (ex.: Cargo), quando salvo vazio,
  então a API rejeita mesmo que o frontend não tenha validado.
- Dado um funcionário com movimentação de estoque associada, quando o usuário tenta excluir,
  então é inativado (não excluído) e a UI informa o motivo.
- Dado um funcionário sem nenhum vínculo, quando excluído, então o registro deixa de existir.
- Dado um usuário `OPERADOR`, quando cadastra/edita/exclui um funcionário, então a ação é
  permitida (não é restrito a `ADMIN`).
- Dado nome/cargo digitados em minúsculas, quando salvos, então são armazenados em
  MAIÚSCULAS.
- [x] caso feliz  [x] erro  [x] borda (CPF duplicado aceito, vínculo em movimento, campo
  configurável obrigatório)  [x] o que NÃO deve acontecer (excluir com movimento associado
  sem inativar)

Cobertos por `FuncionarioCrudTest` (10 testes) — suíte completa do projeto em 43/43 verdes.

## Impacto no contrato de API

```
GET    /api/v1/funcionarios?nome=&status=&pagina=&limite=&ordenarPor=&direcao=
                                              lista paginada (número de página), busca/filtro/ordenação
POST   /api/v1/funcionarios                  cria funcionário
GET    /api/v1/funcionarios/{id}             detalhe
PUT    /api/v1/funcionarios/{id}             atualiza funcionário
DELETE /api/v1/funcionarios/{id}             exclui; fallback para inativar se houver vínculo

GET    /api/v1/config-tela/cadastros.funcionario.form   configuração de campos (reaproveita o endpoint genérico)
PUT    /api/v1/config-tela/cadastros.funcionario.form   idem, só ADMIN
```

Todos sob `/api/v1/**` (JWT de tenant, RLS ativo — P8). Erros em Problem Details (RFC 9457).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `cadastros.funcionario.lista`**
  - Objetivo: encontrar e gerenciar os funcionários já cadastrados.
  - Passos: (1) busque por nome; (2) filtre por status; (3) use os ícones de
    visualizar/editar/excluir.
  - Erros comuns: "não consigo excluir" → o funcionário tem movimentações associadas e foi
    inativado em vez de excluído.
  - `url_video`: `NULL` por ora.
- **`chave_tela`: `cadastros.funcionario.form`**
  - Objetivo: cadastrar um funcionário novo ou editar um existente.
  - Passos: (1) preencha o nome (único campo obrigatório por padrão); (2) CPF/celular/cargo/%
    comissão são opcionais; (3) salve.
  - Erros comuns: "CPF inválido" → confira os dígitos (não precisa ser único); "celular
    inválido" → precisa ter 11 dígitos (DDD + 9XXXX-XXXX).
  - `url_video`: `NULL` por ora.

## Impacto no banco

Nenhuma migration nova — `funcionario` já existia (V016), com RLS ativo (V024). Nenhuma
alteração de schema foi necessária para esta feature.

## Impacto nas integrações

Nenhum. Funcionário não se comunica com adapters de canal.

## Non-goals desta feature

- Categoria/tabela de cargos pré-cadastrados (cargo é texto livre).
- Seletor de empresa (só existe uma por tenant no v1 — `id_empresa` preenchido
  automaticamente).
- Cálculo de comissão em si (o percentual só é armazenado; a lógica de aplicar comissão sobre
  vendas fica para quando o módulo de vendas/relatórios existir).
- Importação de funcionários via planilha.

## Questões abertas

Nenhuma bloqueante.

## Métrica de sucesso

Tempo de cadastro de um funcionário novo (só nome) em menos de 10 segundos — ainda mais
rápido que o cliente, por ter menos campos obrigatórios por natureza.
