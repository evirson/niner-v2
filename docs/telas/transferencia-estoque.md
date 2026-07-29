# Spec: Transferência de Produtos Entre Empresas       Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-07-28 · Módulo(s): `estoque` (transferencia) · Fase: 1 — Núcleo do ERP

## Problema

O lojista com mais de uma empresa/loja (Q6, `empresa`) precisa mover produtos de uma loja para
outra — por exemplo, reabastecer uma filial com estoque de outra. O ledger de movimentação
(`produto_movimento_mestre/detalhe`, V019) já tinha `tipo_movimento = 'TRANSFERENCIA'` e um
campo `id_transferencia` reservado para isso desde o desenho original (§3.3.4), mas nenhuma
tela ou endpoint existia — `com.vetor.niner.estoque` era só um pacote vazio.

## Solução proposta

Nova tela de domínio dentro do módulo `estoque` (primeira funcionalidade real desse menu, que
até aqui era só "Em breve"). Pedido direto do dono do produto, com uma regra central: **a
empresa de saída é sempre a empresa ativa da sessão** (claim `eid` do JWT, ver
`docs/telas/login-empresa.md`) — o operador só escolhe a empresa de destino. Não existe seletor
de origem em lugar nenhum da tela.

## User stories

- Como usuário logado numa loja, quero transferir produtos para outra loja do mesmo tenant, sem
  precisar trocar de sessão/login.
- Como usuário, quero ver quanto tenho em estoque na loja atual antes de decidir a quantidade a
  transferir.
- Como usuário, quero ver o histórico de transferências já feitas (data, origem, destino, quem
  fez, quantos itens).

## Modelo de dados

Nova tabela `produto_transferencia` (cabeçalho), adicionada **dentro de
`V019__estoque.sql`** (banco em construção, edita a migration que já é dona do módulo estoque
— não gera `V028+`):

```sql
CREATE TABLE produto_transferencia (
  id_transferencia   integer     GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id_tenant          smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_empresa_origem  integer     NOT NULL,
  id_empresa_destino integer     NOT NULL,
  id_usuario         integer     NOT NULL,
  data_transferencia timestamptz NOT NULL DEFAULT now(),
  observacoes        text,
  CONSTRAINT produto_transferencia_empresas_diferentes_ck CHECK (id_empresa_origem <> id_empresa_destino),
  ...
);
```

Uma transferência grava, na mesma transação:
1. Uma linha em `produto_transferencia` (o cabeçalho: quem, quando, origem, destino, observações).
2. **Dois** `produto_movimento_mestre` (`tipo_movimento = 'TRANSFERENCIA'`) — um para a empresa
   de origem, outro para a de destino — ambos com o mesmo `id_transferencia` (o valor concreto
   do "gerador externo" que o comentário daquela coluna já antecipava desde 2026-07-16).
3. Para cada produto informado: um `produto_movimento_detalhe` **'D'** (débito, sai) no
   movimento da origem, e um **'C'** (crédito, entra) no movimento do destino, com a mesma
   quantidade dos dois lados — é sempre uma transferência completa, sem perda/ganho de
   quantidade no caminho.

A trigger `fn_atualiza_estoque_movimento` (V019, já existente, inalterada) atualiza
`produto_estoque` dos dois lados sozinha — nenhuma lógica nova de saldo foi escrita.
`produto_movimento_mestre.id_transferencia` continua **sem FK** para `produto_transferencia`
(mantido "proposital" como já estava documentado, pra não acoplar o ledger genérico a uma
tabela de negócio específica).

## Campos do formulário (Nova Transferência)

| Campo | Componente | Obrigatório | Regra |
|---|---|---|---|
| Empresa de Origem | texto somente-leitura | — | Sempre a empresa ativa da sessão (`GET /api/v1/eu`, campo `empresa`); nunca editável |
| Empresa de Destino | select | **Sim** | `GET /api/v1/empresas`, excluindo a empresa de origem e as inativas |
| Produtos | busca + lista | **Sim, ao menos um** | Reaproveita `PesquisaProdutoModal` do PDV (`GET /api/v1/pdv/produtos`) — mesma busca, mostra estoque por empresa, o que já dá visibilidade do saldo na origem antes de adicionar |
| Quantidade (por item) | numérico, 3 casas (`numeric(14,3)`) | **Sim, > 0** | Não pode passar do estoque disponível na origem — validado no front (aviso inline) e de novo no back (409 se passar) |
| Observações | texto livre | Não | Livre, sem validação de formato |

**Sem `cfg_tela_campo` nesta tela** — mesma decisão de `identidade.usuario`: os únicos campos
são estruturalmente obrigatórios (destino + ao menos um item), não há o que tornar configurável
por tenant.

## Tela de listagem

- **Colunas:** Data, Origem, Destino, Usuário, Itens (contagem).
- Sem ordenação por coluna nem filtros na v1 — lista simples, mais recente primeiro
  (`data_transferencia DESC`).
- **Paginação:** por número de página, tamanho fixo em 50 — mesmo padrão do resto do projeto.
- **Ação por linha:** só visualizar (ícone verde) — **sem editar nem excluir**, mesma decisão
  de produto de uma venda: transferência já efetivada é um registro permanente do estoque
  (P3, auditabilidade); `produto_movimento_mestre` já é imutável no banco desde V019/V024.

## Critérios de aceitação (viram testes)

- Dado um usuário logado na empresa A com estoque suficiente de um produto, quando transfere
  para a empresa B, então o estoque de A diminui e o de B aumenta na mesma quantidade.
- Dado um pedido de transferência maior que o estoque disponível na origem, quando enviado,
  então a API rejeita com 409.
- Dado um pedido de transferência para a própria empresa de origem, então a API rejeita com 400.
- Dado um `idEmpresaDestino` inexistente ou de outro tenant, então a API rejeita com 400.
- Dado uma transferência criada, quando listada ou buscada por id, então aparecem os produtos e
  quantidades corretos.
- Dado uma transferência de outro tenant, quando buscada por id, então não aparece (RLS).

Cobertos por `TransferenciaCrudTest` (6 testes). Suíte completa do projeto: 179/179 verdes.

## Impacto no contrato de API

```
GET  /api/v1/estoque/transferencias?pagina=&limite=   lista paginada
GET  /api/v1/estoque/transferencias/{id}               detalhe (produtos e quantidades)
POST /api/v1/estoque/transferencias                     cria transferência (empresa de origem = eid do JWT)
```

Todos sob `/api/v1/**` (JWT de tenant, RLS ativo — P8). Aberto a ADMIN e OPERADOR (operação do
dia a dia, mesma decisão de PDV/cadastros — não é sensível como `identidade.usuario`). Erros em
Problem Details (RFC 9457).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `estoque.transferencia.lista`** e **`estoque.transferencia.form`** — ver
  `web/src/components/AjudaDaTela.tsx`. `url_video`: `NULL` por ora.

## Impacto no banco

Nova tabela `produto_transferencia` dentro de `V019__estoque.sql` + RLS em
`V024__rls_dominio.sql` (adicionada ao array). Nenhuma mudança em `produto_movimento_mestre/
detalhe`/`produto_estoque` nem na trigger `fn_atualiza_estoque_movimento` — só uso do que já
existia.

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- **Reserva de estoque durante a transferência** — a baixa/alta é imediata (mesmo instante),
  sem estado "em trânsito". Se isso vier a ser necessário (ex.: transporte demorado entre
  lojas), é uma feature nova, não uma extensão trivial desta.
- **Cancelamento/estorno de transferência pela tela** — `produto_movimento_mestre` é imutável
  (P3); uma correção exigiria uma transferência de volta, feita manualmente pelo usuário, não
  um botão "desfazer".
- **Transferência parcial de uma linha já criada** (editar quantidade depois) — não existe
  edição, só criação.
- **Notificação para quem recebe** (usuário da empresa de destino não é avisado automaticamente).

## Questões abertas

Nenhuma bloqueante.

## Métrica de sucesso

Tempo de transferência de 1–3 produtos entre duas lojas em menos de 30 segundos.
