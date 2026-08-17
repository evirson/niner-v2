# Spec: Perfis Fiscais (cfg_perfil_fiscal)                          Status: Rascunho
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-17 · Módulo(s): `fiscal.perfil` · Fase: F1/F2 — Fundação + Motor (bloco B2)

## Problema

A **DF3** decidiu que a inteligência tributária mora no Niner: o XML sai pronto, sem provedor
externo. Isso significa que alguém precisa dizer, para cada produto, qual CFOP, qual CST/CSOSN e
quais alíquotas usar — e isso varia por regime do emitente, UF de destino, tipo de destinatário e
tipo de operação.

Espalhar isso em colunas de `produto` seria inviável: 10.000 produtos × 3 CRT × N UFs. A saída é
o **perfil reutilizável** — um perfil, centenas de produtos, uma correção só. `cfg_perfil_fiscal` e
`cfg_perfil_fiscal_regra` já existem (V035); falta a tela.

## Solução proposta

**Padrão de cadastro consolidado** (`docs/telas/cliente.md`), com uma coleção filha ordenada por
especificidade — mesmo mecanismo de "apaga e reinsere a cada save" já usado em
`TipoCarteiraService` e `ProdutoService.salvarCategorias`.

**Acesso: somente ADMIN.** Uma regra errada aqui vira nota errada em todos os produtos que apontam
para o perfil; não é edição de operador. Item de menu com `adminOnly`.

Herda tudo do padrão: paginação por página fixa em 50 com janela deslizante; cabeçalhos ordenáveis
(allowlist no backend, nunca concatenar a coluna do cliente no SQL); três ícones de ação (verde ver →
`/fiscal/perfis/:id/visualizar` em `<fieldset disabled>`, azul editar, vermelho excluir); exclusão com
fallback para inativar; maiúsculas em texto livre; `AjudaDaTela`; ✕ de fechar via `navigate(-1)`;
Enter navega como Tab; `autoFocus` na busca; `InfoRegistro` no fim do formulário.

## Estrutura: cabeçalho + grade de regras

### Cabeçalho (`cfg_perfil_fiscal`)

| Campo | Rótulo | Componente | Regra |
|---|---|---|---|
| `nome` | Nome do perfil | texto (maiúsculas) | Obrigatório, único por tenant (`cfg_perfil_fiscal_nome_uk`) |
| `descricao` | Descrição | texto (maiúsculas) | Opcional |
| `ativo` | Ativo | checkbox | Default `true` |

### Grade de regras (`cfg_perfil_fiscal_regra`)

Cada linha é uma combinação de contexto → saída tributária. **Dimensões** (a chave única):

| Campo | Rótulo | Componente | Regra |
|---|---|---|---|
| `crt` | CRT | `<select>` | 1 Simples · 2 Simples com excesso de sublimite · 4 MEI (**o 3 não existe no produto** — DF37) |
| `uf_destino` | UF destino | `<select>` | `*` (qualquer, default) ou UF |
| `tipo_destinatario` | Destinatário | `<select>` | consumidor final · contribuinte · não contribuinte |
| `tipo_operacao` | Operação | `<select>` | venda · devolução · transferência · remessa · bonificação |

**Saída**, agrupada em três blocos no formulário da linha (popup, não inline — são ~15 campos):

**ICMS** — `cfop` (obrigatório, 4 dígitos), `cst_icms` **ou** `csosn` (ver abaixo), `aliquota_icms`,
`perc_reducao_bc`, `mva_st`, `aliquota_fcp`, `codigo_beneficio` (cBenef).

**PIS/COFINS** — `cst_pis`, `aliquota_pis`, `cst_cofins`, `aliquota_cofins`. Sem IPI: optante do
Simples recolhe IPI dentro do DAS e não destaca na saída (DF37).

**Reforma (IBS/CBS)** — `cst_ibscbs` (3 dígitos), `cclasstrib` (6 dígitos).

## As três regras de negócio que a tela precisa impor

### 1. CST ou CSOSN, nunca os dois — e o certo para o CRT

O banco garante duas coisas: um dos dois preenchido, nunca ambos nem nenhum
(`cfg_perfil_fiscal_regra_icms_ck`), e **CST só no CRT 2** (`cfg_perfil_fiscal_regra_icms_crt_ck`:
`crt = 2 OR cst_icms IS NULL`). A tela reflete isso:

| CRT da regra | Campo de ICMS exibido | O outro |
|---|---|---|
| 1 e 4 (Simples/MEI) | **CSOSN** (`<select>`: 102, 103, 202, 300, 400, 500, 900) | `cst_icms` fica nulo, campo escondido |
| **2** (excesso de sublimite) | **CSOSN ou CST**, escolha do contador — ver abaixo | o não escolhido fica nulo |

Trocar o CRT da linha troca o campo exibido e **limpa o valor do outro**. Validado de novo no
servidor — 400 se vier CST com CRT 1 ou 4.

⚠️ **Por que só o CRT 2 pode as duas coisas.** A empresa do Simples que estoura o sublimite estadual
recolhe **ICMS fora do DAS**, e o mercado diverge sobre se ela emite com o grupo `ICMSSN` (CSOSN) ou
com o grupo `ICMS` (CST) — está em aberto na F0 (`MODULOFISCAL.md` §8.2). Enquanto não houver
resposta oficial, **o ERP não escolhe pelo lojista**: os dois campos aparecem, com um texto de ajuda
explicando a divergência, e quem decide é o contador. Escolher por ele seria fingir uma certeza que
não existe — e o custo do palpite errado é rejeição sistemática em toda venda.

CSOSN 101 (crédito do Simples) fica **fora da lista** no v1 — DF31: exige a alíquota efetiva da
apuração do DAS, que o sistema não tem de onde ler.

### 2. PIS/COFINS — CST 99 é o caso normal, e o 01 é recusado

Todas as empresas do produto são Simples ou MEI (DF37), e nelas o PIS/COFINS está **dentro do DAS**.
O formulário precisa deixar isso óbvio, senão alguém digita uma alíquota "para não deixar zerado".

- `cst_pis`/`cst_cofins` = **99** (default para regra nova) ⇒ campos de alíquota **desabilitados e
  zerados**, com o texto *"Dentro do DAS — sem apuração separada"*.
- **04** (monofásico) e **06** (alíquota zero) ⇒ campos habilitam. É o tratamento próprio que o
  optante segrega da receita: bebida, combustível, autopeça, cesta básica, medicamento.
- **01** (saída tributada normal) ⇒ **fora da lista**. Só existe em Lucro Real e Presumido; o motor
  recusa com mensagem própria, porque num perfil do Simples ele destacaria PIS/COFINS **por cima do
  DAS** e a nota seria autorizada normalmente — o erro só apareceria na contabilidade.

> Aqui existia a **DF36** ("a alíquota vem do regime da empresa, não do perfil"). Ela foi **superada
> pela DF37**: sem Lucro Real e Presumido, não sobrou alíquota ad-valorem a decidir, e
> `regime_apuracao` saiu do schema. O que era "override" voltou a ser simplesmente a alíquota dos
> CST de tratamento próprio. Detalhe completo: `docs/MODULOFISCAL.md` §8.3.

### 3. Especificidade, e o vazio que o motor não perdoa

Na emissão, o motor escolhe a regra **mais específica** que casa: UF exata ganha de `*`. A grade
ordena por especificidade decrescente para o lojista enxergar a precedência do jeito que ela é
aplicada, não em ordem de digitação.

**Sem regra que case, o motor falha explicitamente** (F11) — nunca chuta um CFOP. Por isso a tela
avisa antes: ao salvar, se o perfil não tem nenhuma regra para o CRT de **alguma empresa do tenant
com fiscal ligado**, mostra um aviso (não bloqueia — o perfil pode estar sendo montado aos poucos):

> *"Este perfil não tem regra para CRT 2, usado pela empresa LOJA CENTRO. Produtos com este perfil não
> emitirão nota nessa empresa."*

## Exclusão

Padrão do projeto: tenta excluir, e **cai para inativar** quando há vínculo. Aqui o vínculo é
`produto.id_perfil_fiscal`.

- Nenhum produto aponta ⇒ `DELETE` real (regras vão junto).
- Algum produto aponta ⇒ **200**, o perfil é inativado automaticamente (`ativo = false`) e a
  resposta traz o motivo — mesmo mecanismo de `FornecedorService.excluir` (não é um 409 pedindo
  confirmação separada; a tela mostra o resultado depois de agir, como no resto do projeto).

Perfil inativo não aparece no `<select>` do cadastro de Produto, mas **continua valendo** para os
produtos que já apontam para ele — desativar não pode quebrar a emissão de quem estava emitindo. Quem
quiser realmente parar troca o perfil dos produtos primeiro.

## Perfis semeados no signup

🔴 **Proposta (decisão do dono do produto):** o signup semear dois perfis prontos, do mesmo jeito que
já semeia 6 tipos de carteira e 76 contas do plano de contas:

| Perfil | Regras | Uso |
|---|---|---|
| **REVENDA TRIBUTADA NORMAL** | CRT 1, 2 e 4 → CSOSN 102, CFOP 5.102 | O caso mais comum do varejo |
| **REVENDA COM ST RETIDO** | CRT 1, 2 e 4 → CSOSN 500, CFOP **5.405** | Confecção e calçado, muito comum |

Racional: sem isso, o onboarding começa com uma tela vazia e um lojista que não sabe o que é CSOSN.
Com isso, ele liga o fiscal e a maioria dos produtos já funciona. O risco é semear uma alíquota
errada — mitigado porque os dois perfis acima **não têm alíquota de ICMS fixa** (CSOSN 102 e 500 não
destacam ICMS), e a semente cobre só o CSOSN: a variante com CST do CRT 2 fica de fora justamente
porque depende da divergência ainda em aberto (§1) e exige decisão do contador.

## Critérios de aceitação (viram testes)

- Dado um ADMIN, quando cria um perfil com 4 regras, então o `GET` seguinte traz as 4 na ordem de
  especificidade.
- Dado um perfil salvo, quando é salvo de novo com 2 regras, então ficam exatamente 2 (apaga e
  reinsere), sem sobra da versão anterior.
- Dado uma regra com CRT 1 ou 4, quando salva com `cst_icms` preenchido, então 400.
- Dado uma regra com CRT 2, quando salva com `cst_icms` preenchido, então 200 (é o único CRT que pode).
- Dado uma regra sem `cst_icms` e sem `csosn`, quando salva, então 400 (o CHECK do banco não deve ser
  a primeira linha de defesa — o serviço rejeita antes).
- Dado duas regras com a mesma combinação (crt, uf, destinatário, operação), quando salva, então 400
  com mensagem legível — não a violação crua da UK.
- Dado um nome de perfil já usado no tenant, quando cria outro, então 409.
- Dado um perfil sem produto vinculado, quando excluído, então some do banco junto com as regras.
- Dado um perfil com produto vinculado, quando excluído, então 200 com `acao: "inativado"`, e o
  perfil vira `ativo = false`.
- Dado um perfil inativado, quando um produto já apontava para ele, então o produto continua
  apontando (o vínculo não é quebrado).
- Dado um OPERADOR, quando tenta listar ou gravar, então 403.
- Dado dois tenants distintos, quando um cria um perfil, então o outro não o enxerga nem consegue
  editá-lo por id (isolamento — `id_tenant` explícito, P8/F8).

Cobertos por `PerfilFiscalCrudTest` (novo).

## Impacto no contrato de API

```
GET    /api/v1/fiscal/perfis                  lista paginada + busca + ordenação (ADMIN)
GET    /api/v1/fiscal/perfis/{id}             perfil + regras (ADMIN)
POST   /api/v1/fiscal/perfis                  cria perfil + regras (ADMIN)
PUT    /api/v1/fiscal/perfis/{id}             atualiza; regras são substituídas por completo (ADMIN)
DELETE /api/v1/fiscal/perfis/{id}             exclui ou 409 com fallback para inativar (ADMIN)
GET    /api/v1/fiscal/perfis/opcoes           lista enxuta (id + nome) para o <select> de Produto
```

`/opcoes` existe **sem checagem de papel** (mesmo padrão de `/config-geral/usa-cor-grade`): o cadastro
de Produto é operado por `OPERADOR`, e usar o endpoint completo ADMIN-only só para preencher um
`<select>` é exatamente o bug de 2026-08-13 do plano de contas
([[feedback_select_truncado_por_paginacao]] e `docs/telas/entrada-mercadoria.md`). O `/opcoes` também
carrega a lista **inteira** (sem paginação), pelo mesmo motivo que o `SeletorPlanoContas` carrega 500:
um default fora da primeira página some em silêncio.

Toda query filtra `id_tenant` explicitamente no SQL além do RLS (P8/F8), inclusive o `EXISTS` de
`produto` na checagem de exclusão e o `JOIN` regra↔perfil (`r.id_tenant = p.id_tenant`).

## Ajuda da tela (R22 / §3.7.1)

Entrada `fiscal.perfil.tela` em `AjudaDaTela.tsx`: o que é um perfil e por que ele não fica no
produto; a diferença entre CST e CSOSN e por que só o CRT 2 pode escolher; por que a alíquota de
PIS/COFINS fica zerada e desabilitada no CST 99 (o caso normal — tributo dentro do DAS); e o que
significa "regra mais específica ganha".

## Impacto no banco

**Nenhuma migration nova** para a tela. `cfg_perfil_fiscal`, `cfg_perfil_fiscal_regra` e
`produto.id_perfil_fiscal` já existem (V035:132-193).

Se a proposta de **perfis semeados no signup** for aceita, `SignupService.assinar` ganha os dois
`INSERT`s (mesma transação do resto do seed) — sem mudança de schema.

## Non-goals desta feature

- **Calcular imposto** — é o motor (B4). Esta tela só cadastra a regra.
- **Tabelas nacionais** (`cfg_cfop`, `cfg_cest`, `cfg_cst_icms`, `cfg_cclasstrib`) — são referência
  global sem tela de manutenção, mesma exceção de `cfg_produto_ncm`. Esta tela as **consome** nos
  `<select>` quando existirem; enquanto não existirem, os campos são texto validado por formato.
- **Importar perfil de outro tenant / template de mercado** — futuro.
- **Simulador de tributos** — `GET /api/v1/fiscal/simular-tributos` é do motor (F2), não desta tela.

## Questões abertas

- 🔴 **Semear os dois perfis padrão no signup?** Recomendação acima é sim. Decisão do dono do produto.
- 🔴 **A tabela `cfg_cclasstrib` (~173 códigos) ainda não foi carregada.** Enquanto não for, o campo
  `cclasstrib` é texto de 6 dígitos sem validação contra lista. Vem no mesmo pacote que os XSD, pedido
  ao dono do produto junto com o certificado (§17.1).
- 🔴 **`tipo_operacao_fiscal` tem 11 valores no banco, mas o v1 usa 2** (`VENDA_CONSUMIDOR` e
  `DEVOLUCAO_VENDA` — DF35). Os outros nove (`VENDA_CONTRIBUINTE`, `VENDA_NAO_PRESENCIAL`,
  `TRANSFERENCIA`, `DEVOLUCAO_FORNECEDOR`, `REMESSA_CONSERTO`, `RETORNO_CONSERTO`, `BAIXA_PERDA`,
  `BONIFICACAO`, `COMPLEMENTAR`) já existem no `ENUM` do banco — acrescentar valor depois é
  `ALTER TYPE`, então nasceram todos juntos (§4.2) — mas são de operações futuras. **Recomendação:
  esconder no `<select>`** até existirem, para não convidar o lojista a cadastrar regra para uma
  operação que o sistema ainda não emite. A API aceita qualquer valor do enum (não é o front que
  garante a integridade) — só a tela restringe.

## Métrica de sucesso

Um lojista de calçados liga o fiscal, aponta os produtos para dois perfis, e emite NFC-e sem digitar
CFOP nem CST em nenhum produto individualmente.
