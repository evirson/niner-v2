# Pendências abertas do Nainer

> **O que é este arquivo.** Lista **viva e consolidada** de tudo que está aberto no produto, com
> **de quem é a bola** em cada item. É a fonte única de pendências — `docs/PROGRESSO.md` conta a
> história em ordem cronológica, este arquivo conta o que **ainda falta**.
>
> **Quando falar destas pendências:** só quando o dono do produto **pedir para ler a documentação
> e a memória** ou **perguntar diretamente o que está pendente**. Nunca despejar na abertura de uma
> conversa nem no fim de uma tarefa sem relação — ele recusou isso explicitamente em 2026-08-25.
>
> **Como apresentar:** resumido e **agrupado por dono**, não as ~27 linhas cruas — ele já reclamou
> de informação demais de uma vez (*"TA MUITO CONFUSO"*).
>
> **Última revisão:** 2026-08-27 (registrada a pedido dele: *"deixe todas estas pendências
> documentadas, não esqueça de nada, e quando eu te perguntar as pendências você me fala"*).

**Estado na data desta revisão:** 60 telas em uso · 1088 testes verdes · `main` em `7792b5d`.

---

## 🔴 Bloqueadas em terceiros

### 1. `cStat 974` — nenhuma NF-e modelo 55 autorizada
Chamado **aberto na SEFAZ/PR** (texto em `docs/fiscal/chamado-sefaz-pr-csrt-974.md`). Encerrado do
nosso lado: todas as hipóteses foram **medidas e descartadas** — idCSRT, CNPJ, ambiente, hash e
propagação (esta derrubada por um token novo, ativado minutos antes, falhando idêntico). A NFC-e 65
do mesmo emitente autoriza, o que isola o problema no cadastro do responsável técnico.
⛔ CSRT é segredo — nunca por chat; confere-se pelo tamanho da coluna cifrada
(`octet_length(decode(csrt_cifrado,'base64'))` = 64 ⇒ 36 caracteres em claro).
**Bola:** SEFAZ/PR.

---

## 🔵 Bola dele (dono do produto)

### 2. Teste real do Mercado Livre — ADIADO
M0–M7 prontos (escopo **A+B completo**), **zero chamadas reais** ao ML: tudo verificado contra
WireMock. Espera as credenciais do **usuário de teste**, que ele vai passar.
⚠️ Quando ele disser *"vamos testar"*, repassar **todos** os passos, **poucos por vez**.
⚠️ Não existe "conta de integrador" no ML — é conta comum, do **proprietário da solução**
(MITRYUSCASH, PJ, titular validado). Aplicação em **developers.mercadolivre.com.br/devcenter**,
⛔ **não** no painel do Mercado Pago (ele já trouxe credencial do MP por engano uma vez). Só o
`client_id` vem por chat; o secret vai por variável de ambiente.
Roteiro: `docs/MODULOMARKETPLACE.md` §12.

### 3. Ligar os 3 tópicos de notificação no painel do ML
`items`, `orders_v2`, `shipments` estão **desmarcados de propósito** — o endpoint
`/api/publico/webhooks/mercadolivre` não existia quando a aplicação foi criada. **Ele existe desde
o M5**, então os tópicos podem ser ligados. Passo de painel, não de código.

### 4. Nenhuma tela do módulo de marketplace foi aberta em navegador
Canais, Vincular Anúncios e Fila de Expedição só passaram por teste automatizado.

### 5. Tela de Lucratividade nunca aberta em navegador
(O Relatório de Contas a Pagar foi aberto em 2026-08-26 e funciona.)

### 6. Nenhum relatório foi IMPRESSO no papel depois da correção do tema do PDF (2026-08-26)
O PDF foi conferido na tela e por análise de pixels — papel é outra coisa.

### 7. O PDF com gráfico não foi conferido com a barra visível
A cor foi medida no **mecanismo** (`fill` computado: escuro → claro), mas em aba controlada por
automação o `requestAnimationFrame` é suspenso, a animação do recharts congela e a barra sai como um
tracinho — artefato do ambiente de teste, não do uso real. Basta gerar **um** PDF de um relatório
com gráfico e olhar.

### 8. Paginação de documento A4 não testada no papel
A correção de 2026-08-22 (`imprimirDocumentoA4()`) nunca foi confirmada impressa.

### 9. Horizontal da etiqueta 34×60 não confirmada no papel
Margem 3,00 / espaço 1,70 — números derivados, não medidos numa etiqueta impressa.

### 10. Papel do driver por rolo de etiqueta
Decisão dele: trocar o papel no driver a cada rolo × construir um **agente local de impressão**.
Não existe valor único que sirva a dois rolos, e a web não tem API de DEVMODE.

### 11. Cobrança da assinatura DESLIGADA
Ele tem a credencial de teste do Mercado Pago em mãos; é só ligar (`NINER_MP_ACCESS_TOKEN` /
`NINER_MP_WEBHOOK_SECRET`).

### 12. ~15 popups de TRABALHO ainda sem ✕
Filtros do Relatório, Nova cor, Forma de Pagamento… Os ~24 popups de **confirmação** ficam de fora
de propósito. Decisão dele.

### 13. "Painel" (`/`) está dentro de "Implementações Futuras" e é tela real
Decisão dele: promover ou manter.

### 14. Devolução não estorna comissão nem taxa
Decisão de negócio.

### 15. Pedido de marketplace sem estoque não vira venda — e fica tentando
Consistente com o PDV, mas a venda não existe no ERP embora o dinheiro seja real. Manter ou abrir
exceção? Decisão dele.

### 16. Um produto só alimenta UM anúncio por canal
Índice na V066 fecha o caso de anunciar o mesmo produto duas vezes para ganhar alcance. Confirmar ou
liberar com regra de rateio.

### 17. 🔔 Estorno não revoga assinatura
Dívida **conhecida e aceita**, política dele. **Pedido explícito: avisar em toda revisão/auditoria.**

### 18. Taxa do canal — pôr o valor
A carteira do canal nasce com **0%**; o ML cobra 11–19%. Enquanto ficar zerada, a **DRE
superestima o lucro**. Parte dele: cadastrar a taxa. (A parte minha está no item 20.)

---

## 🟢 Bola minha

### 19. A exportação de XML nunca rodou com período acima de 2.000 notas
A partição é validada pela aritmética e por um teste de congelamento, nunca por um período que
realmente estoure. Depende dele usar + de mim medir.

### 20. A tela não avisa que a taxa do canal está zerada
Enquanto `taxa = 0%`, a DRE superestima o lucro e nada na tela diz isso. Aviso é meu (par do item 18).

### 21. Tela de preço manual do anúncio não existe
`anuncio.preco_manual` é respeitada pelo gatilho e pelo manipulador, mas **nada a liga**.

### 22. SVC — contingência da NF-e 55
Não implementada.

### 23. CSOSN 500 com ST retido (`cStat 938`)
Minha, mas **depende do contador**.

### 24. Efetivar Balanço e Tipo de Carteira não têm spec nenhuma
Duas telas em uso sem arquivo em `docs/telas/` (ver `docs/TELAS.md`).

### 25. `db/migration/README.md` parou na V035
As ~35 migrations seguintes nunca foram indexadas.

### 26. Itens adiados da auditoria de 2026-08-21
21+32, 25, 26+30, 27 — detalhe em `docs/PENDENCIAS-AUDITORIA-2026-08-21.md` (24 das 33 já
corrigidas em 08-22).

### 27. ⚠️ Chamada HTTP ao canal roda dentro da transação do lote do outbox
Lote de 25 × timeout de 30 s. Desenho **preexistente**; risco registrado, a medir com volume real.

---

## Backlog estrutural (do `PROGRESSO.md`, não é regressão)

- **Tela de variação/SKU (`produto_barra`)** — o domínio existe desde 2026-08-05
  (`ProdutoBarraService.obterOuCriar`); falta só a tela de listar/editar/excluir variação.
- **`uso_tenant.qtd_produtos`** — enforcement R19.
- **Catálogo `ajuda_tela` na API (R22)** — hoje o conteúdo é fallback estático dentro do `web/`;
  falta o endpoint/tabela real (spec §3.7.1).
- **Credenciais do datasource nos testes** — `TestcontainersConfiguration` conecta como
  superusuário do container, então `GRANT`/`REVOKE` e RLS ficam **invisíveis** para a suíte.
- **springdoc-openapi** — está na spec e nunca foi implementado; o contrato real vive nas seções
  "Contrato de API" de `docs/telas/*.md`.
- **Decisões de negócio em aberto:** D1 (preços), D3 (gateway), D5/D6/D8/D9/D10 —
  `docs/PLANO-DE-NEGOCIO.md`.

---

## ✅ Fechadas recentemente (para não reabrir por engano)

- **2026-08-26** — `fiscal.download` / DF22: **Exportação de XML em Lote** (`/fiscal/exportacao-xml`),
  validada com dados reais, na versão **síncrona e particionada** (o desenho assíncrono do
  `MODULOFISCAL.md` §11.2 **não** foi feito).
- **2026-08-26** — Marketplace **M1–M7**, Relatório de Contas a Pagar/Pagas, Relatórios em
  subgrupos, PDF dos 10 relatórios fora do tema escuro.
