# Estudo: Integração com Marketplaces — Mercado Livre (primeiro canal)

> **Status: ESTUDO, aguardando decisões do dono do produto.** Nada foi implementado.
> Data: 2026-08-25 · Requisitos cobertos: R3, R5, R6, R7 · Princípios: P1, P2, P3, P8
>
> ⚠️ **O que aqui é verificado × o que é suposto.** Os fatos da API do ML foram levantados na
> documentação pública em 25/08/2026. **Nenhuma chamada foi feita contra a API do Mercado Livre
> ainda** — tudo aqui é leitura, e leitura de documentação já nos traiu neste projeto (o XSD
> passou e a SEFAZ recusou, ver `MODULOFISCAL.md`). Trate os números como "o que a doc diz", a
> confirmar na primeira chamada real.

---

## 1. O que já existe e não precisa ser refeito

A fundação foi construída em julho e nunca foi usada. **O schema está pronto desde a V020–V022:**

| Tabela | Para quê | Estado |
|---|---|---|
| `canal` | conta conectada por marketplace, `credenciais` JSONB cifrado, `status`, `config` | ✅ pronta |
| `anuncio` | de-para anúncio ↔ variação/SKU (R6), `preco`, `status_sync`, `ultimo_erro` | ✅ pronta |
| `pedido` / `pedido_item` | fila única de expedição (R5), **UNIQUE (canal, id_externo)** = idempotência (P2) | ✅ pronta |
| `outbox_evento` | fila de saída com `tentativas`/`proximo_retry`/`DEAD_LETTER` (P2) | ✅ pronta |
| `webhook_recebido` | **UNIQUE (canal, webhook_id)** = idempotência de entrada | ✅ pronta |
| `produto_estoque.reservado` / `.disponivel` | reserva de estoque (ADR-004) | ✅ colunas existem |

E os blocos de código reaproveitáveis também existem, todos já exercitados pelo módulo fiscal:

- **`comum.seguranca.SegredoCifrador`** — AES-256-GCM com chave fora do banco. É exatamente o que
  o ADR-005 pede para `canal.credenciais`, e já protege certificado digital e CSRT.
- **`HttpClient` do JDK** — o `SefazTransporte` provou o padrão, sem lib de terceiro.
- **Worker `@Scheduled` + `TenantContext`** — `ArquivamentoXmlJob` e `CobrancaWebhookJob` já
  resolvem "job sem request não tem tenant", e o `CobrancaWebhookProcessador` já é o par
  *agendador × trabalho transacional* que o P2 exige.
- **Webhook idempotente** — `/api/publico/webhooks/mercadopago` é o molde pronto.

**O que não existe:** nenhuma linha de domínio em `canais/`, `pedidos/`, `precos/`, `integracao/`
— os quatro pacotes têm só `package-info.java`. É código novo do zero, sobre schema pronto.

---

## 2. O que o Mercado Livre exige — os fatos que moldam o desenho

### 2.1 OAuth: **um** aplicativo da Vetor, N lojistas autorizando

O modelo é o mesmo do Mercado Pago que já usamos: a Vetor registra **uma** aplicação em
*My Applications*, recebe `client_id`/`client_secret`, e **cada lojista autoriza essa aplicação**
na própria conta ML. Não é o lojista que cria aplicação.

- `redirect_uri` **obrigatoriamente HTTPS**, e tem de bater **exatamente** com a registrada —
  sem parte variável na URL.
- Tokens: **`access_token` expira em 6 horas** (`expires_in: 21600`). Para renovar sem o lojista
  reautorizar, é preciso pedir o escopo **`offline_access`**, que devolve um `refresh_token`.
- Escopos disponíveis: `read`, `write`, `offline_access`.
- PKCE é **opcional**, ligado por aplicação. Como o segredo fica no servidor, não é necessário.

⚠️ **Consequência de desenho:** o `redirect_uri` não pode carregar o `id_tenant` (a URL não aceita
parte variável). O vínculo *"esta autorização é da loja X"* tem de viajar no parâmetro **`state`**,
que é o mesmo lugar do token anti-CSRF. Errar isso conecta a conta ML de um lojista no tenant de
outro — falha de isolamento (P8) logo na porta de entrada.

### 2.2 ⚠️ **Não existe sandbox** — os testes rodam em produção

Isto muda o planejamento. O ML **não tem ambiente de homologação**: criam-se *test users* via
`POST /users/test_user`, que vivem dentro da produção.

- **Máximo 10 usuários de teste** por conta, e **eles expiram**.
- Precisa do token da conta **real** para criar (token de test user não cria outro).
- Recomendado ao menos um vendedor e um comprador, para transacionar entre eles.
- Site do Brasil: **`MLB`**.

⚠️ **Compare com o fiscal:** lá tivemos a homologação da SEFAZ para errar à vontade — e erramos
muito, de graça. Aqui **não teremos**. Os testes com **WireMock** (já previstos na spec, Fase 2)
deixam de ser boa prática e viram a **única** rede de proteção barata; o usuário de teste é
recurso escasso, não bancada livre.

### 2.3 Notificações exigem URL pública HTTPS — e isso já está resolvido

Registra-se por tópico em `POST /applications/{app_id}/webhooks` (ou pelo painel). Tópicos
relevantes: **`orders_v2`** (venda confirmada e mudanças), **`shipments`**, **`items`**,
e ainda `messages`, `questions`, `claims`.

✅ **A infra já existe:** `https://api.nainer.com.br` está no ar com HTTPS e nginx, e já recebe o
webhook do Mercado Pago. O caminho novo seria `/api/publico/webhooks/mercadolivre`, com o mesmo
desenho: **o webhook não decide nada** — grava em `webhook_recebido` e devolve 200 na hora; quem
aplica efeito é o worker, **consultando a API do ML**. Isso protege contra payload forjado e
contra reprocessamento (P2).

⚠️ **Em desenvolvimento não há URL pública** — é o mesmo buraco que o `NINER_MP_NOTIFICATION_URL`
tem hoje (está vazio). Ou túnel, ou o **polling de segurança** que a spec prevê a cada 15 min
vira o mecanismo primário em dev.

### 2.4 Estoque e preço: `PUT /items/{id}` — e uma armadilha nas variações

Atualizar saldo é `PUT https://api.mercadolibre.com/items/$ITEM_ID` com `available_quantity`.

⛔ **A armadilha:** ao atualizar variações, é preciso **enviar o `id` de todas as outras variações
junto — as omitidas são APAGADAS**. Um anúncio com 12 tamanhos onde mandamos só o tamanho que
mudou perde os outros 11. Não dá erro: dá perda de dado no anúncio do lojista, e desfazer é
republicar à mão.

Isso obriga o adapter a **ler o anúncio antes de escrever**, sempre — o oposto do `PUT` ingênuo.
É exatamente o tipo de regra que só aparece lendo a doc com atenção e que custaria caro descobrir
em produção (não há sandbox).

**Limite de requisição: 1.500 por minuto por vendedor**, respondendo **`429` com corpo vazio**.
Folgado para uma loja pequena, mas o worker tem de honrar o 429 com backoff — e o outbox já tem
`tentativas`/`proximo_retry` para isso.

### 2.5 Pedidos: `GET /orders/{id}`, e o envio é outro recurso

O pedido vem de `GET https://api.mercadolibre.com/orders/$ORDER_ID`. ⚠️ **O formato mudou**: o
JSON novo pede o cabeçalho `x-format-new: true` (alguns sites, `x-version: 2`) e **não traz mais
os dados de envio** — estes saem de `/shipments/{shipment_id}`. Um pedido completo custa **duas**
chamadas, e o adapter junta as duas antes de entregar ao domínio.

### 2.6 ⭐ A nota fiscal **é parte do fluxo de envio**, não um extra

Este é o achado que mais muda o escopo. No Brasil, nas modalidades de Mercado Envios que não são
Full, **o ERP emite a NF-e, transmite à SEFAZ e devolve o XML ao ML — e é isso que libera a
impressão da etiqueta e o despacho**. Sem nota, o pedido não anda.

- Arquivo XML, **máximo 1 MB**, **um `fiscal_document` por pacote**; o ML gera o DANFE em PDF a
  partir do XML que enviamos.
- Tipos de documento: `SALE`, `SALE_RETURN`, `DEVOLUTION`.
- No **Full** (fulfillment do ML) a nota é emitida pelo próprio ML — fluxo invertido, e a
  integração passa a ser de *leitura* da nota que eles emitiram.

**Consequência:** "integrar com o Mercado Livre" no Brasil **não é** só estoque, preço e pedido.
Ou o lojista continua emitindo a nota à mão no painel do ML (possível, e é o que muita loja
pequena faz hoje), ou a integração puxa o módulo fiscal inteiro junto — herdando o estado dele.

---

## 3. ⚠️ Três colisões com o que o ERP já é

Estas não são dificuldades da API do ML. São consequências de plugar um canal novo num ERP que já
tem PDV, caixa, DRE, comissão e fiscal — e são a parte que exige **decisão sua**, não técnica.

### 3.1 Venda de marketplace exige **NF-e modelo 55** — que hoje não autoriza

Venda por marketplace é venda **não presencial**: sai em **NF-e modelo 55**, não em NFC-e 65.
E o modelo 55 é exatamente o que está travado no **`cStat 974`**, com chamado aberto na SEFAZ/PR
desde hoje. A NFC-e, que autoriza normalmente, **não serve** aqui.

Isso não impede começar a integração — impede **fechar o ciclo até o despacho**. É o argumento
mais forte a favor de fatiar o escopo (§6).

### 3.2 O controle de estoque é **opt-in e nasce desligado**

`cfg_permite_estoque_negativo` **nasce ligado** (V055), por decisão sua, com a justificativa
registrada: *"na maioria das vezes o usuário não vai querer controlar o estoque"*.

⚠️ **Isso colide de frente com a promessa central do produto.** "Zero overselling" (P1: o ERP é
dono do estoque, o marketplace é réplica) só existe se o saldo do ERP for verdade. Numa loja que
não alimenta estoque, sincronizar `available_quantity` **publica um número errado no anúncio** —
e o efeito é *pior* que não sincronizar, porque o ML pune reputação por cancelamento.

Precisa de resposta de produto. Saídas possíveis:
- **Sincronizar só para quem tem o controle ligado** (`cfg_permite_estoque_negativo = false`), e
  dizer isso na tela de conexão do canal;
- **Saldo declarado por anúncio** — o lojista diz "tenho 5 no ML" e o ERP não espelha; vira gestão
  de anúncio, não de estoque;
- **Exigir o controle ligado** para conectar canal, tratando marketplace como recurso avançado.

### 3.3 Pedido de marketplace vira `venda`? — **a decisão de maior impacto**

A spec deixou isto em vermelho desde julho (§3.3): *"Unificar o modelo de pedido: o legado só tem
`venda` (física). Decidir se `venda` e `pedido` convergem…"*. Naquela época era abstrato.
**Hoje não é**, porque tudo pendura em `venda`:

| Se o pedido de ML **não** virar `venda` | Se **virar** `venda` |
|---|---|
| Não aparece na **DRE**, na **Lucratividade**, no **Relatório de Vendas** | Aparece em tudo, sem código novo |
| Não gera **comissão** de vendedor | Gera — mas marketplace não tem vendedor; precisa de regra |
| Não entra no **caixa** | Entra — mas o dinheiro do ML **não passa pela gaveta**, cai na conta ML |
| Não consome **cota do plano** (ADR-015) | Consome — o lojista paga por venda de marketplace |
| Precisa de telas e relatórios próprios | Reusa PDV / Pesquisa de Vendas / papeleta |

⚠️ **Repare no caixa e na cota** — os dois são decisão comercial sua, não técnica:

- **Caixa:** o `CaixaService` assume dinheiro que entra na gaveta. Venda de ML é liquidada pelo
  Mercado Pago dias depois, com taxa descontada. Jogá-la no caixa do dia quebraria o Fechamento
  de Caixa — a conferência da gaveta nunca bateria.
- **Cota do plano:** hoje só o PDV chama `LimiteVendasService`. Se pedido de ML contar, um lojista
  que vende 300/mês no ML estoura o plano — o que pode ser **exatamente** o desejado (é receita) ou
  um freio na adoção justamente do recurso que mais diferencia o produto.

---

## 4. Perguntas — nenhuma delas eu consigo responder pelo código

1. **Pedido de Mercado Livre vira `venda` no ERP?** (§3.3) — a que mais muda o desenho. Se sim,
   preciso saber o que fazer com **caixa**, **comissão** e **cota do plano**.
2. **A loja que vai testar já vende no Mercado Livre hoje?** Se sim, ela tem anúncios publicados
   (o caminho é R6, *vincular* anúncio existente) ou vamos publicar do zero? Publicar é bem mais
   caro: categoria, ficha técnica, imagens e atributos obrigatórios variam por categoria.
3. **Qual modalidade de Mercado Envios ela usa?** Full muda tudo (o ML emite a nota e guarda o
   estoque); Flex/Coleta/Agências exigem a nota do ERP para liberar a etiqueta.
4. **O preço no ML é o mesmo do ERP?** O ML cobra comissão (faixa de ~11% a 19%) mais frete.
   Empurrar o preço de balcão para o anúncio destrói margem. Se for preço diferente, isso é o
   módulo `precos/` (tabela de preço por canal), que **não existe** — e vira escopo.
5. **Estoque: sincronizar de verdade, ou saldo declarado por anúncio?** (§3.2)
6. **Uma conta ML por empresa?** O tenant pode ter várias empresas (CNPJs); a conta ML é de um
   CNPJ. Confirmo que `canal` passa a apontar para uma `empresa`?
7. **A conta do piloto é da loja real ou de teste?** Decide se o primeiro erro mexe em estoque e
   dinheiro de verdade.

---

## 5. O que depende de você

| # | O que | Por quê | Bloqueia |
|---|---|---|---|
| 1 | **Criar a aplicação no *My Applications*** (conta ML da Vetor/MITRYUSCASH) e me passar o `client_id`. ⛔ O `client_secret` **não por chat** — vai por variável de ambiente, como o do Mercado Pago | É a identidade da integração; uma só para todos os lojistas | Tudo |
| 2 | **Declarar o `redirect_uri`** na aplicação. Sugestão: `https://api.nainer.com.br/api/publico/canais/mercadolivre/retorno` | Tem de bater exatamente, e ser HTTPS | OAuth |
| 3 | **Marcar os tópicos de notificação** e o callback `https://api.nainer.com.br/api/publico/webhooks/mercadolivre` | Sem isso, só resta polling | Pedido em tempo real |
| 4 | **Responder as 7 perguntas da §4** | Definem o escopo | O desenho inteiro |
| 5 | **Dizer qual conta ML será usada no piloto** | Decide se o primeiro erro é caro | Homologação |
| 6 | ⏭️ **Destravar o `cStat 974`** (já em curso, chamado aberto) | Marketplace vende em NF-e 55 | Só a Opção C |
| 7 | *(não urgente)* Avaliar o **Developer Partner Program** | Certificação **não é obrigatória** para operar — é programa de níveis (Certified/Silver/Gold/Platinum) por GMV e *security assessment* ≥ 65%. Fica para quando houver volume | Nada agora |

---

## 6. Opções de escopo — três recortes, do menor ao maior

### Opção A — "Espelho de estoque e preço" ⭐ recomendada para começar

**Entrega:** conectar a conta ML por OAuth · listar os anúncios existentes · **vincular anúncio ↔
variação do ERP** (R6) · empurrar **estoque e preço** para o ML via outbox (R3 parcial) · painel de
saúde com fila de erros e botão reprocessar (R7).

**Não entrega:** importar pedido, reserva, nota fiscal, etiqueta.

- ✅ Ataca direto a dor nº 1 do PRD (overselling) e valida a arquitetura de adapter.
- ✅ **Não depende do fiscal** — imune ao `cStat 974`.
- ✅ Superfície de escrita pequena no ML (só `PUT /items`), fácil de reverter.
- ⚠️ Depende da decisão de estoque (§3.2) — sem ela, sincroniza número que pode não ser verdade.
- ⚠️ O lojista continua conferindo pedido no painel do ML.

### Opção B — A + **importar pedidos** (o ciclo de venda)

**Acrescenta:** webhook `orders_v2` + polling de segurança · importação idempotente por
`(canal, id_externo)` · **reserva de estoque no recebido** (ADR-004) · fila única de expedição
(R5), com os estados já prontos no enum `status_pedido` · baixa de estoque no envio.

- ✅ É onde a integração deixa de ser "sincronizador" e vira ERP multicanal de verdade.
- ⛔ **Exige a resposta da pergunta 1** (pedido vira `venda`?) — é a bifurcação que decide se isto
  são duas semanas ou dois meses.
- ⚠️ Ainda não emite nota; o lojista emite no painel do ML para liberar a etiqueta.

### Opção C — B + **nota fiscal e despacho** (ponta a ponta)

**Acrescenta:** emissão de **NF-e 55** a partir do pedido · envio do XML ao ML · liberação da
etiqueta · `confirmarEnvio` de volta ao canal · nota de devolução (`DEVOLUTION`).

- ✅ É o produto que o PRD descreve: o lojista não precisa abrir o painel do ML.
- ⛔ **Bloqueada pelo `cStat 974`** enquanto a SEFAZ não responder.
- ⛔ Maior superfície de risco: erro aqui gera documento fiscal errado, não linha errada em tela.

### Minha recomendação

**A → B → C, nesta ordem, e A já com o adapter desenhado para as três.** Motivos:

1. **A entrega valor sozinha** e não depende de nada que esteja travado hoje.
2. **A paga o custo caro uma vez** — OAuth, tokens cifrados, webhook, outbox, painel de saúde. B e
   C reusam tudo isso; a parte difícil não é o `PUT`, é a plataforma embaixo dele.
3. **A é o exame da arquitetura de adapter** (a Shopee é a prova final, spec §Fase 3). Se a
   interface `CanalDeVenda` estiver errada, é muito mais barato descobrir em A.
4. **C depende de terceiro** (SEFAZ) e não faz sentido ser o gargalo do canal inteiro.

⚠️ **Uma ressalva honesta sobre prazo:** a spec estimou "3–4 semanas" para R3+R5+R6+R7 completos
(§Fase 2). Aquela estimativa é de julho, anterior a tudo que o ERP ganhou desde então, e **não
considerava** as três colisões da §3 nem a ausência de sandbox. Prefiro não repetir um número que
não medi — depois que você responder a pergunta 1, eu estimo com base no recorte real.

---

## 7. Fontes

Levantado em 2026-08-25 na documentação pública do Mercado Livre:

- [Autenticação e Autorização](https://developers.mercadolivre.com.br/pt_br/autenticacao-e-autorizacao)
- [Criar uma aplicação](https://developers.mercadolivre.com.br/en_us/register-your-application)
- [Notificações / webhooks](https://developers.mercadolivre.com.br/en_us/products-receive-notifications)
- [Sincronizar anúncios (itens)](https://developers.mercadolivre.com.br/en_us/services-sync-listings)
- [Variações](https://developers.mercadolibre.com.ar/en_us/variations)
- [Gerenciamento de vendas (orders)](https://developers.mercadolivre.com.br/pt_br/gerenciamento-de-vendas)
- [Emitindo nota fiscal pelo Mercado Livre](https://developers.mercadolivre.com.br/pt_br/api-fiscal-faturamento-de-venda)
- [Envio de NF para Flex, Turbo, ME1 e Drop Off](https://developers.mercadolivre.com.br/pt_br/carregar-nf)
- [Realização de testes / usuários de teste](https://developers.mercadolivre.com.br/pt_br/realizacao-de-testes)
- [Developer Partner Program](https://developers.mercadolivre.com.br/pt_br/developer-partner-program)

⚠️ O portal do ML responde **403 a leitura automatizada**, então estes fatos vieram de busca e de
trechos indexados, não de leitura direta das páginas. Confirmar cada número na primeira chamada
real é parte do trabalho, não zelo excessivo.

---

## 8. ✅ Decisões fechadas com o dono do produto (2026-08-25)

As sete perguntas da §4 foram respondidas. **Escopo aprovado: Opção A + Opção B**, com o adapter
desenhado desde já para receber a Opção C quando a SEFAZ liberar a NF-e 55.

| # | Decisão | Consequência de projeto |
|---|---|---|
| 1 | **Pedido de marketplace VIRA `venda`** | Acrescentar `MARKETPLACE` ao enum `origem_venda`. DRE, Lucratividade, Relatório de Vendas e Kardex passam a incluir marketplace **sem código novo** |
| 2 | **Não entra no caixa** | `venda.id_caixa` fica **nulo** — a coluna já aceita. O dinheiro do ML cai na conta dias depois, não na gaveta; vinculá-lo quebraria o Fechamento de Caixa |
| 3 | **Não gera comissão** | `produto_movimento_detalhe.id_funcionario` fica **nulo** — a coluna já aceita, e o Relatório de Comissões agrupa por funcionário. Zero código |
| 4 | **A taxa do ML entra como `tipo_carteira`** | Carteira "MERCADO LIVRE" com `taxa_administradora` (percentual do ML) e `prazo_pagamento` (dias até liquidar), categoria `CARTAO_CREDITO`. DRE, Lucratividade e Fluxo de Caixa funcionam pelo caminho que já existe |
| 5 | ⛔ **Marketplace EXIGE controle de estoque** | Ver §8.1 — são **dois** pontos de guarda, não um |
| 6 | **NF-e 55 fica para depois** | Opção C adiada até a resposta da SEFAZ/PR sobre o `cStat 974` |
| 7 | **Cota do plano: marketplace CONTA** | Ver §8.2 — recomendação minha, reversível numa linha |

### 8.1 ⛔ "Se vende em marketplace, não pode existir estoque negativo"

Decisão literal do dono do produto. Ela supera, **para quem tem canal conectado**, o padrão de
`cfg_permite_estoque_negativo` (que nasce ligado, V055, porque a loja típica não controla estoque).

⚠️ **São dois guardas, e o segundo é o que se esquece:**

1. **Não deixar conectar canal** enquanto `cfg_permite_estoque_negativo = true`. A tela de conexão
   diz o que fazer e leva para Parâmetros do Sistema.
2. **Não deixar religar o parâmetro** enquanto existir canal conectado. Sem este, a loja destrava
   o overselling pela porta dos fundos: o anúncio no ML segue prometendo estoque que não existe, e
   quem paga a conta é a reputação do lojista no marketplace.

Só o primeiro seria uma trava decorativa — a mesma família de erro de
[[feedback_teste_de_guard_passa_pelo_motivo_errado]]: proteger a porta da frente e deixar a de trás
aberta dá a sensação de proteção sem a proteção.

### 8.2 Cota do plano: marketplace **conta** (recomendação, reversível)

O dono do produto delegou (*"o restante segue como você sugeriu"*). Registrando a escolha **e o
porquê**, para poder ser revista com conhecimento de causa:

**Conta.** Duas razões:

1. **Coerência com o ADR-015** — a dimensão cobrada é *venda processada no mês*. Um pedido de
   marketplace é processado, estocado, impresso e relatado pelo ERP exatamente como uma venda de
   balcão; consome o mesmo valor.
2. ⭐ **A direção é mais fácil de reverter.** Começar contando e depois deixar de contar é um
   presente ao cliente. Começar sem contar e depois passar a contar é **aumento de preço para
   quem já é cliente** — gera churn. Na dúvida, escolher a direção cujo arrependimento é barato.

⚠️ **O risco assumido**, escrito para não ser esquecido: um lojista com 300 pedidos/mês no ML
estoura o plano justamente ao adotar o recurso que mais diferencia o produto. Se isso virar atrito
de adoção, a reversão é **uma linha** — `PedidoImportacaoService` deixa de chamar
`LimiteVendasService`.

⚠️ E note que o incremento é **puro** (ADR-015): cancelar pedido de marketplace **não devolve
cota**, igual ao PDV.

### 8.3 Perguntas que continuam em aberto (não bloqueiam o começo)

- **Preço no ML é o mesmo do ERP?** (pergunta 4) — se for diferente, é o módulo `precos/`, que não
  existe. Enquanto não for respondida, a Opção A sincroniza **estoque**; o preço fica atrás de uma
  chave desligada.
- **Modalidade de Mercado Envios** (pergunta 3) e **conta do piloto** (pergunta 7) — só pesam
  quando a Opção B chegar aos pedidos.
- **Uma conta ML por empresa** (pergunta 6) — o desenho já assume `canal → empresa`; confirmar no
  primeiro uso real.

---

## 9. Roteiro em blocos

Mesmo formato do módulo fiscal (B0–B9), que funcionou: blocos pequenos, cada um entregando algo
verificável, e o que depende de terceiro isolado no fim.

| Bloco | Entrega | Depende de |
|---|---|---|
| **M0** | Migration `origem_venda += MARKETPLACE`; interface `CanalDeVenda`; modelo de `canal` com credenciais cifradas; **os dois guardas de estoque (§8.1)** | nada ✅ |
| **M1** | OAuth do ML: iniciar, retorno com `state`, refresh automático do token de 6 h | `client_id` da Vetor |
| **M2** | Leitura: listar anúncios do lojista, tela de **vincular anúncio ↔ variação** (R6) | M1 |
| **M3** | Escrita: `atualizarEstoque` via outbox, honrando `429` e **lendo o anúncio antes de escrever** (armadilha das variações, §2.4) | M2 |
| **M4** | Painel de saúde: fila de erros, dead-letter, reprocessar (R7) | M3 |
| **M5** | Webhook `orders_v2` + polling de segurança; importação idempotente | M1 |
| **M6** | Pedido vira `venda` (origem MARKETPLACE, sem caixa, sem comissão); reserva no recebido (ADR-004) | M5 |
| **M7** | Fila de expedição (R5): estados, baixa de estoque no envio, cancelamento devolve reserva | M6 |
| ⏭️ **M8** | *(Opção C, adiado)* NF-e 55, XML ao ML, etiqueta, `confirmarEnvio` | `cStat 974` |

**M0 não depende de nada** e começa agora.

---

## 8.4 ✅ Preço por canal — fecha a pergunta 4 (2026-08-25)

Decisão do dono do produto, literal:

> *"às vezes o usuário pode ter um preço diferente para o marketplace, que pode ser **maior** que o
> preço de venda na loja física, ou **menor** que a loja física"*

Isso libera o `atualizarPreco`, que eu tinha deixado deliberadamente **desligado** na interface
justamente esperando esta resposta.

### Onde o preço mora

O ERP já tinha três preços em `produto` (`preco_custo`, `preco_venda`, `preco_oferta` com janela de
datas). O do marketplace é o **quarto**, e ele **já tinha casa**: `anuncio.preco`, criada na V020.
Não foi preciso inventar tabela nova.

O que faltava era responder duas perguntas que a coluna sozinha não responde:

**(a) Como o preço nasce, sem o lojista digitar 600 preços à mão?**
`canal.perc_preco` (V064) é a **regra do canal** — o gerador. `preco_anuncio =
produto.preco_venda × (1 + perc/100)`.

⭐ **Aceita negativo, de propósito.** O dono do produto disse "maior **ou menor**", e o caso é real:
um lojista pode aceitar margem menor no canal para ganhar volume ou girar estoque parado. O CHECK
permite de −100 (exclusivo) a 1000 — o piso porque abaixo dele o preço ficaria negativo, o teto
generoso porque não é papel do banco decidir a margem do lojista, mas finito para pegar dedo
escorregado (1500 quando queria 15,00).

**(b) O que acontece quando o lojista reajusta a tabela da loja?**
`anuncio.preco_manual` (V064) marca que aquele preço foi **digitado**. Preço derivado acompanha o
reajuste; preço digitado **não é tocado**.

⚠️ **A marca não é enfeite** — é a lição de [[feedback_efeito_derivado_congela_valor_parcial]],
deste próprio projeto: guardar por *"o usuário editou"*, nunca por *"eu já calculei"*. Sem ela, o
dia do reajuste geral é o dia em que todo preço ajustado à mão no marketplace some **em silêncio**.

### ⚠️ Mas preço manual também não pode envelhecer calado

Não sobrescrever é meio caminho. Se a loja reajustou 30% e o anúncio ficou com o preço antigo, o
lojista pode estar vendendo **abaixo do custo** no marketplace sem saber — e ninguém olha uma tela
que nunca reclama. Por isso `PrecoDoCanal.defasado()` alimenta um **aviso** no painel de saúde
(R7). Avisa; **não muda**. Mudar seria desfazer a decisão dele pela porta dos fundos, que é o
defeito que a marca existe para evitar.

### Decisões de arredondamento (P7 — dinheiro é `BigDecimal`)

Duas, ambas presas por teste em `PrecoDoCanalTest`:

1. **`HALF_UP`**, o arredondamento comercial que o resto do produto usa. `HALF_EVEN` aqui faria o
   preço do ML divergir do da loja por um centavo em metade dos empates — divergência impossível
   de explicar ao lojista.
2. **Arredonda uma vez, no fim.** Arredondar o fator antes de multiplicar espalha o erro
   proporcionalmente ao preço: some num produto de R$ 10 e aparece num de R$ 800.

E `preco_venda` nulo **não vira anúncio a R$ 0,00** — publicar zero num marketplace é pior que não
publicar.

### ⏭️ O que fica em aberto aqui

- ✅ **Preço de oferta NÃO acompanha** — decidido em 2026-08-25. Ver §8.6.
- **O padrão de `perc_preco` ao conectar** nasce **0** (mesmo preço da loja). Ver §8.5.

## 8.5 O padrão de `perc_preco` é 0 — e por quê

Seguindo [[feedback_parametro_novo_pergunte_o_padrao]], o que importa não é o valor da caixa e sim
o **comportamento concreto**: com 0, ao conectar um canal e vincular anúncios, **o preço no
marketplace nasce igual ao da loja física**.

Escolhi 0 porque a alternativa seria o sistema **arbitrar a margem do lojista** — chutar um
acréscimo de 15% ou 18% "para cobrir a comissão do ML" seria decidir o preço dele sem ele pedir, e
o preço é a decisão comercial mais dele que existe.

⚠️ **O risco assumido:** um lojista distraído conecta, vincula tudo a 0% e passa a vender no ML ao
preço de balcão, perdendo 11–19% de comissão em cada venda. Por isso a tela de conexão precisa
**avisar** sobre a comissão do canal no momento de escolher o percentual — o aviso é obrigatório,
não decorativo, porque é ele que substitui o palpite que decidimos não dar.

## 8.6 ✅ Preço em oferta **não** acompanha o canal (2026-08-25)

Decisão do dono do produto, literal: *"preço em oferta não acompanha"*.

A regra do canal deriva de **`produto.preco_venda`**, e ignora `produto.preco_oferta` mesmo com a
janela de oferta vigente. Promoção de fim de semana da loja física **não** derruba o preço do
anúncio no marketplace — quem quiser promoção no canal a faz lá, com o preço do canal.

### ⚠️ Por que isto virou decisão escrita, e não só "é o que o código faz"

Porque **já era o comportamento** — por acidente. `derivar()` recebia um `precoDaLoja` genérico, e
qual coluna alimenta esse parâmetro era escolha de quem fosse escrever o chamador (que ainda nem
existe: é o M3). Um desenvolvedor futuro olhando `produto` veria uma oferta vigente sendo ignorada,
concluiria que é esquecimento e "consertaria" — derrubando o preço de **todos** os anúncios do
lojista numa promoção que ele quis fazer só no balcão.

É a mesma família de [[feedback_constante_literal_onde_ha_campo_de_dominio]]: **correto até alguém
melhorar**. Por isso o parâmetro passou a se chamar `precoDeVendaDaLoja`, com a proibição em
maiúsculas no javadoc — o nome do parâmetro é o único lugar que quem escrever o chamador
necessariamente vai ler.

O mesmo vale para `defasado()`: comparar o preço publicado contra o **promocional** acusaria
"defasado" em toda promoção da loja e treinaria o lojista a ignorar o aviso — o pior desfecho
possível para um alerta.

### ⏭️ O que ainda NÃO está preso por teste (dito de propósito)

Os testes de `PrecoDoCanalTest` provam a aritmética (sinal, arredondamento, nulos), mas **não** há
como um teste unitário provar que o chamador vai passar `preco_venda` e não `preco_oferta` — o
chamador é o M3 e ainda não existe. **Quando o M3 for escrito, o teste dele precisa incluir um
produto com oferta vigente**, provando que o preço do anúncio ignora a oferta. Sem esse teste, esta
decisão está protegida só por javadoc.
