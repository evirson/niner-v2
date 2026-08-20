# Devolução de Produtos Comprados

**Rota:** `/estoque/devolucao-compra` · **Menu:** Estoque → Devolução de Produtos Comprados
**Papéis:** ADMIN e OPERADOR · **Criada em:** 2026-08-20

Devolve mercadoria ao **fornecedor**: baixa o estoque e emite a **NF-e modelo 55 de saída**
correspondente. É o espelho, na direção da entrada, da Devolução de Produtos (que é do consumidor
para a loja) — mas as duas se parecem menos do que o nome sugere, e as diferenças estão marcadas
ao longo deste documento.

---

## 1. O que esta rotina NÃO faz

Duas ausências são decisão do dono do produto (2026-08-20), não pendência:

- **Não mexe no financeiro.** Não gera crédito, não abate conta a pagar, não lança nada em caixa.
  O lojista negocia o crédito com o fornecedor por fora. Por isso a operação **não tem tabela de
  cabeçalho própria** (diferente de `venda_devolucao`, que existe porque gera vale-mercadoria): a
  devolução **é** o movimento de estoque.
- **Não devolve o que já foi vendido.** Ver §3.

---

## 2. Elegibilidade — e o limite duro que ela impõe

Só é devolvível a entrada que satisfaça **todas** estas condições:

| Condição | Por quê |
|---|---|
| `tipo_movimento = 'COMPRA'` e não cancelada | é a compra que se está desfazendo |
| tem **XML arquivado** (`entrada_xml`) | o destinatário da nota de devolução sai do emitente do XML |
| tem **tributação por item** (`entrada_nfe_item`) | a devolução espelha os impostos da entrada |

⚠️ **Entrada manual ou por planilha nunca será devolvível** — não tem nota de origem para
espelhar. E **entrada anterior a 20/08/2026 também não**, porque `entrada_nfe_item` passou a
existir na V051. A tela diz isso por extenso em vez de mostrar a entrada e falhar depois.

---

## 3. Os dois limites de quantidade

O máximo devolvível de cada item é o **menor** entre:

1. **Saldo da nota** — `comprada − já devolvida` (view `vw_entrada_saldo_devolucao`);
2. **Estoque atual** da empresa.

Exemplos que o dono do produto usou para enunciar a regra:

| Entrou | Estoque hoje | Máximo devolvível |
|---|---|---|
| 10 | 8 | **8** — o estoque limita |
| 10 | 12 | **10** — a nota limita |

### ⚠️ O segundo limite não depende do parâmetro de estoque negativo

Desde 2026-08-20 existe `cfg_permite_estoque_negativo` (Parâmetros do Sistema → Estoque), que
decide se as demais rotinas podem deixar o saldo abaixo de zero. **A devolução ao fornecedor não
consulta esse parâmetro**: a regra dela é mais estreita e vale sempre, porque aqui o estoque não é
só saldo — é o que a NF-e declara estar saindo fisicamente. Mesmo com o parâmetro ligado, não se
devolve o que não está na loja.

A comparação abaixo explica por que a venda e a devolução foram tratadas de forma diferente antes
de o parâmetro existir — e por que a devolução continua sendo o caso mais rígido:

| | Venda (PDV) | Devolução ao fornecedor |
|---|---|---|
| A mercadoria | está na mão do operador | ninguém está vendo |
| Estoque zero significa | o **cadastro** está atrasado | a peça provavelmente **já foi vendida** |
| A operação | **registra** um fato observado | **declara** um fato não conferido |
| Negativo | **parâmetro** (`cfg_permite_estoque_negativo`) — desligado por padrão | **barrado sempre**, o parâmetro não afrouxa |

O que muda tudo é a NF-e: ela afirma à SEFAZ que a mercadoria está saindo fisicamente. Se não
estiver, o documento fica mentindo e o fornecedor espera uma caixa que nunca chega.

### Duas maneiras de furar o teto, ambas fechadas

- **Linha repetida**: o pedido é **somado por variação antes** de validar. Conferir linha a linha
  deixaria duas linhas de 5 passarem contra um estoque de 8.
- **Corrida com o PDV**: o estoque é lido **dentro da transação, com a linha travada**
  (`SELECT … FOR UPDATE`, ordenado por `id_variacao` para não dar deadlock). Sem a trava, a venda
  acontece entre a grid e o "confirmar", ou duas devoluções simultâneas leem as mesmas 8 unidades.
  (No resto do sistema quem garante o saldo é a trigger da V054, que roda dentro da mesma
  transação do débito; aqui a trava é explícita porque o limite é conferido **antes** de gravar,
  para a mensagem poder listar produto e quantidade.)

---

## 4. A tela

Popup de filtros obrigatório ao entrar (mesmo padrão de Entrada de Produtos e CRM):
**fornecedor · empresa · nº nota fiscal · início e fim da entrada** — todos opcionais.
Botões: **Fechar** (`navigate(-1)`) e **Localizar**.

Depois, duas grades na mesma tela:

1. **Escolha a entrada** — data, fornecedor, empresa, nota/série, itens, valor. Entrada com
   devolução parcial já feita aparece marcada.
2. **O que devolver** — por item: comprada, já devolvida, em estoque, **máximo**, quantidade a
   devolver (editável, já preenchida com o máximo) e valor. Item cujo máximo é zero **não aparece**.
   Quando o máximo é menor que o saldo da nota, a linha recebe `*` e o rodapé explica que parte da
   mercadoria já saiu da loja.

Confirmação em popup, e um segundo popup com o desfecho — incluindo, em verde ou vermelho, **a
mensagem da nota fiscal**, que diz explicitamente se a mercadoria pode ou não seguir viagem.

---

## 5. A nota fiscal

**Emitente** é a empresa que recebeu a mercadoria; **destinatário** é o fornecedor, com os dados
lidos **do XML arquivado**, não do cadastro de fornecedor — o cadastro é editável e pode ter
divergido; o XML é o que o fornecedor declarou e está imutável no bucket.

- `tpNF = 1` (saída) e `idDest` conforme a UF do fornecedor contra a da loja. O montador
  `MontadorXmlNfeDevolucao` foi **parametrizado** para isso em vez de duplicado — os dois sentidos
  de devolução compartilham ~500 linhas de XML já homologado.
- **Toda a tributação é espelhada** de `entrada_nfe_item`; o motor tributário não roda.
- Um item devolvido pode virar **vários itens na nota**: a mesma variação pode ocupar mais de um
  `nItem` na nota do fornecedor (grade, lotes), cada um com tributação própria. A quantidade é
  alocada entre eles em ordem de `numero_item` (FIFO).
- **IBS/CBS saem zerados** enquanto a nota do fornecedor não os declarar (obrigatório só em
  04/01/2027) — declarar imposto que a entrada não cobrou não seria espelhar.

### CFOP é a única coisa que muda — e muda porque tem de mudar

O CFOP da entrada é o da **venda do fornecedor**. Copiá-lo declararia que *nós* vendemos produção
própria. De-para fechado com o dono do produto:

| Fornecedor usou | Nossa devolução |
|---|---|
| `x.101` / `x.102` / `x.103` / `x.104` | **`x.202`** — devolução de compra para comercialização |
| `x.401` / `x.403` / `x.405` (com ST) | **`x.411`** |

O primeiro dígito acompanha o do fornecedor (5 interno, 6 interestadual). CFOP fora do de-para
**falha na montagem** com o motivo por extenso (F11) — remessa, consignação e industrialização por
encomenda têm regra própria e precisam de decisão, não de palpite.

---

## 6. Ordem das operações — e por que ela se inverte

| | Ordem | Se o segundo passo falhar |
|---|---|---|
| **Efetivar** | grava e baixa estoque → emite a nota | a devolução vale, a nota fica pendente e **a carga não sai** |
| **Cancelar** | cancela a nota na SEFAZ → reverte o estoque | **nada** é revertido; 409 com o motivo |

Não é assimetria gratuita: em cada caso o passo que vem primeiro é o que deixa o sistema no estado
*menos* errado se o outro falhar. Ao cancelar, a nota autorizada já declarou que a mercadoria saiu
— devolver o estoque antes da confirmação deixaria a loja com a mercadoria em casa e um documento
válido dizendo o contrário.

Nos dois casos a orquestração mora **no controller**, que não é transacional: F2 proíbe chamada de
rede dentro de transação de banco, e a SEFAZ pode levar 10 s.

### Cancelamento

`POST /api/v1/estoque/devolucao-compra/{id}/cancelar`, motivo obrigatório. Marca o movimento e
lança um `CANCELAMENTO` com `'C'` — **nada é apagado** (P3). O saldo devolvível volta sozinho,
porque `vw_entrada_saldo_devolucao` ignora devolução cancelada.

O prazo do evento 110111 para NF-e 55 vem de `cfg_uf_autorizador` por (UF, modelo, ambiente), com
piso de 24 h. Vencido o prazo, o caminho legal deixa de ser o cancelamento: passa a ser **pedir ao
fornecedor a nota de devolução correspondente**, e é isso que a mensagem diz.

---

## 7. Contrato de API

| Método | Rota | Resposta |
|---|---|---|
| GET | `/api/v1/estoque/devolucao-compra/entradas` | página de entradas elegíveis (filtros da §4) |
| GET | `/api/v1/estoque/devolucao-compra/entradas/{id}/itens` | itens devolvíveis com os três limites |
| POST | `/api/v1/estoque/devolucao-compra` | 201 + devolução + `nota` (nula se o fiscal estiver desligado) |
| POST | `/api/v1/estoque/devolucao-compra/{id}/cancelar` | 200 + protocolo do evento (nulo se não havia nota) |

⚠️ Entrada de **outro tenant** responde **409, o mesmo que uma entrada inelegível ou inexistente**
— distinguir "existe mas não é sua" de "não existe" confirmaria a existência da linha alheia (P8).

---

## 8. Schema

- **V051** — `entrada_xml.xml_objeto_bucket/xml_hash/arquivado_em` (o XML da entrada passou a ser
  arquivado no MinIO, ADR-014) + tabela nova **`entrada_nfe_item`** (47 colunas, com RLS; inclui
  **IPI**, que `documento_fiscal_item` não tem porque a NFC-e de saída não destaca).
- **V052** — `ALTER TYPE tipo_movimento ADD VALUE 'DEVOLUCAO_COMPRA'`, **sozinha no arquivo**:
  no Postgres o valor novo não pode ser usado na mesma transação em que é criado.
- **V053** — `produto_movimento_mestre.id_movimento_origem` (+ FK composta com `id_tenant` e
  índice parcial) e a view `vw_entrada_saldo_devolucao`.

Três peças **já existiam** e encolheram o schema: `tipo_operacao_fiscal.DEVOLUCAO_FORNECEDOR`
(semeada na V035), `documento_fiscal.id_movimento` e
`documento_fiscal_referencia.id_documento_referenciado` — esta última já documentada como
"preenchida quando a nota é do próprio Niner", ou seja, já previa referenciar nota de **terceiro**
só pela chave, que é exatamente o caso da nota do fornecedor.

---

## 9. Testes

`DevolucaoCompraCrudTest` (7) — elegibilidade (XML sim, manual não), os dois tetos com os números
que o dono do produto usou (10/8 e 10/12), linha repetida somando antes de validar, devolução
parcial, cancelamento devolvendo estoque e saldo, isolamento entre tenants.
`CfopDevolucaoCompraTest` (4) — o de-para inteiro e a falha com motivo para CFOP fora dele.

A emissão fiscal não entra no teste de CRUD de propósito: sem configuração fiscal na empresa o
assembler devolve vazio (F12) e a devolução acontece sem nota, que é o caminho testável sem
certificado.
