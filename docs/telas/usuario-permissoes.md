# Spec: Permissões do usuário (RBAC)               Status: Aprovada — parte 1 de 2
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-27 · Módulo(s): `identidade.permissao` · Fase: 1 — Núcleo do ERP

## Problema

O ERP tinha **dois papéis fixos no código**: ADMIN (um por conta, imutável) e OPERADOR. Quem não
era admin entrava em **tudo** que não estivesse marcado como ADMIN-only — 8 telas de 63. Um caixa
enxergava (e usava) Contas a Pagar, Devolução, Transferência de Estoque, Cancelamento de Venda.

## Decisões do dono do produto (2026-08-27)

1. **Permissão por tela E por ação:** acessar, incluir, alterar, excluir.
2. **O administrador pode tudo**, é um só por conta e não se configura.
3. **⛔ Sem perfis.** Palavras dele: *"montar perfis de usuários eu não acho uma boa ideia, pq às
   vezes para um tenant o estoquista pode fazer uma coisa, e no outro tenant vai fazer coisas
   diferentes"*. A grade é do **usuário**.
4. **Usuário novo nasce sem nada** e o admin libera tela a tela.

⭐ O ponto 3 já estava previsto no banco desde a V015: `usuario_rotina` (permissão presa ao usuário,
sem perfil no meio) nasceu vazia esperando o produto definir a granularidade. A V073 a substitui
por `usuario_permissao`, agora com as quatro ações.

## Modelo (V073)

- **`cfg_tela`** — catálogo das 63 telas (chave, nome, grupo, `admin_apenas`). Global, sem RLS: a
  lista de telas é do **produto**, igual para todos; o que é por tenant é a permissão. Gerado a
  partir de `web/src/lib/menu.ts`, que até aqui era a única lista real de telas — e que o backend
  não enxergava.
- **`usuario_permissao`** — `(id_tenant, id_usuario, chave_tela)` + as 4 marcas. **Ausência de
  linha = sem acesso.**

⚠️ **CHECK no banco:** incluir/alterar/excluir sem `acessar` é recusado. A combinação não faz
sentido e a tela mostraria "pode excluir" para quem nem abre a tela.

⚠️ **Tela `admin_apenas` não é concedível**, nem por engano. O `PUT` **recusa com 400 nomeando a
tela** — ignorar em silêncio faria o admin achar que concedeu. Isto foi descoberto **testando ao
vivo**: antes da trava, a DRE era gravada na grade e aparecia no menu do operador, para a rotina
negar depois — o usuário culparia o sistema, não a permissão.

## Telas e endpoints

| Endpoint | Quem usa |
|---|---|
| `GET /api/v1/telas` | monta a grade |
| `GET /api/v1/eu/permissoes` | o **próprio** usuário — o front usa para o menu e os botões |
| `GET|PUT /api/v1/usuarios/{id}/permissoes` | o **administrador**, para conceder (ADMIN-only) |

A tela: `/usuarios/:id/permissoes`, alcançada pelo ícone de cadeado na lista de Usuários — que
**não aparece para o administrador**, porque não há o que configurar nele.

O `PUT` **substitui a grade inteira**. Mandar só o que mudou traria o modo de falha clássico: uma
permissão desmarcada que não chega ao servidor continuaria valendo.

## Critérios de aceitação

- **Dado** um administrador, **então** todas as telas vêm liberadas, sem nenhuma linha gravada.
- **Dado** um usuário recém-criado, **então** ele não acessa **nenhuma** tela.
- **Dado** PDV com incluir e Clientes com alterar, **então** é exatamente isso que ele recebe — nem
  incluir em Clientes, nem alterar no PDV.
- **Dado** um pedido para conceder a **DRE**, **então** 400 citando a tela.
- **Dado** um operador, **então** ele não lê nem grava permissões (403), nem as próprias.
- **Dado** o admin de outra conta, **então** 404 ao tentar configurar usuário alheio (P8).
- **Dado** um segundo `PUT` menor, **então** o que ficou de fora **deixa de valer**.

Testes: `api/src/test/java/com/vetor/niner/PermissaoPorTelaTest.java` (8 casos).

## ⛔ Parte 2 — o que ainda NÃO está protegido

O que existe hoje é: **o modelo, a concessão e o menu**. O menu esconde o que o usuário não pode
acessar, e a grade está gravada e é respeitada por `PermissaoService`.

**Falta aplicar `PermissaoService.exigir(...)` nas rotinas.** Enquanto isso não for feito, um
usuário sem permissão **não vê a tela no menu, mas alcança o endpoint** se souber a URL ou chamar a
API direto. Ou seja: hoje isto é **controle de interface**, não de segurança.

⚠️ Dizer isso claramente importa mais que a funcionalidade em si: um RBAC que parece proteger e não
protege é pior que nenhum, porque o administrador confia nele para separar responsabilidades.

O caminho previsto é uma anotação por controller (`@Tela("vendas.pdv")`) + interceptor, mapeando o
método HTTP para a ação (GET→acessar, POST→incluir, PUT→alterar, DELETE→excluir), com as exceções
declaradas caso a caso — há rotinas em que POST não é inclusão (efetivar, cancelar, estornar), e
essas precisam ser decididas uma a uma, não por convenção.
