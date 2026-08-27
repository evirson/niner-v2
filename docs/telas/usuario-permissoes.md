# Spec: Permissões do usuário (RBAC)               Status: Aprovada — implementada (partes 1 e 2)
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

- **`cfg_tela`** — catálogo das telas (57 hoje; 48 concedíveis) (chave, nome, grupo, `admin_apenas`). Global, sem RLS: a
  lista de telas é do **produto**, igual para todos; o que é por tenant é a permissão. Gerado a
  partir de `web/src/lib/menu.ts`, que até aqui era a única lista real de telas — e que o backend
  não enxergava.
- **`usuario_permissao`** — `(id_tenant, id_usuario, chave_tela)` + as 4 marcas. **Ausência de
  linha = sem acesso.**

⚠️ **CHECK no banco:** incluir/alterar/excluir sem `acessar` é recusado. A combinação não faz
sentido e a tela mostraria "pode excluir" para quem nem abre a tela.

⚠️ **Tela `admin_apenas` não é concedível**, e desde 2026-08-27 **nem aparece na grade**. O `PUT`
continua recusando com 400 nomeando a tela, como defesa contra cliente de API. Isto foi descoberto
**testando ao vivo**: antes da trava, uma tela exclusiva era gravada na grade e aparecia no menu do
operador, para a rotina negar depois — o usuário culparia o sistema, não a permissão.

## Telas e endpoints

| Endpoint | Quem usa |
|---|---|
| `GET /api/v1/telas` | monta a grade |
| `GET /api/v1/eu/permissoes` | o **próprio** usuário — o front usa para o menu e os botões |
| `GET|PUT /api/v1/usuarios/{id}/permissoes` | quem concede: o administrador **ou** quem tem a tela Usuários com "alterar" (ver o teto, adiante) |

A tela: `/usuarios/:id/permissoes`, alcançada pelo ícone de cadeado na lista de Usuários — que
**não aparece para o administrador**, porque não há o que configurar nele.

O `PUT` **substitui a grade inteira**. Mandar só o que mudou traria o modo de falha clássico: uma
permissão desmarcada que não chega ao servidor continuaria valendo.

## Critérios de aceitação

- **Dado** um administrador, **então** todas as telas vêm liberadas, sem nenhuma linha gravada.
- **Dado** um usuário recém-criado, **então** ele não acessa **nenhuma** tela.
- **Dado** PDV com incluir e Clientes com alterar, **então** é exatamente isso que ele recebe — nem
  incluir em Clientes, nem alterar no PDV.
- **Dado** um pedido para conceder uma tela exclusiva (ex.: **Empresas**), **então** 400 citando a tela.
- **Dado** um operador **sem** a tela Usuários, **então** ele não lê nem grava permissões (403). Com a tela, ele grava — limitado pelo teto, e nunca a própria grade.
- **Dado** o admin de outra conta, **então** 404 ao tentar configurar usuário alheio (P8).
- **Dado** um segundo `PUT` menor, **então** o que ficou de fora **deixa de valer**.

Testes: `api/src/test/java/com/vetor/niner/PermissaoPorTelaTest.java` (12 casos).

## Parte 2 — a trava no servidor (feita no mesmo dia)

O menu escondido evita o erro honesto; não evita quem digita a URL. Agora um **interceptor**
confere a permissão antes de cada rotina de `/api/v1`.

- **55 controllers** declaram `@Tela("chave")`. Uma linha por arquivo.
- Tradução padrão: **GET→acessar, POST→incluir, PUT/PATCH→alterar, DELETE→excluir**.
- **8 métodos declaram `@Acao(EXCLUIR)`** porque o verbo mente: cancelar venda, cancelar entrada,
  cancelar devolução ao fornecedor, estornar crediário, reabrir caixa, desfazer e zerar contagem
  são todos POST. Regra dele: **desfazer é excluir** — assim o caixa pode vender sem poder
  cancelar, que é a separação que interessa a quem configura permissão.

⚠️ **`@Livre` marca 10 endpoints** que já eram abertos a qualquer papel antes do RBAC — o javadoc
de cada um dizia isso, em português. O caso que obrigou a criar a anotação: o **PDV** lê
`/config-geral/*` (tela de ADMIN) para montar a venda. Sem `@Livre`, **o caixa não conseguiria
vender**.

⚠️ **Controller sem `@Tela` não é bloqueado**, por decisão dele: são as consultas auxiliares entre
domínios (o PDV busca cliente pela API de Clientes). O custo, dito claramente: quem entra no
sistema consegue **ler** dado auxiliar da própria conta sem ter a tela; o que ele não consegue é
**abrir a tela** nem **criar, alterar ou excluir** por ela.

### Dois defeitos meus, ambos silenciosos

1. **`exigir()` chamava `pode()` no próprio bean.** Auto-invocação não passa pelo proxy do Spring,
   então o `@Transactional` de `pode` era ignorado, a consulta rodava sem transação, o
   `SET LOCAL app.id_tenant` nunca acontecia e `plataforma.tenant_atual()` vinha NULL. Resultado
   medido: **403 para quem TINHA a permissão gravada**, sem nada em log. É a armadilha já
   documentada no projeto, num caminho novo.
2. **O helper de teste filtrava com JsonPath booleano** (`$[?(@.adminApenas == false)]`), recebia
   lista vazia, concedia nada — e o PUT respondia **200**. O teste falhava 400 linhas depois, com
   um 403 que parecia bug do RBAC.

## As 9 telas exclusivas do administrador

Revisadas por ele **tela a tela** (V078). Ficam exclusivas as que mexem na **conta** e as de
**carga de implantação**:

Canais de Venda · Minha Conta · Empresas · Importação de Clientes, Contas a Receber, Fornecedores,
Produtos e Estoque Inicial · Exportação de Dados

⚠️ **Exclusiva não aparece na grade.** Antes elas eram listadas e o salvamento recusava — ao
clicar em "liberar tudo", ele recebeu um erro citando 22 telas que nem sabia estarem ali.
Oferecer o que o sistema depois recusa é convidar ao erro.

⚠️ **O menu precisou mudar junto:** o grupo Configurações era ADMIN-only **em bloco**, então o
operador não veria as telas recém-liberadas (Parâmetros do Sistema, o bloco Fiscal) nem tendo
permissão. Hoje a marca é item a item e espelha `cfg_tela.admin_apenas`.

⚠️ **Tela fora do catálogo não é controlada por permissão** e continua no menu — hoje são as de
"Implementações Futuras". Tratar ausência como "proibida" faria qualquer tela nova sumir do menu
no dia em que fosse criada, sem erro nenhum.


## Ninguém delega o que não tem (2026-08-27)

Regra dele: *"as permissões que ele pode delegar para este novo usuário são no máximo as
permissões que ele tem"*. Configurar permissão deixou de ser exclusivo do administrador — passa a
poder quem tem a tela **Usuários** com "alterar".

⚠️ **O teto sozinho seria contornável em dois passos**, então vêm três travas juntas:

1. **Teto** — não concede tela que não tem, nem ação que não tem dentro de uma tela que tem (quem
   não pode excluir produto não dá "excluir produto" a ninguém).
2. **Não edita a própria grade** — senão marca tudo para si e o teto vira decoração.
3. **Não edita quem tem mais permissão que ele** — senão tira o acesso de um colega mais graduado,
   ou usa a conta dele como degrau.

O administrador não passa por nada disso: o teto dele é o sistema inteiro.

⚠️ **A grade mostra só o que ele pode delegar.** Para quem não é admin, as telas e as ações fora
do seu alcance **não aparecem** — mostrar tudo e recusar no Salvar seria repetir o incômodo do
"liberar tudo": marcar dezenas de itens e descobrir no fim que metade não podia.

## As travas antigas removidas

Dez telas apareciam na grade mas tinham uma segunda tranca escrita no serviço ("só ADMIN"),
anterior ao RBAC. Conceder a tela não surtia efeito: o operador via o item no menu, clicava e
recebia acesso negado. **34 chamadas de `exigirAdmin` foram removidas** de Usuários, Parâmetros do
Sistema e do bloco Fiscal inteiro — quem decide agora é a grade.

Três casos especiais, decididos por ele tela a tela:

| Tela | Decisão |
|---|---|
| Fechamento de Caixa | operador fecha o próprio; **reabrir continua só do administrador** |
| Pesquisa de Vendas | operador **pode cancelar**, desde que o caixa esteja aberto (RN-02, que já existia) |
| Entrada de Produtos | operador faz **tudo** |

## Estreia sem backfill

Ligar a trava faz todo usuário sem grade receber 403 — e nenhuma grade existia. Como o Nainer
está em **homologação**, sem cliente real, ninguém foi afetado, e o comportamento de estreia é o
definitivo: usuário novo não enxerga nada até ser liberado.

⚠️ **No dia da produção isto muda:** se já houver operadores em uso, é obrigatório conceder a
grade cheia a eles **antes** de a trava subir, senão perdem o acesso sem aviso.
