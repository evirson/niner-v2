# Spec: Transferência de Produtos Entre Empresas       Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-07-28, revisada 2026-07-29 · Módulo(s): `estoque` (transferencia) · Fase: 1 — Núcleo do ERP

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
| Quantidade (por item) | numérico, até 3 casas (`numeric(14,3)`) **se** `cfg_permite_qtd_decimal` estiver ligado, senão inteiro | **Sim, > 0** | Sem limite contra o estoque da origem (revisado 2026-07-29, ver "Estoque negativo permitido" abaixo) — só não pode ser zero/negativa. |

**Sem `cfg_tela_campo` nesta tela** — mesma decisão de `identidade.usuario`: os únicos campos
são estruturalmente obrigatórios (destino + ao menos um item), não há o que tornar configurável
por tenant. **Observações removido (2026-07-29)** — campo existia desde a v1 mas nunca foi
usado; tirado do formulário e do payload (`criarTransferencia` sempre envia `observacoes: null`).

**Quantidade decimal configurável (2026-07-29):** `cfg_geral.cfg_permite_qtd_decimal`
(Parâmetros do Sistema) decide se o campo Quantidade aceita vírgula/3 casas ou só dígitos
inteiros — mesma regra usada no PDV e no Histórico do Cliente (`docs/telas/configuracao-geral.md`).
Validado também no servidor: com o parâmetro desligado, `POST /transferencias` rejeita (400)
qualquer item com quantidade fracionária.

**Sintaxe "quantidade\*código" no campo de código de barras (2026-07-29):** digitar
`5*9001000000138` já adiciona o item com quantidade 5 em vez de sempre 1 — mesma função
`interpretarCodigoBarras()` reaproveitada do PDV (`web/src/lib/pdv.ts`), útil quando o mesmo
produto é transferido em lote. Sem `*`, o valor inteiro é o código e a quantidade é 1 (soma 1 se
o item já estiver na lista). Dica exibida discretamente sob o campo de código de barras.

**Fluxo de "Nova Transferência" (revisado 2026-07-29):** clicar em "Nova Transferência" abre a
tela de produtos com um popup por cima (`EscolherDestinoModal.tsx`) mostrando a empresa de
origem (somente leitura) e pedindo a de destino — só depois de confirmado o popup o campo de
código de barras libera (mesmo mecanismo de foco automático de antes, só que disparado pela
escolha no popup em vez de um select embutido na tela). O título da tela passa a mostrar
"Nova Transferência · {origem} → {destino}" assim que os dois são conhecidos. Cancelar o popup
volta para a listagem.

## Tela de listagem

- **Colunas:** Nº, Data, Origem, Destino, Usuário, Itens (contagem) — todas ordenáveis (clique
  no cabeçalho, ▲/▼/⇅, revisado 2026-07-29; antes só havia Data/Origem/Destino/Usuário/Itens
  sem ordenação nenhuma).
- **Filtros (novo, 2026-07-29):** Data Inicial, Data Final, Nº da Transferência, Empresa de
  Saída, Empresa de Entrada — nessa ordem na barra. `GET /api/v1/estoque/transferencias` ganhou
  os query params `idTransferencia`/`idEmpresaOrigem`/`idEmpresaDestino`/`dataInicial`/
  `dataFinal` (datas comparadas por `::date`, sem hora).
- **Paginação:** por número de página, tamanho fixo em 50 — mesmo padrão do resto do projeto.
- **Ação por linha:** visualizar (ícone verde) **e excluir (ícone vermelho, novo em
  2026-07-29)** — ver "Exclusão de transferência" abaixo. Não existe editar.

## Critérios de aceitação (viram testes)

- Dado um usuário logado na empresa A, quando transfere um produto para a empresa B, então o
  estoque de A diminui e o de B aumenta na mesma quantidade — **mesmo que A não tenha saldo
  suficiente** (revisado 2026-07-29, ver "Estoque negativo permitido").
- Dado um pedido de transferência para a própria empresa de origem, então a API rejeita com 400.
- Dado um `idEmpresaDestino` inexistente ou de outro tenant, então a API rejeita com 400.
- Dado uma transferência criada, quando listada ou buscada por id, então aparecem os produtos e
  quantidades corretos.
- Dado uma transferência de outro tenant, quando buscada por id, então não aparece (RLS).
- Dado uma transferência existente, quando excluída, então a quantidade de cada produto volta
  para a empresa de origem e sai da de destino — mesmo que o destino não tenha mais saldo
  suficiente para "devolver" (fica negativo).
- Dado `cfg_permite_qtd_decimal` desligado, quando um item é enviado com quantidade fracionária,
  então 400 e nada é gravado (2026-07-29).

Cobertos por `TransferenciaCrudTest`. Suíte completa do projeto: 500/500 verdes (2026-08-14).

## Impacto no contrato de API

```
GET    /api/v1/estoque/transferencias?pagina=&limite=&ordenarPor=&direcao=
                                                         &idTransferencia=&idEmpresaOrigem=
                                                         &idEmpresaDestino=&dataInicial=&dataFinal=
                                                         lista paginada, filtrável e ordenável
GET    /api/v1/estoque/transferencias/{id}               detalhe (produtos e quantidades)
POST   /api/v1/estoque/transferencias                     cria transferência (empresa de origem = eid do JWT)
DELETE /api/v1/estoque/transferencias/{id}                exclui — reverte o estoque (2026-07-29)
```

Todos sob `/api/v1/**` (JWT de tenant, RLS ativo — P8). Aberto a ADMIN e OPERADOR (operação do
dia a dia, mesma decisão de PDV/cadastros — não é sensível como `identidade.usuario`). Erros em
Problem Details (RFC 9457).

## Estoque negativo permitido (revisado 2026-07-29)

Pedido direto do dono do produto, **vale para qualquer movimentação de produto do sistema**
(entrada ou saída, não só transferência — PDV incluído): nenhuma rotina de movimentação de
estoque deve checar/bloquear por saldo insuficiente. `TransferenciaService.resolverItens()` só
valida que a variação existe e está ativa — não compara mais com `produto_estoque.disponivel`.
O front (`TransferenciaForm.tsx`) não bloqueia mais o botão "Confirmar Transferência" nem mostra
aviso de "maior que o estoque disponível". Mesma mudança em `PdvVendaService` (PDV) — ver
`docs/telas/pdv.md`.

## Exclusão de transferência (novo, 2026-07-29)

Reverte o non-goal original desta spec ("sem cancelamento/estorno pela tela") — pedido direto
do dono do produto. `produto_movimento_mestre` continua **fisicamente imutável** (`REVOKE
UPDATE, DELETE ON produto_movimento_mestre FROM niner_app`, V024, P3) — isso não muda e não dá
pra contornar pela API. A exclusão funciona apagando as linhas de `produto_movimento_detalhe`
da transferência (origem 'D' e destino 'C'); a trigger já existente
`fn_atualiza_estoque_movimento` (V019) devolve a quantidade à origem e retira do destino
sozinha, **sem checagem de saldo** — a devolução acontece mesmo que o destino já tenha saído do
saldo transferido (fica negativo, consistente com "Estoque negativo permitido" acima). Os dois
cabeçalhos de `produto_movimento_mestre` ficam órfãos (sem linha de detalhe) — mesmo mecanismo
já usado pra qualquer correção de ledger neste projeto. O cabeçalho `produto_transferencia` é
apagado de verdade (sem `REVOKE` nele).

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
- **Transferência parcial de uma linha já criada** (editar quantidade depois) — não existe
  edição, só criação ou exclusão total (2026-07-29).
- **Notificação para quem recebe** (usuário da empresa de destino não é avisado automaticamente).

~~Cancelamento/estorno de transferência pela tela~~ — **implementado em 2026-07-29** (ver
"Exclusão de transferência" acima), revertendo o non-goal original. `produto_movimento_mestre`
continua imutável por grant do banco; a exclusão funciona apagando o detalhe do ledger (linha
que sempre foi corrigível) e deixando o cabeçalho órfão.

## Questões abertas

Nenhuma bloqueante.

## Métrica de sucesso

Tempo de transferência de 1–3 produtos entre duas lojas em menos de 30 segundos.
