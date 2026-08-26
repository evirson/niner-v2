# Estudo: Integração com Marketplaces — Mercado Livre (primeiro canal)

> **Status: EM IMPLEMENTAÇÃO.** As decisões da §4 estão fechadas (§8) e o **escopo A+B aprovado está completo** — M0 a M7
> existem em código — ver o estado real na **§10**, que é a seção a acreditar quando esta divergir.
> Escrito em 2026-08-25 como estudo; cabeçalho corrigido em 2026-08-26, quando M1, M2, M3 e M5–M7 entraram.
> Requisitos cobertos: R3, R5, R6, R7 · Princípios: P1, P2, P3, P8
>
> ⚠️ **Nenhuma chamada real ao Mercado Livre foi feita até hoje.** Tudo o que está verificado, está
> verificado contra **WireMock**, que responde o que nós programamos. Os fatos da §2 vieram de
> documentação lida, e leitura de documentação já nos traiu aqui (o XSD passou e a SEFAZ recusou).
> A primeira chamada real vai encontrar divergências — é esperado, não é fracasso.
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

**O que faltava (julho):** nenhuma linha de domínio em `canais/`, `pedidos/`, `precos/`, `integracao/`
— os quatro pacotes tinham só `package-info.java`. ✅ Hoje `canais/` e `integracao/mercadolivre/`
existem (M0–M5), e a importação de pedido vive em `integracao/Pedido*`; `pedidos/` e `precos/`
continuam vazios como pacote — o M6 decide se o domínio de pedido mora lá ou vira `venda`.

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
| 1 | ✅ **FEITO (2026-08-25).** **Criar a aplicação no *My Applications*** (conta ML da Vetor/MITRYUSCASH) e me passar o `client_id`. ⛔ O `client_secret` **não por chat** — vai por variável de ambiente, como o do Mercado Pago | É a identidade da integração; uma só para todos os lojistas | Tudo |
| 2 | ✅ **FEITO (2026-08-25).** **Declarar o `redirect_uri`** na aplicação. Sugestão: `https://api.nainer.com.br/api/publico/canais/mercadolivre/retorno` | Tem de bater exatamente, e ser HTTPS | OAuth |
| 3 | ⏸️ **URL preenchida, tópicos DESMARCADOS de propósito (2026-08-25) — o endpoint ainda não existe; ver §11.6.** **Marcar os tópicos de notificação** e o callback `https://api.nainer.com.br/api/publico/webhooks/mercadolivre` | Sem isso, só resta polling | Pedido em tempo real |
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
| **M1** | ✅ **FEITO 2026-08-26.** OAuth do ML: iniciar (`/api/v1`, autenticado — ver §10.3), retorno com `state` (`/api/publico`), refresh automático do token de 6 h | `client_id` da Vetor ✅ |
| **M2** | ✅ **FEITO 2026-08-26.** Leitura: listar anúncios do lojista, tela de **vincular anúncio ↔ variação** (R6), com sugestão por SKU | M1 ✅ |
| **M3** | ✅ **FEITO 2026-08-26.** Escrita: `atualizarEstoque` via outbox, honrando `429` e **lendo o anúncio antes de escrever** (armadilha das variações, §2.4) | M2 |
| **M4** | ✅ **FEITO.** Painel de saúde: fila de erros, dead-letter, reprocessar (R7) | M3 |
| **M5** | ✅ **FEITO 2026-08-26.** Webhook `orders_v2` + polling de segurança; importação idempotente | M1 ✅ |
| **M6** | ✅ **FEITO 2026-08-26.** Pedido vira `venda` (origem MARKETPLACE, sem caixa, sem comissão); reserva no recebido (ADR-004) | M5 |
| **M7** | ✅ **FEITO 2026-08-26.** Fila de expedição (R5): estados e fila (a baixa de estoque ficou no PAGO, no M6 — ver §10.10) | M6 |
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

---

## 10. Estado da implementação (2026-08-26)

**M0 a M4 fechados** — a Opção A está completa. Até 25/08 os blocos foram escolhidos para não depender do
`client_id` (que a MITRYUSCASH ainda estava providenciando); com ele em mãos em 26/08, o OAuth (M1)
e o vínculo de anúncios (M2) entraram no mesmo dia.

⏭️ **O próximo é o M3** — e ele é o que transforma a integração em produto: sem ele o ERP conecta e
vincula, mas **não publica nada** (§10.5).

| Bloco | Estado | Onde |
|---|---|---|
| **M0** — fundação | ✅ | `V063`, `V064`, `canais/` |
| **Adapter do ML** (parte do M2/M3) | ✅ contra WireMock | `integracao/mercadolivre/` |
| **Worker do outbox** | ✅ | `integracao/outbox/` |
| **Tela + painel de saúde** (R7) | ✅ | `web/src/pages/canais/CanaisVenda.tsx` |
| **M1 — OAuth** | ✅ **2026-08-26** | `V065`, `canais/EstadoOAuthRepositorio`, `integracao/mercadolivre/MercadoLivreOAuth*` |
| **M2** — vincular anúncio ↔ variação (R6) | ✅ **2026-08-26** | `V066`, `canais/Anuncio*`, `web/.../VincularAnuncios.tsx` |
| **M4** — painel de saúde | ✅ | `CanaisVenda.tsx` |
| **M3** — escrita de estoque/preço | ✅ **2026-08-26** | `V067`, `integracao/Sincronizacao*` |
| **M5** — webhook + polling + importação | ✅ **2026-08-26** | `V068`, `integracao/Pedido*` |
| **M6** — pedido vira venda + reserva | ✅ **2026-08-26** | `V069`, `integracao/PedidoVenda*` |
| **M7** — fila de expedição (R5) | ✅ **2026-08-26** | `V070`, `integracao/Expedicao*`, `web/.../FilaExpedicao.tsx` |
| M8 — NF-e 55 | ⏭️ | depende do `cStat 974` |

**1056 testes verdes**, `tsc -b` limpo.


### 10.1 As decisões de implementação que valem revisão

**`atualizarEstoque` recebe a lista inteira de variações.** Não é assinatura desajeitada — é a
armadilha da §2.4 transformada em barreira: uma assinatura do tipo *"variação X agora tem N"*
convidaria todo adapter futuro a mandar um `PUT` parcial e apagar as demais variações do anúncio.
O adapter **lê o anúncio antes de escrever**, sempre, e paga uma chamada a mais por sincronização.
A assimetria decide: o limite do ML é 1.500/min (folgado), e o custo de errar é perda de dado no
anúncio do lojista.

**Falha transitória × definitiva é a decisão central do transporte.** `429` (corpo vazio) e `5xx`
viram `CanalIndisponivelException` e o outbox reagenda; `4xx` de negócio vira erro definitivo.
Confundir custa nos dois sentidos: definitivo tratado como transitório gira até o dead-letter
escondendo a causa atrás de trinta tentativas idênticas; transitório tratado como definitivo joga
no dead-letter algo que só precisava esperar meio minuto.

**Agendador e trabalho em beans separados.** `@Transactional` não vale em auto-invocação: chamado
de dentro do próprio bean, o método roda **sem transação**, o lock do `FOR UPDATE SKIP LOCKED` se
solta no fim do `SELECT` e dois workers pegam o mesmo evento. Mesmo desenho de
`CobrancaWebhookJob` × `CobrancaWebhookProcessador`.

**⚠️ `outbox_evento` tem RLS.** Worker sem `TenantContext` lê **zero linha em silêncio** — sem
erro, sem log, apenas nunca despachando. Foi assim que os jobs de fiscal ficaram inertes até
2026-08-19. Daí o laço por tenant a partir de `plataforma.tenant`.

**O painel conta os três estados separadamente.** Pendente é normal, erro está tentando sozinho,
dead-letter parou e espera gente. Um "total de problemas" mostraria número alarmante num dia
saudável.

**Reprocessar zera as tentativas** — senão o evento que chegou ao dead-letter com 12 falhas
voltaria e morreria na tentativa seguinte, e o botão pareceria não funcionar.

**Desconectar apaga a credencial de verdade** (`credenciais = NULL`), não só troca o status. Os
vínculos de anúncio ficam: são trabalho do lojista, e desconexão temporária não pode custar tudo.

### 10.2 ⚠️ O que NÃO está coberto, dito de propósito

- **Nenhuma chamada real ao Mercado Livre foi feita.** Tudo está verificado contra WireMock, que
  responde o que eu programei que ele responda. Os fatos da §2 vieram de documentação lida, e este
  projeto já aprendeu que documentação lida não é comportamento medido (o XSD passou e a SEFAZ
  recusou). **A primeira chamada real vai encontrar divergências** — é esperado, não é fracasso.
- ✅ **A regra "preço de oferta não acompanha" (§8.6) saiu do javadoc e virou teste** em
  2026-08-26. A dívida dizia *"quando o M3 for escrito, o teste dele precisa incluir um produto com
  oferta vigente"* — mas o **chamador nasceu no M2**, ao vincular o anúncio, então a dívida venceu
  ali: `AnuncioVinculoTest.precoDoAnuncioIgnoraAOfertaDaLoja` lança uma oferta vigente de R$ 10,00
  sobre um preço de venda de R$ 100,00 e exige que o anúncio publique **100,00**. ⚠️ Conferido do
  jeito que este projeto aprendeu a conferir: a regra foi **quebrada de propósito** (derivando de
  `COALESCE(preco_oferta, preco_venda)`) e o teste **reprovou** — teste de guarda que ninguém viu
  falhar não provou nada.
- **Nenhuma tela foi usada com um canal conectado de verdade.** O estado `CONECTADO` já é
  alcançável pelo OAuth (M1), mas só contra WireMock; nenhum lojista real autorizou nada ainda.
- ✅ **O outbox foi ligado nas duas pontas** (M3, §10.5) — mas **contra WireMock**. Nenhum saldo
  chegou a um anúncio de verdade.
- ⚠️ **A chamada HTTP ao canal acontece DENTRO da transação do lote do outbox.** É o desenho que já
  existia: `OutboxProcessador.processarLoteDoTenantCorrente()` é `@Transactional` porque é a
  transação que segura os locks do `SKIP LOCKED`. Com lote de 25 e timeout de 30 s por chamada, o
  pior caso segura uma conexão do pool por vários minutos. Não foi redesenhado no M3 — fica
  registrado como risco conhecido, a medir quando houver volume real.
- ⚠️ **Eventos repetidos não são deduplicados.** Uma importação que mexe várias vezes na mesma
  variação enfileira um evento por vez. É **deliberado**: dedupe por índice parcial sobre
  `PENDENTE` abriria uma janela real de perda — o worker mantém o status `PENDENTE` enquanto
  processa, então uma mudança de saldo que chegasse nesse intervalo seria engolida por
  `ON CONFLICT DO NOTHING` e o ML ficaria com o valor antigo, sem evento pendente para corrigir.
  Publicar duas vezes é desperdício; perder a última mudança é o defeito que o módulo existe para
  não ter.

---
### 10.3 ⚠️ O M1 desviou da §9 em um ponto — e o desvio é o certo

A §9 dizia *"OAuth do ML: **iniciar**, retorno com `state`, refresh"*, e a §5 previa **as duas
metades em `/api/publico`**. Não dá:

- **Iniciar** ficou em **`/api/v1`, autenticado** (`GET /api/v1/canais/{id}/mercadolivre/autorizar`).
  Um endpoint anônimo não tem como saber de que loja é a autorização — e a URI de redirect não
  aceita parte variável para dizer. Ou o tenant vem do JWT no início, ou viria da URL, que é
  escolhida por quem chama.
- **Concluir** ficou em **`/api/publico`, anônimo** (`GET /api/publico/canais/mercadolivre/retorno`),
  porque quem chega ali é o navegador do lojista devolvido pelo ML, sem JWT nenhum. Este é o
  endereço registrado no DevCenter, e **renomeá-lo quebra a conexão de todos os lojistas de uma
  vez**, com uma mensagem do ML que não diz que a culpa é nossa.

O vínculo entre as duas metades é a linha da **V065** (`plataforma.oauth_estado_canal`): hash do
`state`, uso único, 10 minutos, em `plataforma` porque o retorno roda **sem** `TenantContext` — é
ele que vai *descobrir* o tenant, e sob RLS a consulta devolveria zero linha em silêncio.

### 10.4 ⛔ O primeiro OAuth real NÃO fecha em `localhost` — decisão pendente

A `redirect_uri` registrada é **de produção** (`https://api.nainer.com.br/...`) e o ML exige
casamento **caractere por caractere**, sem parte variável. Clicar em "Conectar" numa API rodando
em `localhost` leva o lojista ao consentimento normalmente — e o ML devolve o navegador para a
**API de produção**, que tem outro banco e não conhece aquele `state`. Resultado: a volta acusa
"este pedido de conexão não vale mais", e a causa não aparece em log nenhum do dev.

Duas saídas, e é decisão do dono do produto:

1. **Testar em produção** — publicar e deixar que a primeira autorização real seja o teste. O
   `redirect_uri` já está certo; o `client_secret` vai para a variável de ambiente **do servidor**.
   ⚠️ Cada rodada de diagnóstico passa a depender de deploy, e diagnóstico de OAuth é feito de
   rodadas.
2. **Registrar uma segunda URI de redirect** apontando para um túnel. Depende de o painel do ML
   aceitar mais de uma (§11.3 diz que a aplicação aceita várias, *"se a tela só aceitar uma, fica a
   de produção"* — **não conferido na tela**), e exige **hostname fixo**: túnel grátis muda de
   endereço a cada restart, e casamento exato significa que todo restart quebraria o login.

### 10.5 ✅ O outbox foi ligado nas duas pontas (M3)

**Medido em 2026-08-26, antes do M3:** ninguém chamava `OutboxRepositorio.enfileirar()` e **nenhum
`ManipuladorDeEvento` estava registrado**. O motor funcionava (worker, retry, dead-letter, painel),
mas um evento que chegasse à fila viraria dead-letter com *"nenhum manipulador registrado"* — e o
painel mostrando *"0 pendentes, 0 erros"* parecia saúde quando era **ausência de trabalho**.

As duas pontas fechadas:

**1. Quem avisa — gatilho no banco (V067), não nos serviços.** Mesma decisão do
`cfg_permite_estoque_negativo` (V054) e pelo mesmo motivo: debitam ou creditam estoque o PDV, a
transferência, a devolução ao fornecedor, a devolução de venda, o **cancelamento de entrada** (de
que ninguém lembra), o cancelamento de devolução, o balanço e a importação — mais o que vier.
Espalhar o "avise o canal" por serviço garante matematicamente que uma rotina fique de fora, e a
que ficar de fora vira anúncio prometendo estoque que não existe, **em silêncio**.
`produto_estoque` é onde o saldo realmente mora, qualquer que seja o caminho — e o INSERT no
outbox acontece **na mesma transação** do movimento, que é o outbox pattern inteiro (P2).

⭐ O gatilho de **preço** só avisa; **quem calcula é o Java**. Recalcular `anuncio.preco` em SQL
criaria duas implementações da mesma regra de dinheiro (P7) — `PrecoDoCanal` arredonda HALF_UP,
uma vez no fim, e recusa preço nulo. Elas divergiriam no dia em que só uma fosse corrigida, num
centavo que ninguém explica ao lojista.

**2. Quem executa** — `SincronizacaoEstoqueManipulador` e `SincronizacaoPrecoManipulador`.

⚠️ **Duas conversões que só podem errar para um lado:** saldo fracionário
(`numeric(14,3)`) vira inteiro **para baixo** — 2,7 → **2**, porque arredondar para cima promete
uma peça que não existe; e saldo negativo (permitido no ERP) vira **zero**, porque "−3
disponíveis" não significa nada num anúncio. As duas erram para o lado de **prometer menos**:
vender menos do que se tem custa uma venda, vender mais custa a reputação do lojista.

⚠️ **Vincular também enfileira**, e é o único ponto em que enfileirar por Java é certo: o gatilho
reage a mudança de `produto_estoque`, e vincular não mexe em estoque nenhum. Sem isso o anúncio
recém-vinculado ficaria com o saldo errado do ML até a próxima venda — e o caso comum é o lojista
vincular **justamente porque** o número lá está errado.

### 10.6 ⚠️ Uma decisão de produto tomada no M2, à espera de confirmação

**Um produto do ERP só pode alimentar UM anúncio por canal** (índice
`anuncio_canal_variacao_erp_uk`, V066). Duas linhas apontando para a mesma variação com 5 peças
publicariam 5 em **cada** anúncio — prometendo 10 ao comprador com 5 no estoque, e o marketplace
pune cancelamento com reputação, que é o ativo do lojista.

⚠️ **Isto fecha um caso de uso real:** o lojista que anuncia o mesmo produto duas vezes para ganhar
alcance. Fechar foi a escolha segura enquanto não existe uma regra de **rateio** decidida pelo dono
do produto — inventar o rateio no código seria decidir por ele. Havendo decisão, é **um índice para
remover**, mais a regra de divisão.

### 10.7 O que o M2 entregou, e o que ele NÃO cobre

✅ Lista os anúncios da conta conectada · **uma linha por variação do canal** (anúncio com 12
tamanhos = 12 linhas, porque é o saldo da variação que se publica) · **sugestão por SKU**, casando
`seller_custom_field` do ML com `produto_barra.sku` **ou** `.ean` · vincular/desvincular · lista de
vínculos que **não depende do ML estar no ar**.

⛔ **Não cobre**: publicar anúncio novo no ML (o R6 é *vincular* anúncio existente — publicar do
zero exige categoria, ficha técnica e atributos obrigatórios que variam por categoria, e é escopo
próprio); editar o preço do anúncio à mão (a coluna `preco_manual` existe e ninguém a liga ainda);
e a tela **nunca foi usada com um canal conectado de verdade** — tudo contra WireMock.

### 10.8 M5 — a notificação chega SEM tenant, e é o mesmo problema do `state`

⭐ **Vale registrar o padrão, porque ele volta sempre que algo de fora bate na porta.** O webhook do
Mercado Livre chega sem JWT e sem `TenantContext`; o corpo traz o **`user_id` do vendedor**, e é
só isso. Descobrir de quem é exigiria varrer `canal` — que tem RLS e devolveria **zero linha em
silêncio**.

A solução é a mesma da V065: **uma tabela global, sem RLS** — `plataforma.canal_externo`, mapeando
`(marketplace, vendedor) → (tenant, canal)`. ⚠️ Ela **não guarda segredo**: `conta_externa` é o id
público do vendedor, o mesmo que já aparece na tela de Canais. O token continua cifrado em
`canal.credenciais`, sob RLS.

⛔ **E a chave é UNIQUE por (tipo, conta_externa):** a mesma conta de Mercado Livre não pode estar
conectada em dois tenants. Se estivesse, uma notificação de venda seria **ambígua** — e o desempate
importaria o pedido de um lojista dentro da loja de outro (P8). Tentar conectar uma conta já
conectada é recusado com uma frase que diz o que houve.

#### ⚠️ Responder 200 mesmo no caso ruim

O ML **reenvia** o que falha e **desativa** o callback que falha repetidamente. Devolver 4xx para
uma notificação de vendedor desconhecido — que acontece de verdade: lojista que desconectou, conta
de teste antiga, notificação atrasada — treinaria a plataforma a desligar o nosso endereço, e aí
**nenhum** lojista receberia pedido. O endpoint responde 200 **sempre**, e registra.

#### ⛔ Item sem vínculo recusa o pedido INTEIRO

Se qualquer item aponta para um anúncio ainda não vinculado (R6), a importação **não grava nada**.
As alternativas eram piores: importar só os itens vinculados daria um pedido com metade das linhas
— que parece completo na tela de expedição e faria a loja **despachar um pacote faltando produto**;
e inventar uma variação gravaria a venda de um produto no estoque de outro.

⭐ **O custo de recusar é baixo porque a fila não desiste:** a notificação continua pendente com a
mensagem visível, e o polling traz o pedido de novo. No minuto em que o lojista vincular o anúncio,
a importação passa sozinha — sem ninguém reprocessar nada à mão. Isso está preso por teste.

#### ⭐ O polling de segurança não é redundância

Três situações reais em que o webhook simplesmente não existe: (a) em desenvolvimento não há URL
pública; (b) os três tópicos ficaram **desmarcados** no painel do ML até este bloco existir; (c)
plataforma desativa callback que falha repetidamente — e o dia em que isso acontecer é justamente
o dia em que ninguém está olhando. Quinze minutos é o intervalo que a spec previu: um pedido que
demora quinze minutos para aparecer é um aborrecimento; um que **nunca** aparece é uma venda
perdida e uma reclamação no marketplace.

#### ⏭️ O que o M5 NÃO faz

Importa o pedido para a **fila de expedição** (`pedido`/`pedido_item`) e para por aí: **não** vira
`venda`, **não** reserva estoque, **não** consome cota do plano. Isso é o **M6**, e é a decisão nº 1
da §8 — a que mais muda o desenho.

✅ **Os três tópicos do painel do ML podem ser ligados agora** (§11.6): o endpoint existe. Ligar
continua sendo passo do painel, e o polling cobre enquanto não forem.

### 10.9 M6 — o que a conversão decide, e as duas dívidas conscientes

O ciclo: **RECEBIDO** reserva o estoque · **PAGO** vira venda (libera a reserva, dá baixa de
verdade, cria a parcela a receber e consome cota) · **CANCELADO** libera a reserva.

⭐ **A reserva fecha o laço do anti-overselling sozinha.** `disponivel` é coluna gerada
(`qtd_estoque − reservado`), o M3 publica `disponivel`, e o gatilho da V067 dispara em qualquer
UPDATE de `produto_estoque` — inclusive de `reservado`. Reservar um pedido **republica o saldo
menor no anúncio** sem ninguém pedir.

⭐ **O dinheiro entra pela carteira do canal** (§8 item 4): `canal.id_carteira` aponta para um
`tipo_carteira` com `taxa_administradora` (a comissão do marketplace) e `prazo_pagamento` (dias até
liquidar), categoria CARTAO_CREDITO. A venda nasce como **parcela em aberto** em
`contas_receber` — como uma venda no cartão —, e então **DRE, Lucratividade e Fluxo de Caixa
funcionam pelo caminho que já existe**. A carteira é criada junto com o canal, com o nome dele.

⚠️ **Ela nasce com taxa ZERO**, e isso é decisão: o ML cobra 11–19%, mas arbitrar um percentual
seria decidir a margem do lojista (mesmo motivo de `perc_preco` nascer 0, §8.5). ⛔ **Enquanto a
taxa estiver zerada, a DRE superestima o lucro** — número plausível e errado, a família de defeito
que a Lucratividade documentou em 2026-08-25. A tela precisa avisar.

#### ⛔ Dívida 1 — cancelamento no canal NÃO cancela a venda

Se o pedido já virou venda e o marketplace o cancela, o M6 **libera a reserva e registra**, mas não
cancela a venda. Cancelar venda no Nainer é rotina com regra própria: estorna estoque, mexe em
caixa, exige motivo e, havendo nota, fala com a SEFAZ. **Disparar isso a partir de uma notificação
automática de terceiro seria deixar um marketplace cancelar documento fiscal da loja.** O pedido
fica marcado CANCELADO e o lojista decide na tela de Cancelamento de Venda.

#### ⛔ Dívida 2 — pedido sem estoque não vira venda, e fica tentando

Com o controle de estoque ligado (que marketplace **exige**, §8.1), converter um pedido cujo
produto está zerado é recusado pela trava de estoque negativo — a mensagem diz qual produto e
quanto falta, e o polling tenta de novo a cada 15 min, então **conserta sozinho** quando o lojista
acertar o saldo.

⚠️ É o comportamento **consistente com o resto do produto** (o PDV também é barrado), mas o custo
está escrito: **enquanto não for resolvido, a venda não existe no ERP** — não aparece em relatório
nem no financeiro, embora o dinheiro seja real. Acontece quando o saldo do ERP e o do anúncio
divergem (venda simultânea no balcão, sincronização atrasada). ⏭️ **Decisão do dono do produto:**
manter assim, ou permitir a exceção para venda de marketplace, já que ela **registra um fato
observado** — a peça saiu.

### 10.10 M7 — a fila de expedição, e um desvio do roteiro que vale explicar

`PAGO → EM_SEPARACAO → ENVIADO`. Três estados, não seis: cada estado a mais é uma pergunta a mais
para quem está com a caixa na mão, e a fila de uma loja pequena não comporta cerimônia.

⚠️ **O roteiro dizia "baixa de estoque no envio", e ela ficou no PAGO (M6).** É onde a venda nasce
— e uma venda sem baixa de estoque seria um buraco entre o M6 e o M7, com o saldo do ERP mentindo
durante todo o intervalo de separação. A **reserva** já cobre o que a baixa-no-envio pretendia
proteger: a peça fica indisponível desde o RECEBIDO.

⛔ **RECEBIDO não entra na fila.** Pedido não pago pode não ser pago nunca: separar mercadoria para
ele é trabalho jogado fora e, pior, a peça sai da prateleira e some do fluxo da loja. A reserva
segura o estoque; a separação espera o dinheiro.

⚠️ **A tela NÃO é ADMIN-only**, ao contrário do resto do módulo de canais — quem separa e embala é
o operador. Exigir ADMIN obrigaria o dono a despachar tudo, ou a dar acesso de administrador a quem
só precisa de uma lista de itens. Por isso ela vive em **Frente de Loja**, não no grupo de Canais.

#### ⭐ O defeito que a V070 existe para impedir

`salvarPedido` atualiza o status a cada chegada do pedido — e o pedido chega muitas vezes. Se o
lojista marca EM_SEPARACAO às 10h e o polling roda às 10h15 com o marketplace ainda dizendo
"paid", um UPDATE ingênuo devolveria o pedido à fila — **já separado** — e alguém montaria um
segundo pacote para a mesma venda. Silenciosamente.

A regra vive numa função do banco (`fn_status_pedido_do_canal`) para não depender de quem escreve
o próximo UPDATE: **CANCELADO do canal sempre vence** (o comprador desistiu, é fato do canal), e
fora isso o canal só manda enquanto o trabalho físico não começou. Preso por dois testes.

#### ⛔ `confirmarEnvio` no Mercado Livre não faz nada — de propósito

Em **Mercado Envios** quem controla o estado do envio é o **próprio marketplace**: o pedido vira
"enviado" quando a transportadora bipa a etiqueta, e a etiqueta é liberada pela **NF-e** (§2.6, e é
a Opção C, travada no `cStat 974`). Não existe "avisar o ML que despachei" — a informação vai no
sentido contrário.

⚠️ **Não fazer nada é melhor que inventar uma chamada.** A alternativa seria chutar um endpoint que
a documentação não descreve para este caso e, sem sandbox no ML, o primeiro teste seria em produção
no envio de um lojista de verdade.

⭐ O manipulador de outbox existe assim mesmo porque a **Shopee** e o **envio próprio** precisam
dele, e porque é a interface que a spec definiu — descobrir isso ao plugar o segundo canal seria
tarde. E o aviso passa pelo outbox, não pela requisição: o despacho **já aconteceu no mundo
físico**, e uma indisponibilidade do marketplace não pode impedir o lojista de registrar que o
pacote saiu.

## 11. Passo a passo para criar a aplicação no Mercado Livre

Levantado em 2026-08-25, quando o dono do produto foi criar a conta. São **duas coisas
diferentes**, e confundi-las custa tempo.

### 11.1 A conta ML — **https://www.mercadolivre.com.br/**

⚠️ **Não existe "conta de integrador" no Mercado Livre.** É conta comum. O que separa vendedor de
integrador não é o tipo de conta, é o que se faz com ela.

- Cadastrar como **pessoa jurídica**, no CNPJ da **MITRYUSCASH**.
- ⛔ **Tem de ser a conta do proprietário da solução** — não a pessoal de ninguém, não a de um
  cliente. O próprio ML avisa que isso evita "problemas futuros de transferência de conta": a
  aplicação nasce presa à conta, e mover depois é dor de cabeça.
- Se a MITRYUSCASH já tiver conta da empresa, ela serve.

⚠️ **A conta da Vetor nunca vende nada no ML.** Ela é dona da aplicação e cria os usuários de
teste. Quem vende é o lojista, na conta de vendedor dele, que **autoriza** a aplicação. Mesmo
modelo do Mercado Pago.

### 11.2 A aplicação — **https://developers.mercadolivre.com.br/devcenter**

Logado com aquela conta: *Minhas aplicações* → **Criar uma aplicação**.

⏳ **Começar pela validação do titular** (upload do documento do responsável): é a única parte que
depende do ML e pode levar dias. O formulário em si leva minutos.

| Campo | Valor |
|---|---|
| URI de redirect | `https://api.nainer.com.br/api/publico/canais/mercadolivre/retorno` |
| Escopos | `read`, `write`, **`offline_access`** |
| PKCE | desligado (o segredo fica no servidor) |
| Tópicos de notificação | `orders_v2`, `items`, `shipments` |
| Callback | `https://api.nainer.com.br/api/publico/webhooks/mercadolivre` |

⚠️ **`offline_access` é o que mais dói esquecer:** sem ele o `access_token` morre em 6 h e o
lojista teria de reautorizar quatro vezes por dia.

⚠️ **A URI de redirect tem de bater caractere por caractere** com a do código, e não aceita parte
variável — é por isso que o `id_tenant` viaja no `state`, nunca na URL (§2.1).

### 11.2.1 ✅ Como o formulário foi preenchido de verdade (2026-08-25)

⚠️ **A tela do DevCenter não usa o vocabulário da doc.** Ela não pede "escopos": pede **fluxos
OAuth**, **negócios** e **permissões** por área funcional. A tradução que usamos — e que quem for
mexer deve conferir na tela antes de repetir:

| Na tela | Escolhido | Equivale a |
|---|---|---|
| Fluxo **Authorization Code** | ✅ | o fluxo do lojista autorizando |
| Fluxo **Refresh Token** | ✅ | **o `offline_access`** — é aqui que ele aparece |
| Fluxo **Client Credentials** | ❌ | token da aplicação sem lojista, não usamos |
| **PKCE necessário** | ❌ | segredo fica no servidor |
| Negócio **Mercado Livre** / **VIS** | ✅ / ❌ | VIS são os classificados (Veículos, Imóveis, Serviços) |

**Permissões:** *Usuários* → leitura · *Publicação e sincronização* → leitura **+** escrita
(a leitura não é opcional, §2.4) · *Venda e envios* → leitura + escrita · *Faturamento* → leitura +
escrita (§2.6). Recusadas: comunicações, publicidade, métricas, promoções.

⚠️ *Faturamento* só serve à Opção C, que está travada — pedimos assim mesmo porque **ampliar escopo
depois costuma obrigar cada lojista a reautorizar**. É o padrão de OAuth e o que vimos no Mercado
Pago; **não confirmado na doc do ML**.

### 11.3 ⛔ Uma aplicação só, e o que isso obriga

**No Brasil o ML permite apenas 1 aplicação por conta** depois da validação do titular. Não dá para
ter "Nainer Dev" e "Nainer Prod" separados, como fizemos com o Mercado Pago.

Consequência: **um único `client_id` para dev e produção**. A mitigação é que a aplicação aceita
**várias URLs de redirect** — registrar a de produção e a de desenvolvimento. Se a tela só aceitar
uma, fica a de produção e o dev resolve por túnel.

### 11.4 O que volta para cá

Só o **`client_id`** (é público). ⛔ O `client_secret` **nunca por chat** — entra por variável de
ambiente, como `NINER_MP_ACCESS_TOKEN` do Mercado Pago (ADR-016).

📁 **Onde o `client_id` está** (2026-08-25): `docs/mercadolivre/api.md`, fora do git
(`.gitignore`: `docs/mercadolivre/` + `*mercadolivre*secret*`). Nasceu em `docs/mercadopago/` por
engano de pasta e foi movido no mesmo dia — é a §11.5 acontecendo na prática, agora na arrumação
do arquivo em vez da credencial. ⚠️ A regra do `.gitignore` foi criada **antes** de a pasta
existir, de propósito: pasta de credencial que ainda não está ignorada é onde o `client_secret`
entra no commit sem ninguém notar.

### 11.5 ⚠️ Mercado Pago **não é** Mercado Livre — a credencial não serve

Aconteceu em 2026-08-25: o dono do produto trouxe credenciais dizendo *"já tenho as credenciais do
Mercado Livre"*, e eram do **Mercado Pago** (aplicação `nainer-erp-assinatura`).

A confusão é fácil e vai se repetir:

| | Mercado Pago | Mercado Livre |
|---|---|---|
| Para quê | **cobrar a mensalidade** do lojista (ADR-016) | **vender** no marketplace (este módulo) |
| Portal | `mercadopago.com.br/developers` | `developers.mercadolivre.com.br/devcenter` |
| Credencial | `APP_USR-…` | `client_id` + `client_secret` |

⚠️ **Mesma empresa, mesmo prefixo de token, cadastros separados.** Credencial do Mercado Pago
**não** abre a API de anúncios e pedidos do Mercado Livre, e vice-versa.

**Como saber o que uma credencial `APP_USR-` é**, sem adivinhar pelo prefixo — que não diz nada
(credencial de *usuário de teste* também começa com `APP_USR-`):

```
curl -H "Authorization: Bearer <token>" https://api.mercadopago.com/users/me
```

`"tags": ["…","test_user"]` e apelido `TESTUSER…` ⇒ sandbox. Foi assim que se confirmou, em
2026-08-25, que o token trazido era de teste — e portanto de baixo risco, apesar de ter sido
colado no chat.

⛔ **A regra continua:** `client_id` é público e pode vir por chat; **`client_secret` e token vão
por variável de ambiente**, nunca por conversa.

### 11.6 ⛔ Tópicos de notificação: desmarcados até o endpoint existir

Os três que interessam são `items`, `orders_v2` e `shipments` (§2.3). Em 2026-08-25 **nenhum foi
marcado**, e a razão vale para qualquer plataforma: `/api/publico/webhooks/mercadolivre` **não
existe no código** — tópico ligado contra endpoint inexistente é 404 a cada notificação, e
plataforma costuma **desativar sozinha** o callback que falha repetidamente. Ligar os tópicos é
passo do painel, não recriação da aplicação.

✅ Isso **não bloqueia a Opção A**: espelho de estoque e preço é escrita nossa para o ML.
