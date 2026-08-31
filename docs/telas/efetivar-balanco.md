# Spec: Efetivar Balanço de Estoque                Status: Aprovada (retroativa)
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-04 · Spec escrita: 2026-08-31 ·
Módulo(s): `estoque` · Fase: 2 · Rota: `/estoque/efetivar-balanco`

> ⚠️ **Esta spec é RETROATIVA.** A tela existe e funciona desde 2026-08-04; o arquivo nasceu em
> 2026-08-31 para fechar a pendência 24 de `docs/PENDENCIAS.md` (*"Efetivar Balanço e Tipo de
> Carteira não têm spec nenhuma"*), que num repositório spec-driven é lacuna, não detalhe.
> Ela foi **derivada do código** (`web/src/pages/estoque/EfetivarBalanco.tsx`,
> `api/.../estoque/balanco/`), não da memória — e cada afirmação sobre comportamento abaixo foi
> conferida no arquivo citado.
>
> 📄 **A ROTINA inteira está em `docs/telas/contagem-estoque.md`** (as quatro telas, o modelo de
> dados de `produto_balanco` e as decisões de escopo do dono do produto). Este arquivo é a spec
> **desta tela** e não repete aquilo: aqui está o que é específico da efetivação — o guarda de
> confirmação, o que a efetivação grava, e as três armadilhas que ela já custou.

## Problema

Contar o estoque físico não muda nada sozinho. Alguém precisa dizer *"o que eu contei é a
verdade"* e transformar a divergência em movimento de estoque — de forma **auditável** (P3) e
**reversível**, porque uma contagem errada efetivada sem volta arruinaria o saldo de toda a loja.

## Solução

Uma tela separada da "Diferenças de Estoque" (pedido explícito do dono do produto), que mostra a
**mesma grade de diferenças** para revisar e um único botão de ação.

- **Escopo:** sempre a **empresa ativa da sessão** (claim `eid`), sem seletor — nem para ADMIN.
  Um balanço físico só faz sentido para quem está fisicamente na loja.
- **Grade:** Descrição · Cor · Tamanho · Qtd Estoque · Qtd Contada · Diferença, com **rodapé de
  totais** e a diferença colorida (`--sucesso` para sobra, `--danger` para falta).
- **Dois estados vazios com mensagens diferentes**, e a distinção importa: *"não há nenhuma
  contagem em andamento nesta empresa"* (`existeContagemAtiva = false`) é diferente de *"nenhuma
  diferença encontrada"* — o primeiro manda o operador contar, o segundo diz que já está tudo
  certo. Agrupar os dois num só texto mandaria metade dos usuários para o lado errado.

### O guarda de confirmação

O botão **Efetivar Balanço** abre um popup que exige **digitar a frase `efetiva contagem`** por
extenso — mesmo padrão de "Zerar Contagem de Estoque". O popup declara, antes de confirmar:

- quantos produtos serão ajustados;
- o **total contado × o total em estoque**;
- que a contagem ativa será zerada em seguida;
- e que **dá para desfazer** depois, em "Zerar Contagem de Estoque" — a frase que evita o pânico
  de quem clicou por engano.

## O que a efetivação grava

Um `produto_movimento_mestre` com `tipo_movimento = 'AJUSTE'` e uma linha de
`produto_movimento_detalhe` por variação com diferença (crédito quando o contado é maior, débito
quando é menor). ⭐ **A contagem não é apagada:** as linhas de `produto_balanco` recebem o
`id_movimento` da efetivação, e é isso que as "zera" sem perder nada — é o que viabiliza o
desfazer. O saldo em `produto_estoque` é atualizado pela **trigger** `fn_atualiza_estoque_movimento`
(V019), nunca por `UPDATE` do serviço.

## ⚠️ As três armadilhas desta tela (todas já custaram caro)

1. ⛔ **`tipo_movimento = 'AJUSTE'` NÃO identifica o balanço.** A Importação de Dados grava
   exatamente o mesmo `AJUSTE`. Enquanto o "desfazer" filtrava só pelo tipo, um clique em
   *Desfazer* **apagava o estoque de 10.000 SKUs recém-migrados**, com resposta **200** — estoque
   negativo é permitido por padrão, então nada barrava. Hoje o filtro é
   `produto_balanco.id_movimento`, coluna que **só o balanço preenche** (V019, com FK). Achado na
   auditoria de 2026-08-30.
2. ⚠️ **A efetivação pode ser RECUSADA desde 2026-08-20.** O ajuste debita estoque quando o
   contado é menor, e passa pela mesma trigger das outras sete rotinas: com
   `cfg_permite_estoque_negativo` **desligado**, nenhum débito pode deixar o saldo abaixo de zero.
   Com o parâmetro no padrão (ligado), nada muda. Ver `docs/telas/configuracao-geral.md`.
3. ⚠️ **A permissão é consultada na TELA, não só no servidor** (`usePermissaoDaTela`). Sem isso o
   conferente contava o inventário inteiro, abria a tela, lia as diferenças, digitava a frase por
   extenso — **e só então levava 403**. É o defeito do "403 tardio" que este projeto já pagou em
   Recebimento de Crediário; aqui o botão nasce desabilitado.

## Contrato de API

```
GET  /api/v1/estoque/balanco/diferencas          → { existeContagemAtiva, linhas[] }
POST /api/v1/estoque/balanco/efetivar            → { idMovimento, totalProdutosAjustados, dataEfetivacao }
GET  /api/v1/estoque/balanco/ultima-efetivacao   → { existe, idMovimento, dataEfetivacao, totalProdutos }
POST /api/v1/estoque/balanco/desfazer            (é a tela "Zerar Contagem de Estoque")
```

**RBAC** — `BalancoEstoqueController` declara `@Tela("estoque.contagem")` na classe, e os métodos
desta tela sobrescrevem:

| Endpoint | `@Tela` | Ação exigida |
|---|---|---|
| `GET /diferencas` | `{"estoque.diferencas", "estoque.efetivar-balanco", "estoque.contagem"}` | acessar |
| `POST /efetivar` | `estoque.efetivar-balanco` | **incluir** |

⚠️ **A leitura aceita QUALQUER UMA das três chaves, e isso é deliberado** (`@Tela` é **ou**, nunca
**e**): as três telas mostram a mesma grade, e exigir a chave da tela dona daria **403 na abertura
citando uma tela que o administrador decidiu não liberar**. Ver
`feedback_tela_aceita_varias_chaves`.

## Critérios de aceitação (viram testes)

Cobertos em `api/src/test/java/com/vetor/niner/BalancoEstoqueCrudTest.java`:

- **Dado** contagem com diferença, **quando** efetivo, **então** nasce um `AJUSTE`, o estoque passa
  a bater com o contado e o balanço ativo fica vazio (`efetivarGravaAjusteAtualizaEstoqueEZeraBalancoAtivo`).
- **Dado** contagem que bate com o estoque, **quando** efetivo, **então** 400 com mensagem própria
  (`efetivarSemDiferencasRespondeErroDeValidacao`).
- **Dado** uma efetivação, **quando** desfaço, **então** o estoque volta **e a contagem ativa é
  restaurada** (`desfazerRevertEstoqueERestauraBalancoAtivo`) — não basta reverter o saldo.
- **Dado** duas efetivações, **quando** desfaço duas vezes, **então** a segunda volta para a
  anterior, e a terceira recusa (`desfazerVoltaProEfetivacaoAnteriorQuandoAMaisRecenteJaFoiDesfeita`,
  `desfazerDuasVezesSeguidasNaSegundaNaoHaMaisNadaParaDesfazer`).

⛔ **O que NÃO tem teste, declarado:** que o *Desfazer* não alcança um `AJUSTE` da Importação de
Dados (a correção de 2026-08-30 é lida no SQL, não exercitada com massa de importação), e a recusa
por estoque negativo com o parâmetro desligado.

## Non-goals

- **Escolher outra empresa** — decisão do dono do produto, perguntada e respondida: não.
- **Efetivar parcialmente** (só alguns produtos da lista): a contagem é da loja inteira; efetivar
  metade deixaria o restante em estado ambíguo.
- **Histórico de balanços** com relatório próprio — hoje o rastro vive no ledger (`AJUSTE`) e no
  Kardex.

## Impacto no banco

Nenhum: usa `produto_balanco` (V019) e o ledger. A coluna `id_movimento` que sustenta o desfazer
já existe desde 2026-08-04.
