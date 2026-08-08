# Achado de segurança: RLS sozinho não bastou para isolar tenant   Status: Corrigido
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-08 · Módulo(s): todos (cross-cutting) · Fase: —

## O que aconteceu

Durante a implementação de cor/grade/tamanho (`docs/telas/produto.md`), um teste novo
(`CorGradeTamanhoCrudTest.isolamentoEntreTenants`) reproduziu, de forma determinística, uma linha
de outro tenant sendo lida/alterada por uma query que dependia **só** da política RLS (P8) —
sem nenhum `WHERE id_tenant = plataforma.tenant_atual()` escrito diretamente no SQL. Testado
também contra código antigo e não tocado nesta feature (`CategoriaProdutoService`, já existente
desde 2026-07-22) — vazou do mesmo jeito. Não é um bug do código novo; é uma lacuna estrutural em
como o resto do projeto vinha escrevendo SQL.

**Causa raiz não totalmente diagnosticada** (suspeita: cache de plano genérico do
Postgres/JDBC interagindo com a qual do RLS entre transações com valores diferentes de
`app.id_tenant`), mas **100% corrigida, de forma reproduzível, com defesa em profundidade**:
adicionar o filtro explícito `id_tenant = plataforma.tenant_atual()` (ou `= ?` com o valor já
resolvido) diretamente no texto do SQL, além da política RLS.

## Diretriz (P8, reforçada)

> **Toda query de domínio (SELECT/UPDATE/DELETE) deve filtrar `id_tenant` explicitamente no SQL,
> mesmo que a tabela já tenha RLS.** RLS continua sendo a rede de segurança (P8 original) — a
> query explícita é a primeira linha de defesa, não uma opcional.

Isso vale para:
- `WHERE` de `listar()`/`buscar()`/`atualizar()`/`excluir()` em todo service de cadastro.
- Subqueries `EXISTS`/`IN` usadas em checagem de dependência antes de excluir.
- `JOIN`s entre duas tabelas com RLS — o `ON` precisa igualar `id_tenant` dos dois lados
  (`t1.id_tenant = t2.id_tenant`), não só o RLS de cada tabela isoladamente.
- Qualquer lookup por PK vindo de um claim do JWT (ex.: `EuController` buscando `usuario`/
  `empresa` pelo `id_usuario`/`id_empresa` do token) — mesmo sendo "óbvio" que o dado é do próprio
  tenant, o filtro explícito é a defesa, não uma formalidade.
- **Caminho de autenticação** (`SignupService.login`) — aqui um vazamento não é "leitura indevida
  de outro tenant", é **autenticação cross-tenant** (usuário de um tenant logando como se fosse de
  outro). Prioridade máxima de correção.

`INSERT`s não precisam do mesmo tratamento — já usam `plataforma.tenant_atual()` diretamente no
`VALUES`, não dependem do RLS pra isolar (o valor gravado é sempre o do contexto atual).

## Arquivos corrigidos nesta rodada (auditoria completa de `api/src/main`)

- `catalogo/ProdutoService.java`, `catalogo/CategoriaProdutoService.java`
- `catalogo/CorService.java`, `catalogo/TamanhoService.java`, `catalogo/GradeService.java`,
  `catalogo/ProdutoBarraService.java` (novos nesta mesma sessão — já nasceram com o filtro)
- `cadastros/funcionario/FuncionarioService.java`
- `cadastros/fornecedor/FornecedorService.java`
- `cadastros/cliente/ClienteService.java`, `cadastros/cliente/CategoriaClienteService.java`
- `comum/web/EuController.java`
- `plataforma/onboarding/SignupService.java` (`login()` — o de maior risco, ver acima)

Padrão de correção usado em todos (idêntico, pra manter consistência):
```java
// listar()
StringBuilder filtro = new StringBuilder(" WHERE x.id_tenant = plataforma.tenant_atual()");

// buscar()
SELECT_BASE + " WHERE x.id_tenant = plataforma.tenant_atual() AND x.id_x = ?"

// atualizar() / UPDATE
"UPDATE x SET ... WHERE id_x = ? AND id_tenant = plataforma.tenant_atual()"

// excluir() — EXISTS de dependência + inativar + DELETE, todos com o mesmo filtro
```

Verificado: suíte de testes completa (`./mvnw test`) segue em **405/405**, 0 falhas/erros, após
todas as correções.

## Pendência para revisões futuras

Esta auditoria cobriu `api/src/main` inteiro nesta rodada (3 agentes de busca em paralelo), mas
**qualquer service novo escrito a partir de agora deve nascer já com o filtro explícito** — não é
um item de checklist pontual, é convenção permanente (ver `CLAUDE.md`, seção "Conventions to
honor when building").
