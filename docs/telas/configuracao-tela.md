# Spec: Configuração de Tela (campos visíveis/obrigatórios)   Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-07-21 · Módulo(s): `comum.telaconfig` (reutilizável) · Fase: 1 — Núcleo do ERP

## Problema

Lojistas diferentes usam campos diferentes do cadastro de cliente (um usa Instagram, outro
não; um exige e-mail sempre preenchido, outro nunca pede). Até aqui, quais campos aparecem e
quais são obrigatórios era uma decisão fixa no código, igual para todo mundo.

## Solução proposta

Uma tela de configuração, acessível só por `ADMIN`, onde o lojista escolhe **por tenant**
quais campos de um formulário aparecem e quais são obrigatórios. A estrutura de banco e a API
são **genéricas** — servem para o formulário de Cliente hoje e para qualquer tela nova no
futuro (Fornecedor, Funcionário, Produto...), reaproveitando a mesma convenção de
`chave_tela` já usada pelo catálogo de ajuda (R22/§3.7.1, `AjudaDaTela`).

## User stories

- Como **gestor**, quero ocultar campos que minha loja não usa (ex.: TikTok), para o
  formulário ficar mais enxuto para minha equipe.
- Como **gestor**, quero exigir e-mail em todo cadastro de cliente, mesmo que o sistema não
  exija por padrão.
- Como **operador**, não quero (nem devo) conseguir mudar essa configuração — só o gestor
  decide o que é obrigatório.

## Campos configuráveis (tela `cadastros.cliente.form`)

`Nome` e `Categoria` **não aparecem aqui** — são estruturalmente obrigatórios (`NOT NULL` no
banco, V016) e sempre visíveis; não fazem sentido como opção de configuração. Todos os
demais campos "de negócio" do formulário são configuráveis:

CPF/CNPJ, RG/Inscrição Estadual, E-mail, Celular, Id. WhatsApp, Instagram, Facebook, TikTok,
CEP, Endereço, Número, Complemento, Bairro, Cidade, UF, Limite de crédito.

`Data de nascimento` e `Gênero` **também não entram**: têm regra própria já fechada com o
dono do produto (nascimento sempre opcional; gênero obrigatório só para pessoa física,
oculto para jurídica — ver `docs/telas/cliente.md`) — colocá-los num sistema de config
genérico contradiria essa regra.

## Regras

- Um campo **não pode ser obrigatório se estiver oculto** (`CHECK` no banco + validado na
  API + a UI desabilita o checkbox "Obrigatório" quando "Visível" está desmarcado).
- Tenant **sem nenhuma configuração salva** (o caso comum, recém-assinado) usa o **default da
  tela**: todo campo configurável começa **visível** e **não obrigatório** — idêntico ao
  comportamento antes desta feature existir.
- Só **ADMIN** grava configuração (`PUT`); qualquer usuário do tenant **lê** (`GET`) — o
  formulário precisa saber como se renderizar independente do papel de quem está logado.
- O ícone de engrenagem (Heroicons `cog-6-tooth`, ao lado do ícone de ajuda — 2026-07-21) só
  aparece para `ADMIN`; `OPERADOR` que
  tentar acessar `/clientes/configuracao` diretamente pela URL é redirecionado ao Painel
  (`RequireAdmin`, front) — e a gravação seria rejeitada (`403`) mesmo que o front fosse
  contornado (checagem também no backend, a partir do claim `roles` do JWT).

## Critérios de aceitação (viram testes)

- Dado um tenant sem nenhuma configuração salva, quando consulta `GET /api/v1/config-tela/
  cadastros.cliente.form`, então todo campo vem `visivel=true`/`obrigatorio=false`.
- Dado um ADMIN que salva `email.obrigatorio=true`, quando consulta de novo, então o valor
  salvo é devolvido (e não mais o default).
- Dado um OPERADOR, quando tenta `PUT /api/v1/config-tela/{chaveTela}`, então recebe `403`.
- Dado um `PUT` com um campo que não existe no registro da tela (ex.: `"nome"`, que não é
  configurável), então é rejeitado com `400`.
- Dado um `PUT` com `obrigatorio=true` e `visivel=false` para o mesmo campo, então é
  rejeitado com `400`.

## Impacto no contrato de API

```
GET /api/v1/config-tela/{chaveTela}   lista a config atual (mescla salvo + default); qualquer usuário do tenant
PUT /api/v1/config-tela/{chaveTela}   salva em lote (upsert); só ADMIN (403 para OPERADOR)
```

Corpo do `PUT`: lista de `{"campo": "email", "visivel": true, "obrigatorio": true}`.

## Impacto no banco

**Migration nova** (`V027__cfg_tela_campo.sql`) — não mexe em nenhuma migration já aplicada;
o dev não precisou recriar o banco (`docker compose run --rm flyway` aplicou só a V027).

```sql
cfg_tela_campo(id_tenant FK, chave_tela, campo, visivel BOOL, obrigatorio BOOL, atualizado_em,
               PK(id_tenant, chave_tela, campo),
               CHECK (NOT obrigatorio OR visivel))
```

RLS próprio no arquivo (nasce depois do guarda-corpo de V024, mesmo padrão de V025/V026/V027).
O registro de quais campos são configuráveis em cada `chave_tela` é **estático no backend**
(`ConfiguracaoTelaService.CAMPOS_POR_TELA`) — cada tela nova que ganhar essa configuração
entra nesse mapa; não é uma tabela de metadados separada (YAGNI até existir uma segunda tela
configurável de verdade).

## Ajuda da tela — obrigatório (R22 / §3.7.1)

Esta tela ainda **não tem** `AjudaDaTela` (ficou de fora nesta primeira versão — é uma tela
de configuração simples, de uso raro, para ADMIN). 🔴 Adicionar `chave_tela:
cadastros.cliente.configuracao` quando a tela ganhar mais uso ou complexidade.

## Non-goals desta feature

- Configuração de ordem/layout dos campos (só visibilidade/obrigatoriedade).
- Configuração de campos estruturalmente obrigatórios (Nome, Categoria) ou com regra de
  negócio própria (Data de nascimento, Gênero).
- Tabela de metadados dinâmica de "quais campos existem em quais telas" — o registro é
  estático no código por ora.

## Questões abertas

Nenhuma bloqueante.

## Métrica de sucesso

Nenhum lojista precisa pedir suporte/desenvolvimento para esconder um campo que não usa ou
exigir um campo que considera essencial.
