# Módulo Fiscal — NFC-e, NF-e, Cancelamento, Correção e Guarda de XML

**Produto:** Niner (Vetor Sistemas) · **Banco:** `niner_db` · **Versão do documento:** 2.1
**Data:** 2026-08-16 (v2.1: escopo do v1 fechado pelo dono do produto — ver §1.1 e §4)

**Relação com os outros documentos:** este é o **documento de módulo** do fiscal. Complementa
(não substitui) o `spec-driven-erp-varejo.md` (v2.0) e o `docs/PLANO-DE-NEGOCIO.md`. Cada tela
ganha arquivo próprio em `docs/telas/` no padrão já usado pelas ~40 telas existentes, e cada
decisão de arquitetura vira ADR no formato §6 da spec.

**Convenções deste documento:**

- ✅ = decidido · 🔴 = pendente (dono do produto) · 🆕 = novo na v2.0
- ⚠️ = fato coletado em fonte **secundária**. Fiscal é área onde "quase certo" vira multa para o
  lojista: todo ⚠️ tem que ser batido contra o MOC / Portal Nacional NF-e / XSD oficial **antes de
  virar código**, e a Fase F0 existe em boa parte para isso.

---

## 0. Sumário das decisões

| # | Decisão | Escolha |
|---|---|---|
| DF1 | Como falar com a SEFAZ | ✅ **Emissão própria**, direto na SEFAZ. Sem provedor terceiro |
| DF2 | Ordem de entrega | ✅ **NFC-e primeiro** (amarrada ao PDV), NF-e na sequência |
| DF3 | Onde mora a inteligência tributária | ✅ **No Niner** — perfil fiscal por produto/tenant, XML sai pronto |
| DF4 | Reforma tributária (IBS/CBS/IS) | ✅ **Desde o v1, para TODOS os regimes** — motor e schema prontos mesmo para Simples/MEI; ver §8.5 |
| DF5 | Certificado digital | ✅ **A1 (.pfx) por upload**, cifrado **no banco** (`fiscal_certificado.arquivo_cifrado`) — não em bucket, DF21 revisada em 2026-08-17 —, senha cifrada com chave fora do banco |
| DF6 | UF piloto | ✅ **Paraná** — autorizador próprio (`nfce.sefa.pr.gov.br`), não SVRS; ver §9.4 |
| DF7 | Base técnica em Java | ✅ **Híbrido**: lib open-source para XSD/assinatura/QR + `HttpClient` do JDK no transporte. PoC na F0 é o gate |
| DF8 | Emissão no PDV | ✅ **Síncrona na efetivação** + **contingência offline** |
| DF10 | Cancelamento | ✅ **Uma operação só** — cancelar a venda cancela a nota, com trava de prazo legal |
| DF11 | Arquivamento | ✅ XML das notas próprias + eventos · ZIP por período · DANFCE/DANFE em PDF junto |
| DF17 | QR Code | ✅ **v3.00 (NT 2025.001) desde o início** — sem o modelo v2.0 legado; ver §9.5 |
| **DF35** 🆕 | **Escopo do v1 fiscal** | ✅ **NFC-e ao consumidor final (identificado ou não) + NF-e de devolução de venda**, com o ciclo de vida completo da nota: cancelamento, inutilização, contingência offline, arquivamento e download. **Todo o resto fica documentado como implementação futura** (§4.2) |
| DF9 | Escopo da NF-e (modelo 55) | ✅ Reduzido pela DF35: **só a nota de devolução de venda** (entrada, `finNFe=4`). Venda a CNPJ, transferência e devolução ao fornecedor ⇒ futuras |
| DF13 | Venda a CNPJ contribuinte no PDV | ✅ Revisada pela DF35: sem NF-e de venda no v1, o PDV **emite NFC-e para consumidor final, inclusive com CNPJ** (`indIEDest` 2 ou 9), e **recusa a emissão** quando o cliente é contribuinte de ICMS (`indIEDest = 1`), com mensagem que ensina a saída. A venda em si nunca é bloqueada (F3) — ver §9.6 |
| DF26 | Venda não presencial (marketplace / e-commerce) | ✅ Futura (§4.2). Os indicadores `indPres`/`indFinal`/`idDest` nascem no `documento_fiscal` desde o v1 porque a NFC-e já os preenche; o resto (transporte, `infIntermed`) vem junto com `canais/` |
| DF27 | Correção de nota autorizada | ✅ Futura. ⚠️ **CC-e não existe para NFC-e** — é evento de NF-e 55, então nunca teria entrado num v1 de NFC-e de qualquer forma (§4.2) |
| ~~DF36~~ | ~~Alíquota de PIS/COFINS: do perfil do produto ou do regime da empresa?~~ | ⛔ **SUPERADA pela DF37 (2026-08-17).** A pergunta só existia porque o CRT 3 cobre Lucro Presumido e Real ao mesmo tempo. Com os dois regimes fora do produto, não há mais alíquota ad-valorem de PIS/COFINS a decidir: é sempre CST 99, dentro do DAS. `regime_apuracao` foi removido do schema |
| **DF37** 🆕 | **Quais regimes tributários o Niner atende?** | ✅ **Só MEI e Simples Nacional** — CRT 1, 2 e 4. Lucro Real e Lucro Presumido **não são atendidos**, e a recusa é de **escopo de produto**, não de funcionalidade faltando. Consequências em cascata: sem `regime_apuracao`, sem PIS/COFINS ad-valorem, sem IPI, sem `equiparado_industrial`. CST de ICMS sobrevive só para o CRT 2. Ver §7.1, §8.2 e §8.3 |

Decisões ainda em aberto: **Anexo A** (DF12, DF16, DF18–DF24, DF29–DF34).

---

## 1. Histórico do estudo

### 1.1 O escopo do v1, fechado em 2026-08-16 (DF35)

O estudo nasceu cobrindo tudo que um ERP de varejo pode precisar emitir. O dono do produto fechou
o escopo do **primeiro corte** em duas emissões:

1. **NFC-e (modelo 65)** ao consumidor final, **identificado ou não**, a partir do PDV.
2. **NF-e (modelo 55) de devolução de venda** — nota de **entrada**, emitida pela própria loja,
   referenciando a NFC-e original.

Mais o **ciclo de vida completo** dessas duas notas, que não é escopo separado e sim parte da mesma
entrega: **cancelamento** (30 minutos na NFC-e), **inutilização** de numeração, **contingência
offline**, **arquivamento** e **download do XML** para a contabilidade. Nota que não se cancela,
não se arquiva e para de sair quando a internet cai não é um módulo fiscal utilizável.

**Todo o resto continua neste documento**, catalogado em §4.2 com o que já fica pronto e o que
falta — não foi apagado nem reduzido a uma lista de títulos. A pesquisa de transferência (ADC 49),
marketplace (`infIntermed`), CC-e e consignação está preservada porque refazê-la depois custa mais
do que mantê-la escrita.

**Duas consequências que o lojista vai sentir, e que o sistema tem que explicar em vez de
disfarçar** (F11):

- **Venda a contribuinte de ICMS não terá documento fiscal no v1.** NFC-e não serve para quem
  compra para revender, e a NF-e de venda ficou para depois. O PDV recusa a emissão e diz o porquê
  — a venda em si continua sendo gravada (F3).
- **Devolução depois de 30 minutos** passa a ter caminho (a NF-e de entrada), mas **cancelamento
  fora do prazo continua sem volta** — e, no PR, cancelamento extemporâneo não existe (§10.1).

### 1.2 O que mudou da v1.1 para a v2.0

A v1.1 era um estudo de viabilidade escrito **de fora para dentro** (o que a legislação exige).
A v2.0 é o estudo escrito **de dentro para fora**: cada afirmação foi conferida contra o schema
real (`db/migration/V001`–`V033`) e contra as telas que já existem. O que mudou:

1. **§4 virou o catálogo completo de documentos** — a lista de 4 operações de NF-e da v1.1 (e do
   pedido original) deixava **7 modalidades de fora**, três delas praticamente obrigatórias para
   quem já usa o Niner hoje. Ver §4.3.
2. **§6 é nova: as lacunas do schema atual.** O que hoje impede a emissão, tabela por tabela,
   coluna por coluna. A mais grave: **`produto` não tem nenhuma coluna de unidade comercial** —
   `uCom` e `uTrib` são obrigatórios no XML, e não existe de onde tirá-los.
3. **§7 responde ao item 4.3 do pedido** ("arquivar no banco os tributos dos produtos") com **três
   camadas explícitas** — cadastro, referência versionada e memória de cálculo — em vez de tratar
   tudo como "cadastro fiscal".
4. **A transferência entre empresas ganhou tratamento próprio (§8.4).** A v1.1 tratava NF-e de
   transferência como "mais uma operação". Depois da **ADC 49/STF** e da **LC 204/2023**, ICMS
   **não incide** na transferência entre estabelecimentos do mesmo titular, e o que se transfere é
   **crédito** — regra que muda o XML inteiro dessa nota. Era o maior erro conceitual do documento
   anterior.
5. **A forma de pagamento virou item de schema (§6.4).** `detPag` é obrigatório na NFC-e e a
   `tipo_carteira` do Niner não tem `tPag`, bandeira nem CNPJ de credenciadora. Sem isso, nenhuma
   NFC-e é autorizada.
6. **A guarda passou a incluir o XML de entrada (§11.1).** `entrada_xml.xml_bruto` já guarda o XML
   das notas dos fornecedores desde 2026-08-11 — o contador precisa das entradas tanto quanto das
   saídas, e o custo de incluí-las no ZIP é quase zero.
7. **Nasceu a Rotina de Conformidade Fiscal (§12).** Ligar o fiscal num tenant com 10.000 produtos
   sem NCM é o cenário real de onboarding; sem uma tela que liste o que falta, o lojista descobre
   produto a produto, no caixa, com cliente na frente.
8. **Correções factuais** — a frase de homologação vai no **nome do destinatário**, não na razão
   social do emitente; `cEAN` de produto sem GTIN real é literalmente `SEM GTIN` e **nunca** o
   EAN-13 interno gerado por `gerar_ean13_interno()`; devolução de venda é `finNFe=4`, não nota
   normal.

---

## 2. Por que este módulo muda o produto

**2.1 — O ICP do plano de negócio muda.** O `PLANO-DE-NEGOCIO.md` §3 diz, hoje, que está **fora do
ICP** "quem exige emissão fiscal nativa", e o Anexo A classifica NF-e/NFC-e como **non-goal do
produto**. Emitir NFC-e no PDV inverte isso: o Niner passa a ser um ERP fiscal, o que amplia muito
o mercado e, ao mesmo tempo, **eleva o custo de suporte, o risco jurídico e o piso de qualidade**
— nota rejeitada é loja parada. Os dois documentos precisam ser atualizados na mesma leva em que
este for aprovado. 🔴 **DF12 — fiscal entra em qual plano?** Recomendação: add-on cobrado à parte
ou exclusivo do Profissional/Escala, nunca no Essencial.

**2.2 — Emissão própria é compromisso permanente, não projeto.** A SEFAZ publica Notas Técnicas
continuamente, cada uma com data de obrigatoriedade. Quem emite direto assume manter o layout em
dia **para sempre**, sob pena de **todos os tenants pararem de vender no mesmo dia**. Isso vira
rotina de operação da Vetor (§16.4), não boa vontade. É o custo real que a DF1 comprou em troca de
não pagar por nota.

**2.3 — O relógio já está correndo.** Desde **03/08/2026** os grupos IBS/CBS/IS da **NT
2025.002-RTC** são obrigatórios em produção para **regime regular (CRT 3)**; nota sem os grupos é
rejeitada. Simples e MEI (CRT 1, 2 e 4) estão dispensados até **04/01/2027** ⚠️ — cerca de cinco
meses. Ver §8.5.

> **A DF37 mudou o peso desta data.** Com o produto atendendo só MEI e Simples, **04/01/2027 é o
> prazo de 100% da base de clientes** — não de uma minoria. Não sobra nenhuma empresa no prazo de
> 03/08/2026, mas também não sobra ninguém com folga: quando a data virar, vira para todo mundo ao
> mesmo tempo. É o argumento mais forte para o IBS/CBS já sair calculado no v1 (DF4).

**2.4 — O fiscal é o primeiro módulo que pode parar a loja.** Todos os módulos até aqui falham para
dentro: um relatório errado se refaz, uma baixa errada se estorna. Uma NFC-e que não autoriza para
o cliente na frente do caixa. É a razão de F2 (nada de rede dentro de transação), F3 (a venda nunca
some porque a nota falhou) e da contingência offline serem inegociáveis, e não refinamentos de
segunda fase.

---

## 3. Princípios do módulo fiscal (F1–F12)

No mesmo espírito da Constituição P1–P9 da spec principal. Inegociáveis; o resto é implementação.

| # | Princípio | O que significa na prática |
|---|---|---|
| **F1** | **O documento fiscal é consequência, nunca causa** | O fiscal nunca cria movimento de estoque, caixa ou financeiro. Ele lê uma operação **já registrada** e a representa perante a SEFAZ. Toda tela fiscal fala de um registro que já existe |
| **F2** | **Nada de I/O de rede dentro de transação de banco** | Autorização leva de 1 a 30 segundos e pode não responder. A transação que grava a venda **fecha antes** da transmissão. Segurar conexão esperando SEFAZ derruba o PDV inteiro sob carga |
| **F3** | **A venda nunca some porque a nota falhou** | Rejeição, timeout ou SEFAZ fora do ar **nunca** desfazem a venda. O documento fica em estado explícito de erro, visível e reprocessável |
| **F4** | **Numeração é recurso crítico** | Sequencial por (empresa, modelo, série), **sem buraco**, alocado sob trava e nunca reutilizado. Buraco só se resolve por **inutilização** formal |
| **F5** | **Idempotência antes de retransmitir** | Antes de reemitir documento em estado duvidoso (timeout), **consulta-se a chave na SEFAZ**. Emitir duas notas para a mesma venda é problema fiscal do lojista, não bug de UX |
| **F6** | **O XML autorizado é imutável** | Autorizado, o `nfeProc` vai para o bucket e **nunca é reescrito**. Correção por evento (cancelamento, CC-e) ou documento novo. Guarda de 5 anos |
| **F7** | **Certificado é segredo de terceiro** | O `.pfx` do lojista nunca trafega em log, nunca vai para o banco em claro, nunca aparece em resposta de API, e o acesso é auditado. Vazamento = alguém emitindo nota no CNPJ do lojista |
| **F8** | **Isolamento de tenant vale em dobro aqui** | `id_tenant` **explícito** em toda query, além do RLS (`docs/infra/isolamento-tenant-rls.md`). Vazamento cruzado no fiscal é o certificado, a numeração e a nota de outro CNPJ |
| **F9** | **Todo cálculo é auditável e reproduzível** | Para cada item fica gravado **de onde veio cada número**: perfil, CST/CSOSN, alíquota, versão de tabela, versão do motor. O lojista precisa explicar a nota anos depois |
| **F10** | **A UF é dado, não código** | Autorizador, endpoint, prazo de cancelamento, alíquota interna, FCP, particularidade de validação: tudo em **tabela**, nunca em `if`. É o que permite a UF piloto virar Brasil sem reescrever |
| **F11** 🆕 | **Bloqueio preventivo, nunca rejeição no caixa** | Produto sem NCM/unidade/perfil, cliente sem município IBGE, empresa sem certificado válido: o sistema **impede antes**, na tela de cadastro e na Conformidade Fiscal (§12) — não deixa chegar no caixa e virar rejeição com cliente esperando |
| **F12** 🆕 | **Fiscal desligado não muda o ERP** | Tenant sem fiscal habilitado opera exatamente como hoje. O módulo é aditivo: nenhuma coluna nova é `NOT NULL` sobre dado existente, nenhuma tela existente passa a exigir campo fiscal enquanto `emite_nfce`/`emite_nfe` estiverem falsos |

---

## 4. Escopo

### 4.1 O v1 fiscal (DF35)

Os CFOP abaixo trazem o par **dentro do estado / fora do estado** e são os típicos de varejo
revendendo mercadoria de terceiros ⚠️ — a definição final é sempre do perfil fiscal (§8.2), porque
muda com ST, produto importado e benefício.

| # | Operação de negócio | Doc. | `finNFe` / `tpNF` | CFOP | Origem no Niner | Fase |
|---|---|---|---|---|---|---|
| 1 | **Venda presencial a consumidor final** — com ou sem CPF, inclusive a CNPJ não contribuinte | **NFC-e 65** | 1 / saída | 5.102 · **5.405** se ST retido | `vendas.pdv` | **F3** |
| 2 | **Devolução de venda feita pelo consumidor** | **NF-e 55 de entrada** | **4** / **entrada** | 1.202 · 1.411 se ST | `vendas.devolucao` | **F5** |
| 3 | **Cancelamento** da NFC-e ou da NF-e de devolução | Evento **110111** | — | — | `vendas.cancelamento` e `fiscal.documentos` | **F4** |
| 4 | **Inutilização** de faixa de numeração | Evento próprio | — | — | `fiscal.inutilizacao` | **F4** |
| 5 | **Contingência offline** da NFC-e | `tpEmis = 9` | — | — | `vendas.pdv` + `fiscal.contingencia` | **F3** |
| 6 | **Arquivamento e download** do XML | — | — | — | `fiscal.download` | **F4** |

**Por que a devolução é nota de *entrada*, e não de saída.** O consumidor pessoa física **não emite
nota**. Quem emite é a própria loja, como entrada (`tpNF = 0`, `finNFe = 4`), referenciando a chave
da NFC-e original em `documento_fiscal_referencia`. Isso muda o CFOP (série 1.xxx), o sentido do
estoque e a numeração ⚠️ (mesma série da saída na maioria das UFs — confirmar no MOC-PR). A tela de
Devolução de Produtos já tem tudo de que a nota precisa: desde 2026-08-11 ela restringe a produtos
efetivamente vendidos e amarra no número da venda.

**A devolução espelha a tributação da nota original.** Não se recalcula do zero: mesma base, mesma
alíquota, mesmo CST/CSOSN da NFC-e que está sendo devolvida ⚠️ — por isso a camada 3 de §7.4 (a
memória de cálculo gravada por item) não é luxo de auditoria, é **insumo direto** da devolução.

**O limite honesto do v1.** Sem NF-e de venda, **venda a contribuinte de ICMS fica sem documento
fiscal**. O PDV detecta pelo `indIEDest` do cliente e recusa a emissão com mensagem que ensina a
saída (§9.6); a venda continua sendo gravada normalmente (F3). Cabe ao lojista saber que, enquanto
a NF-e de venda não chegar, ele não atende revendedor com nota.

### 4.2 Implementações futuras

Catalogadas, não descartadas. Cada linha traz **o que já fica pronto no v1** — porque o custo de
uma operação futura não é o mesmo se o schema, o transporte e o motor já existirem.

| Operação | Doc. | O que já fica pronto no v1 | O que falta |
|---|---|---|---|
| **Venda a CNPJ contribuinte** | NF-e 55, `finNFe=1` | Motor, numeração, assinatura, transporte SEFAZ, `indIEDest` no cliente, DANFE A4 (da devolução) | Regra de CFOP interestadual, DIFAL, grupo de transporte/volumes, e o PDV passar a escolher o modelo em vez de recusar |
| **Transferência entre matriz e filiais** | NF-e 55 | Ledger de transferência, custo por item (`preco_custo`), empresas com config fiscal própria | **§8.4 inteira** — ADC 49/LC 204: ICMS não incide, transfere-se crédito, com opção anual do Convênio 109/2024. É a operação futura que mais precisa de validação com contador |
| **Devolução ao fornecedor** | NF-e 55, `finNFe=4`, saída | `entrada_xml.xml_bruto` guarda a nota de compra; `entrada_item_tributo` (DF30) guarda os tributos dela | Espelhar a tributação da nota de compra original ⚠️ (senão o fornecedor não recupera o crédito) e decidir a tela (DF14) |
| **Carta de Correção (CC-e)** | Evento 110110 | `NFeRecepcaoEvento4` e `documento_fiscal_evento` já existem desde a F4 | Tela, validação do texto e prazo de 720 h ⚠️. ⚠️ **Não se aplica à NFC-e** — só faz sentido depois da NF-e de venda |
| **Venda não presencial** (marketplace, e-commerce, delivery) | NF-e 55 | `indPres`/`indFinal`/`idDest` já gravados; motor e emissão prontos | `documento_fiscal_transporte`, `documento_fiscal_intermediador` (`infIntermed`, obrigatório desde a NT 2020.006 ⚠️), DIFAL, e o módulo `canais/`, que ainda não existe |
| **Remessa e retorno de garantia/conserto** | NF-e 55 (par) | `documento_fiscal_referencia` (o retorno referencia a remessa) | CFOP 5.915/1.916, controle do que saiu e não voltou, e uma tela de origem — hoje não existe operação de remessa no ERP |
| **Consignação** (remessa, retorno, venda) | NF-e 55 (3 documentos) | — | Fluxo inteiro: 5.917 → 5.918/1.918 → 5.114, com controle de saldo consignado. Só vale se o ICP trabalhar assim |
| **Baixa por perda, quebra, furto** | NF-e 55 | A Rotina de Contagem de Estoque **já gera o ajuste** | CFOP 5.927 e o gancho na efetivação do balanço. É o mais barato desta lista, e diferença de inventário é o que o fisco cruza |
| **Brinde / bonificação / amostra** | NF-e 55 | — | CFOP 5.910/5.911 e tributação própria de saída sem receita |
| **Retorno de mercadoria não entregue** | NF-e 55 de entrada | `documento_fiscal_referencia` | Só faz sentido quando houver entrega (venda não presencial) |
| **NF-e complementar** (preço ou ICMS a menor) | NF-e 55, `finNFe=2` | `documento_fiscal_referencia` | Regra de tributação da complementar. Rara no varejo |
| **Cancelamento por substituição** (110112) | Evento | Transporte de eventos | ⚠️ É o caminho do "fechou no cartão errado" na NFC-e (NT 2018.004) — o mais provável de ser pedido cedo pelo balcão |
| **Distribuição DF-e** | — | Parser de NF-e da Entrada por XML, já testado com notas reais | NSU, GZIP, manifestação do destinatário. **Maior valor/esforço do módulo inteiro:** mataria o upload manual de XML |
| **EPEC / SVC** | — | — | Contingência da NF-e 55. A do v1 é a offline da NFC-e, que é a que salva o PDV |
| **Papel `CONTADOR`** | — | Download em ZIP | Terceiro papel em `identidade` (hoje só ADMIN/OPERADOR, com ADMIN único e imutável por tenant) — mudança estrutural, não detalhe |

> **O que o v1 faz para não encarecer o futuro:** `tipo_operacao` nasce como **enum extensível** com
> os valores das operações futuras já previstos; `documento_fiscal_referencia` nasce completo
> (a devolução do v1 já precisa dele); `documento_fiscal` já grava `finNFe`, `tpNF`, `indPres`,
> `indFinal` e `idDest`. O que **não** nasce são tabelas que ficariam vazias —
> `documento_fiscal_transporte` e `documento_fiscal_intermediador` vêm com a fase que as usa, porque
> criar tabela nova depois não custa nada (não há dado para migrar). O caro é remontar o XML, e
> disso o v1 já se protege.

### 4.3 A análise que gerou este catálogo

Registro do que a leitura do pedido original revelou — vale como memória de por que a §4.2 tem 15
linhas e não 4.

Resposta direta ao item 4.2 do pedido — **sim, faltavam**. Em ordem de gravidade:

**(a) Venda a contribuinte de ICMS — a NFC-e simplesmente não serve.** É o buraco mais imediato: a
NFC-e é documento de **venda presencial a consumidor final**. Cliente PJ que compra para revender,
ou qualquer entrega fora da UF, exige **NF-e modelo 55**. Hoje o PDV é a única tela de venda do
Niner e não distingue os dois casos. Fechado pela **DF13**, revisada pela DF35: como a NF-e de venda
ficou para depois, o PDV **recusa a emissão** para cliente com `indIEDest = 1` em vez de emitir uma
NFC-e inválida. O campo `indicador_ie` no cadastro de cliente nasce no v1 justamente para isso — sem
ele, não há como distinguir, e o sistema emitiria errado sem saber.

**(b) Venda não presencial.** O coração da visão original do produto (marketplaces) é, do ponto de
vista fiscal, um documento **diferente**: NF-e 55 com `indPres = 2` (internet) ou `4` (entrega a
domicílio), `idDest` conforme a UF do comprador, grupo de **transporte e volumes**, e — desde a NT
2020.006 ⚠️ — o grupo **`infIntermed`** com o CNPJ do marketplace e o identificador do vendedor na
plataforma. Futura (§4.2), junto com `canais/`.

**(c) Carta de Correção (CC-e).** Erro de endereço, de natureza da operação, de dado de transporte:
tudo isso se corrige por evento 110110 dentro de 720 horas ⚠️, sem cancelar nada. Era o corretivo
que faltava — sem ele, todo erro vira cancelamento (limitado a 30 min na NFC-e / 24 h na NF-e) ou
nota de devolução. ⚠️ **Mas não se aplica à NFC-e**, então nunca entraria num v1 de NFC-e: chega
junto com a NF-e de venda (§4.2).

**(d) Devolução de venda: é nota de ENTRADA emitida pela própria loja.** A lista original dizia
"nota de devolução de uma venda que o consumidor comprou", o que está certo, mas esconde o ponto:
consumidor pessoa física **não emite nota**, então quem emite é a loja, como **entrada** (`tpNF=0`,
`finNFe=4`), referenciando a chave da nota original. Muda o CFOP (série 1.xxx/2.xxx), muda o
sentido do estoque e muda a numeração (mesma série da saída ⚠️, verificar particularidade da UF).

**(e) Baixa de estoque por perda/quebra/furto.** A Rotina de Contagem de Estoque já gera ajuste de
inventário **sem nenhum documento fiscal**. Para o fisco, mercadoria que entrou e não saiu por
venda saiu por alguma coisa — a NF-e CFOP 5.927 é essa alguma coisa.

**(f) Remessa e retorno (garantia/conserto).** Produto com defeito que vai para a assistência ou
volta para o fornecedor circula fisicamente; sem nota, é mercadoria desacompanhada.

**(g) Consignação.** Prática comum em confecção e calçado — justamente o público de um ERP com
cor/grade/tamanho. São três documentos encadeados, não um.

**(h) NF-e complementar.** Nota emitida com preço ou ICMS a menor se corrige com nota complementar
(`finNFe=2`), não com cancelamento. Rara no varejo; F6.

### 4.4 Non-goals permanentes — com data de revisão

Diferente da §4.2: aqui não é "depois", é "provavelmente nunca, salvo mudança de ICP".

> ⚠️ Convenção do projeto (memória `feedback_non_goals_apodrecem`): non-goal **não se apaga** quando
> é superado — vira `~~texto original~~ — superado em <data>: <prova em arquivo:linha>`. E "Non-goals"
> é a seção **menos confiável** de qualquer spec. Vale para este documento também.

| Item | Por que fica fora | Quando revisar |
|---|---|---|
| **NFS-e (serviço)** | Outro documento, outro layout, ~5.500 regras municipais. Não é o negócio do lojista de varejo | Só se o ICP mudar |
| **CT-e / MDF-e** | Transporte, não varejo. MDF-e só é exigido de quem faz transporte próprio interestadual ⚠️ | Se o ICP incluir frota própria |
| **SAT-CF-e (SP)** | Equipamento fiscal físico; SP tem caminho próprio | Quando SP entrar no roadmap |
| **Apuração fiscal / SPED / EFD / Bloco K** | Obrigação acessória, não emissão. Escopo do contador | Produto futuro |
| **Split payment da reforma** | Só entra na transição real (2027+) | Acompanhar NT |
| **Multi-CNPJ por tenant** | O v1 do produto é 1 CNPJ por tenant (D4); `empresa` já é multi, então o módulo é desenhado **por empresa** | Junto com a decisão D4 |
| **Exportação / Zona Franca / Suframa** | Fora do varejo de balcão. `suframa` fica como coluna, sem regra de motor | Se aparecer no ICP |

---

## 5. O encaixe no que já existe

O Niner tem quase tudo que uma nota precisa. O fiscal é **camada de tradução**, não sistema
paralelo (F1). Mapeamento conferido contra o código:

| Origem no Niner (existe hoje) | Documento gerado | Observação |
|---|---|---|
| `PdvVendaService` — efetivação de venda | NFC-e 65 (ou recusa explícita, DF13) | Split-tender e desconto rateado já estão em `produto_movimento_detalhe`; a empresa vem do claim `eid` |
| `venda_devolucao` + vale-mercadoria | NF-e 55 de **entrada** | Desde 2026-08-11 já restringe a produtos efetivamente vendidos e amarra no nº da venda — é exatamente o que a nota de devolução referencia |
| `produto_transferencia` + ledger | NF-e 55 de transferência *(futura)* | Hoje move estoque **sem documento fiscal**. Ver §8.4 (ADC 49) |
| `produto_movimento_mestre` tipo `COMPRA` | NF-e 55 de devolução ao fornecedor *(futura)* | DF14 decide a tela |
| `vendas.cancelamento` (ADMIN-only, com motivo) | Evento 110111 | A justificativa mínima de 15 caracteres é a que a tela já pede |
| `entrada_xml.xml_bruto` | (consome XML de terceiro) | Já **lê** NF-e e **guarda** o XML bruto — insumo da Distribuição DF-e (futura) e do ZIP do contador (§11) |
| `comum.armazenamento` — `ArmazenamentoPrivado` (MinIO/S3, **ADR-014**) | Guarda dos **XML autorizados** | ✅ Bucket fiscal **provisionado em 2026-08-17**: privado, Object Lock GOVERNANCE de 1825 dias, credencial da API **sem permissão de apagar**. Área `AreaPrivada.FISCAL_XML`, caminho `tenants/{id}/fiscal/{ano}/{mes}/{modelo}/{chave}.xml`. Falta o consumidor (gravar no fim da autorização). O `.pfx` **não** vai para bucket (fica cifrado no banco). O GCS do ADR-013 continua só com foto de produto |
| `comum.arquivocompartilhado` | DANFCE por WhatsApp | Cache de 24 h já existe para papeleta e crediário. **Não serve** para o ZIP fiscal (§11.2) |
| Papeleta térmica (42 col., 75 mm, Consolas negrito) | DANFCE | A calibragem de 2026-08-14 é a base do cupom fiscal — não recomeçar do zero |
| `cfg_produto_ncm` (~10.515 NCMs reais) | Cadastro fiscal do produto | Já global e sem RLS — mesmo padrão para CFOP, CEST, CST e cClassTrib |
| `cfg_geral` (singleton por tenant) | Parâmetros fiscais | Padrão de tela singleton resolvido; mas config fiscal é **por empresa**, não por tenant (§7.1) |
| `lib/rotinaCritica.tsx` (`web/`) | Emissão no PDV | Criado para o horário de acesso não cortar venda no meio; emitir NFC-e é rotina crítica pelo mesmo motivo |
| `GaugeProgresso` + polling (Importação de Dados) | Download em ZIP | Padrão de progresso ao vivo já construído e testado |
| `Documentos.java` — CPF/CNPJ **alfanumérico** | Validação do emitente/destinatário | O projeto já trata CNPJ alfanumérico (IN RFB 2.229/2024). Ver a ressalva da chave de acesso em §9.3 |

---

## 6. Lacunas do schema atual 🆕

O que **hoje** impede emitir uma nota. Conferido em `db/migration/V001`–`V033`. Toda alteração é
**aditiva** (F12): coluna nova nasce nullable ou com default, e a exigência é da aplicação, pela
Conformidade Fiscal (F11) — nunca `NOT NULL` retroativo sobre dado existente.

### 6.1 `empresa` (V014) — o emitente 🆕 ✅ campos da tela desde 2026-08-19

Hoje tem: razão social, fantasia, CNPJ, IE, endereço, número, complemento, bairro, cidade, estado,
CEP, telefone, e-mail. **Falta para o grupo `emit`/`enderEmit`:**

| Falta | Campo do XML | Nota |
|---|---|---|
| `codigo_municipio_ibge` | `cMun` | **Obrigatório.** 7 dígitos. Não há de onde derivar — `cidade` é texto livre |
| `codigo_uf_ibge` | `cUF` | Deriva de `uf`, mas o par (UF ↔ código) precisa virar tabela (`cfg_uf_autorizador`) |
| `cnae` | `CNAE` | Obrigatório quando há IM; recomendado sempre |
| `inscricao_municipal` | `IM` | |
| `inscricao_estadual_st` | `IEST` | Só para substituto tributário em outra UF |
| `crt` | `CRT` | **1** Simples · **2** Simples com excesso de sublimite · **4** MEI ⚠️ (CRT 4 é recente; confirmar no XSD vigente). O **3** (Regime Normal) é recusado — DF37 |
| `tipo_estabelecimento` | — | Matriz ou filial. Necessário para a transferência (§8.4) |
| `pais` / `codigo_pais` | `cPais`/`xPais` | Fixo 1058/BRASIL, mas o campo tem que existir |

Além disso: `cnpj` e `inscricao_estadual` são **nullable** e `estado` é texto livre. Para empresa
com `emite_nfce = true`, os três passam a ser exigidos e validados (F11) — CNPJ pela rotina
alfanumérica já existente, UF contra a tabela de UFs.

> **Onde isso mora:** não em `empresa`, e sim na nova `fiscal_config_empresa` (§7.1) — exceto
> `codigo_municipio_ibge`, `cnae`, `inscricao_municipal` e `tipo_estabelecimento`, que são
> **atributos da empresa**, não configuração fiscal, e vão para `empresa` mesmo. A tela de Empresa
> (`identidade.empresa`) ganha esses campos; a tela nova de Configuração Fiscal fica com o resto.

✅ **Implementado em 2026-08-19** — a lacuna foi achada pelo dono do produto ao clicar "Corrigir"
numa pendência de "Empresa sem CNPJ" na Conformidade Fiscal e não encontrar onde preencher (`empresa`
era só leitura desde sempre, apesar dos campos existirem desde `V014`). Tela nova `identidade.empresa`
(`/empresas`, **primeiro CRUD do projeto sem criar/excluir**): CNPJ (alfanumérico), Inscrição
Estadual, Inscrição Municipal, código de município IBGE (texto puro, sem lookup — não existe
tabela de referência de municípios no projeto) e CNAE, além de identificação/endereço/contato.
`tipo_estabelecimento` **não** entrou — a coluna `matriz` já existe e cobre o mesmo papel (Matriz/
Filial), mostrado somente-leitura na tela. Nada é obrigatório para salvar; quem cobra é a
Conformidade Fiscal. Testado ao vivo: pendências de "Empresa" caíram de 6 para 2. Detalhe completo:
`docs/telas/empresa.md`.

### 6.2 `produto` (V017) e `produto_barra` — o item da nota

| Falta | Campo do XML | Gravidade |
|---|---|---|
| **`unidade_comercial`** | **`uCom`** | 🔴 **Bloqueante.** Não existe **nenhuma** coluna de unidade em `produto`. A única do sistema é `produto_fornecedor.unidade_compra`, que é a unidade **do fornecedor**, não a de venda. Sem `uCom` nenhuma nota sai |
| `unidade_tributavel` + `fator_conversao_tributavel` | `uTrib`, `qTrib`, `vUnTrib` | Obrigatórios. No varejo quase sempre iguais aos comerciais — mas o campo precisa existir |
| `origem` (0–8) | `orig` do grupo ICMS | **Obrigatório em todo item.** 0 nacional, 1 importação direta, 2 mercado interno importado… Define inclusive a alíquota interestadual de 4% da Resolução SF 13/2012 ⚠️ |
| `cest` | `CEST` | Obrigatório quando o produto é de segmento sujeito a ST, **mesmo que a operação não seja ST** ⚠️ (Convênio 142/2018) |
| `id_perfil_fiscal` | — | Ponteiro para o perfil (§7.3). É o que a DF3 comprou |
| `ex_tipi` | `EXTIPI` | Só quando o NCM tem exceção. Nullable |
| `cnpj_fabricante` | `CNPJFab` | Só para item de produção em escala relevante ⚠️. Nullable |
| `codigo_beneficio` | `cBenef` | Exigido por algumas UFs ⚠️. Nullable, entra por perfil ou por produto |

**`produto.codigo_ncm` é nullable** e referencia `cfg_produto_ncm`. Continua nullable (F12), mas
produto sem NCM **não pode ser vendido com nota** — regra da Conformidade Fiscal.

**`produto_barra.ean` é nullable, e isso está certo.** O que **não** pode acontecer: mandar o
`sku` interno no `cEAN`. O `sku` é um EAN-13 gerado por `gerar_ean13_interno()` com prefixo 9,
válido em estrutura mas **não registrado na GS1** — a SEFAZ valida o GTIN contra a base oficial e
rejeita ⚠️. Produto sem GTIN real leva literalmente `SEM GTIN` em `cEAN` e `cEANTrib`.

**`cProd`** (código do produto na nota) = `produto_barra.sku`, que é único por tenant e por
variação. **`xProd`** = `produto.descricao` + cor + tamanho quando o produto usa grade real —
descrição sem a variação inviabiliza a devolução item a item.

### 6.3 `cliente` (V016) — o destinatário

| Falta | Campo do XML | Nota |
|---|---|---|
| `codigo_municipio_ibge` | `cMun` | **Obrigatório** no `enderDest` da NF-e. Mesmo problema da empresa |
| `indicador_ie` | `indIEDest` | **1** contribuinte · **2** isento de inscrição · **9** não contribuinte. É o campo que decide se a NFC-e pode ser emitida (DF13). **Sem ele o sistema emite errado sem saber** |
| `codigo_pais` / `pais` | `cPais`/`xPais` | |
| endereço de **entrega** | `entrega` | *Futuro* — só quando houver venda não presencial |
| `telefone` no formato do XML | `fone` | Existe, mas só dígitos e 6–14 posições ⚠️ |

`cpf_cnpj` nullable está correto: NFC-e sem identificação do consumidor omite o grupo `dest`
inteiro. Quando informado, a validação alfanumérica de CNPJ já existente vale (`Documentos.java`).

⚠️ Alguns estados exigem identificação acima de um valor. **Não foi possível confirmar a régua do
PR** — item de F0. O PDV já precisa facilitar a captura do CPF pelo programa Nota Paraná.

### 6.4 `tipo_carteira` (V025) — a forma de pagamento 🆕 ✅ campo da tela desde 2026-08-18

`detPag` é **obrigatório na NFC-e** e a `tipo_carteira` precisava de:

| Campo | Campo do XML | Nota |
|---|---|---|
| `codigo_tpag` | `tPag` | 01 dinheiro · 03 crédito · 04 débito · 05 crédito loja (o crediário do Niner) · 17 PIX · 90 sem pagamento · 99 outros ⚠️ (tabela completa na NT 2023.004 — conferir na F0) |
| `codigo_bandeira` | `tBand` | 01 Visa · 02 Mastercard · 03 Amex · 06 Elo · 07 Hipercard · 99 outros ⚠️. Obrigatório junto com `tPag` 03/04 |
| `cnpj_credenciadora` | `CNPJ` do grupo `card` | Da adquirente (Cielo, Rede, Stone…) |
| `tipo_integracao` | `tpIntegra` | 1 integrado (TEF/POS) · 2 não integrado. Hoje o Niner é **2**, hardcoded no `MontadorXmlNfce` — nunca lido de `tipo_carteira`, não ganhou campo de tela por não ter uso ainda |

O mapeamento é direto: `categoria_carteira` já separa `AVISTA` / `CARTAO_DEBITO` /
`CARTAO_CREDITO` / `CREDIARIO` / `VALE_MERCADORIA`, e cada linha de `tipo_carteira` já é uma
bandeira específica.

✅ **Implementado em 2026-08-18** — a lacuna foi achada pelo dono do produto ao clicar "Corrigir"
num registro AMEX real na tela de Conformidade Fiscal e não encontrar onde preencher. Seção "Dados
Fiscais (NFC-e)" em `financeiro.tipocarteira` (`TipoCarteiraForm.tsx`): `codigoTpag`/
`codigoBandeira` são `<select>` de código fixo (nunca texto livre — evita bandeira/forma de
pagamento inventada), bandeira só aparece quando a categoria é Cartão Débito/Crédito. Todos
opcionais no CRUD (colunas sem `NOT NULL`) — quem cobra o preenchimento continua sendo a tela de
Conformidade Fiscal (§11.1 do estudo, `docs/telas/fiscal-conformidade.md`), não este cadastro.
`cnpj_credenciadora` validado com o mesmo `Documentos.valido()` de CPF/CNPJ do resto do domínio,
normalizado sem máscara no banco. Testado ao vivo: corrigir a pendência do AMEX no navegador fez a
contagem de "Formas de pagamento" cair de 9 para 8 pendências na mesma sessão.

🔴 **DF29 — vale-mercadoria é pagamento ou desconto?** O vale gerado por devolução
(`venda_devolucao`) hoje quita venda como forma de pagamento. Na nota ele pode ser **`tPag` de
crédito em loja** ⚠️ ou **desconto no item**. Tributariamente não são a mesma coisa: desconto
incondicional reduz a base de cálculo, forma de pagamento não. **Recomendação:** tratar como forma
de pagamento (o valor da mercadoria vendida não muda), e confirmar com contador no piloto.

Também precisa sair do PDV para a nota: **`vTroco`**, que a tela calcula e hoje não persiste em
lugar nenhum recuperável para o XML.

### 6.5 O que **não** falta

Vale registrar, para o esforço não ser superestimado: `venda`, `produto_movimento_mestre/detalhe`,
`produto_estoque`, `caixa_detalhe`, `contas_receber` e `fornecedor` **não precisam de coluna nova**.
O vínculo com o fiscal é sempre pelo lado do documento (`documento_fiscal.id_venda`,
`.id_movimento`), nunca por coluna nova na tabela de origem — mesmo padrão de `id_conta_pagar` em
`caixa_detalhe`, com a lição de 2026-08-14 aplicada: **vínculo sem FK exige atenção no `excluir()`**,
e por isso o documento fiscal **nunca é apagado** (F6), só muda de estado.

---

## 7. Modelo de dados (migrations V034+)

> Última migration hoje: **V033**. O fiscal começa em **V034**. Tabela de tenant carrega
> `id_tenant` + RLS `FORCE` com política por tenant, no mesmo arquivo da migration (padrão desde
> V025). Tabela de **referência nacional** é global, **sem** `id_tenant` e **sem** RLS — igual
> `cfg_produto_ncm` e `cfg_banco`. Toda tabela nova entra no guarda-corpo de RLS do fim do arquivo.

### 7.1 Configuração e credenciais

**`fiscal_config_empresa`** — uma linha por `empresa` (não por tenant: cada loja tem IE, série e
numeração próprias).

`id_fiscal_config` · `id_tenant` · `id_empresa` (único) · `crt` (CHECK 1/2/4, DF37) ·
`inscricao_estadual_st` · `suframa` · `ambiente` (`HOMOLOGACAO`/`PRODUCAO`, **por empresa**) ·
`serie_nfce` · `serie_nfe` · `csc_id` · `csc_token_cifrado` · `emite_nfce` · `emite_nfe` ·
`contingencia_ativa` · `contingencia_desde` · `contingencia_justificativa` ·
`versao_tabela_ibpt` · `opcao_transferencia_tributada` (§8.4) ·
`criado_em` · `atualizado_em`.

**`fiscal_certificado`** — o A1 do lojista.

`id_certificado` · `id_tenant` · `id_empresa` · `arquivo_cifrado` (o `.pfx` inteiro, **cifrado**, no próprio banco — DF21 revisada em 2026-08-17) ·
`senha_cifrada` (AES-256-GCM local, `comum.seguranca.SegredoCifrador`, chave fora do banco —
não é referência a Secret Manager externo, que o projeto não tem) · `cnpj_titular` ·
`razao_social_titular` (extraídos do certificado e conferidos contra a empresa) · `valido_de` ·
`valido_ate` · `impressao_digital` (SHA-256, auditoria e deduplicação) · `ativo` (histórico
preservado; **nunca deletar certificado antigo**).

> **Regra F7:** nenhum endpoint devolve o `.pfx` nem a senha. Nem para ADMIN. O upload é
> *write-only* da perspectiva da API. Todo uso grava linha em `fiscal_certificado_uso_log`
> (quem, quando, para qual documento).

### 7.2 O documento fiscal

**`documento_fiscal`** — mestre, serve NFC-e e NF-e.

| Coluna | Nota |
|---|---|
| `id_documento_fiscal` · `id_tenant` · `id_empresa` | |
| `modelo` | 55 ou 65 |
| `serie` · `numero` | alocados sob trava (§9.2) |
| `chave_acesso` char(44) | única por tenant; nula até a montagem |
| `codigo_numerico` (`cNF`) · `digito_verificador` | `cNF` aleatório de 8 dígitos, **diferente** do `nNF` ⚠️ |
| `tipo_operacao` | enum: `VENDA_CONSUMIDOR`, `VENDA_CONTRIBUINTE`, `VENDA_NAO_PRESENCIAL`, `DEVOLUCAO_VENDA`, `DEVOLUCAO_FORNECEDOR`, `TRANSFERENCIA`, `REMESSA_CONSERTO`, `RETORNO_CONSERTO`, `BAIXA_PERDA`, `BONIFICACAO`, `COMPLEMENTAR` — extensível (§4.1) |
| `finalidade` (`finNFe`) · `tipo_nf` (`tpNF`) | 1 normal · 2 complementar · 3 ajuste · 4 devolução / 0 entrada · 1 saída |
| `situacao` | máquina de estados do §9.1 |
| `tipo_emissao` (`tpEmis`) | 1 normal · 9 contingência offline NFC-e |
| `ambiente` | **congelado** no momento da emissão |
| `indicador_presenca` (`indPres`) · `indicador_final` (`indFinal`) · `indicador_destino` (`idDest`) | 🆕 nascem já preenchidos, mesmo no v1 só de loja física (DF26) |
| `id_venda` · `id_movimento` · `id_devolucao` | **liga à operação de origem (F1)**, nullable, um deles preenchido |
| `id_cliente` · `id_fornecedor` | nullable |
| `data_emissao` · `data_autorizacao` | |
| `protocolo` · `status_sefaz` · `motivo_sefaz` | resposta crua da SEFAZ, **sempre** gravada |
| `valor_produtos` · `valor_desconto` · `valor_frete` · `valor_seguro` · `valor_outros` · `valor_total` | |
| `valor_icms` · `valor_icms_st` · `valor_fcp` · `valor_pis` · `valor_cofins` · `valor_ipi` | |
| `valor_ibs_uf` · `valor_ibs_mun` · `valor_cbs` · `valor_is` | reforma (§8.5) |
| `valor_total_tributos` (`vTotTrib`) | Lei 12.741 (§8.6) |
| `xml_objeto_bucket` · `xml_hash` | ponteiro imutável (F6) |
| `tentativas` · `ultima_tentativa_em` · `ultimo_erro` | reprocessamento |
| `criado_em` · `atualizado_em` · `id_usuario` | padrão de auditoria do projeto |

**`documento_fiscal_item`** — o item **e a memória de cálculo** (F9). Detalhado em §7.4, camada 3.

**`documento_fiscal_pagamento`** 🆕 — `detPag` por linha: `tpag`, `valor`, `tband`,
`cnpj_credenciadora`, `codigo_autorizacao`, `tipo_integracao`, `id_carteira` (de onde veio), mais
`valor_troco` no mestre. Sem esta tabela, reprocessar uma NFC-e depois de o caixa ser fechado
exigiria remontar o pagamento a partir de `caixa_detalhe` — e a nota tem que ser reproduzível
byte a byte.

**`documento_fiscal_evento`** — cancelamento, CC-e, inutilização, contingência: `tp_evento`
(110111 cancelamento · 110110 CC-e ⚠️), `sequencia`, `justificativa` (mín. 15 caracteres),
`protocolo`, `status_sefaz`, `motivo_sefaz`, `xml_objeto_bucket`, `id_usuario`, `criado_em`.

**`documento_fiscal_referencia`** — chave da nota referenciada (devolução → original; complementar
→ original; retorno → remessa). A NT 2025.002 introduziu regras próprias de referenciamento ⚠️,
obrigatórias desde 09/2026.

**`documento_fiscal_transporte`** e **`documento_fiscal_intermediador`** — *fora do v1 (DF35)*.
Transporte (`modFrete`, transportadora, placa/UF/RNTC, volumes) e `infIntermed` (CNPJ do
marketplace + identificador do vendedor) só existem quando houver mercadoria circulando por
terceiro ou venda por plataforma. Ficam registrados aqui porque criar tabela vazia agora não
adianta nada — não há dado para migrar depois —, mas os campos que **o documento** carrega
(`indPres`, `indFinal`, `idDest`, `finalidade`, `tipo_nf`) nascem no v1, porque a NFC-e já os
preenche e é o XML que sai caro de remontar. `produto.peso_bruto`/`peso_liquido` já existem para
quando a hora chegar.

**`fiscal_numeracao`** — sequência por `(id_empresa, modelo, serie)`, com `proximo_numero` e trava
(§9.2).

**`fiscal_inutilizacao`** — faixa `numero_inicial`–`numero_final`, ano, justificativa, protocolo,
XML.

### 7.3 Cadastro tributário: o perfil fiscal

**`cfg_perfil_fiscal`** (por tenant) — o coração da DF3. Em vez de espalhar CST/CFOP por coluna de
produto, o produto aponta para um **perfil** reutilizável ("Revenda tributada normal", "Revenda com
ST retido", "Isento"). Um perfil, centenas de produtos, uma correção só.

**`cfg_perfil_fiscal_regra`** — a regra dentro do perfil, resolvida por contexto:

| Dimensão | Valores |
|---|---|
| `crt` do emitente | 1 / 2 / 4 (CHECK; o 3 não existe no produto — DF37) |
| `uf_destino` | `*` (qualquer) ou UF específica |
| `tipo_destinatario` | consumidor final · contribuinte · não contribuinte |
| `tipo_operacao` | venda · devolução · transferência · remessa · bonificação |
| **Saída** | `cfop`, `cst_icms`/`csosn`, `aliquota_icms`, `perc_reducao_bc`, `mva_st`, `aliquota_fcp`, `cst_pis`, `aliquota_pis`, `cst_cofins`, `aliquota_cofins`, `cst_ibscbs`, `cclasstrib`, `codigo_beneficio` |

Resolução: a regra mais específica ganha (UF exata > `*`). **Sem regra que case → erro explícito,
nunca chute** (F11 + §8.1).

> **DF37 — o que a regra ainda decide, e o que ela nunca decidiu.** Com Lucro Real e Presumido fora
> do produto, `aliquota_pis`/`aliquota_cofins` deixaram de ser "override de um valor derivado do
> regime" e passaram a ser simplesmente **a alíquota dos CST de tratamento próprio** (04 monofásico,
> 06 alíquota zero) que o optante do Simples segrega da receita — cesta básica, medicamento,
> autopeça, bebida. No caso normal, CST 99, tudo é zero: o tributo está dentro do DAS. O CST **01**
> (saída tributada normal) é **recusado pelo motor**, porque só existe nos regimes que o produto não
> atende — aceitá-lo faria a nota destacar PIS/COFINS por cima do DAS, cobrando duas vezes.
>
> Sumiram daqui: `cst_ipi`/`aliquota_ipi` (optante do Simples recolhe IPI dentro do DAS e não
> destaca na saída, LC 123/2006 art. 13, II).
>
> Sobreviveu por um motivo específico: **`cst_icms` só vale para o CRT 2**, com CHECK no banco
> (`crt = 2 OR cst_icms IS NULL`). A empresa com excesso de sublimite recolhe ICMS **fora** do
> Simples, e se ela emite com CSOSN ou com CST é divergência de mercado ainda em aberto (§8.2). O
> ERP não chuta por ela — quem decide é o contador, configurando o perfil.

### 7.4 As três camadas de tributo 🆕 — resposta ao item 4.3

"Arquivar no banco as informações sobre tributos dos produtos" são, na verdade, **três coisas
diferentes**, com donos e ciclos de vida diferentes. Confundi-las é o erro clássico de módulo
fiscal — e é o que faz uma nota de 2026 ficar impossível de reproduzir em 2029.

**Camada 1 — o que o produto É (cadastro, muda raramente).**
Colunas fiscais em `produto` (§6.2) + `id_perfil_fiscal` + os perfis de §7.3. É dado do lojista,
editável, versionado por `atualizado_em`. Responde: *"como este produto é tributado?"*

**Camada 2 — as tabelas de referência nacionais (versionadas, globais, sem RLS).**
Iguais para todos os tenants, mantidas por script como `cfg_produto_ncm` já é:

| Tabela | Conteúdo | Fonte |
|---|---|---|
| `cfg_cfop` | código, descrição, sentido (entrada/saída), dentro/fora do estado | Convênio SINIEF s/nº |
| `cfg_cest` | código CEST × NCM × segmento | Convênio ICMS 142/2018 |
| `cfg_cst_icms` · `cfg_csosn` | código, descrição, campos obrigatórios e **proibidos** por código | MOC |
| `cfg_cst_ibscbs` · `cfg_cclasstrib` | CST de 3 dígitos e classificação de 6 dígitos (~173 códigos) | IT 2025.002 (RFB/CGIBS) ⚠️ |
| `cfg_uf_autorizador` | UF × modelo × ambiente: URLs dos 6 serviços, autorizador, prazo de cancelamento, prazo pós-contingência, alíquota interna, FCP, exige `cBenef` | Portais estaduais (F10) |
| `cfg_ibpt` | **NCM × UF × vigência** → alíquota nacional federal, importado federal, estadual, municipal | Tabela IBPT (DF16) |
| `cfg_st_mva` | NCM × UF origem × UF destino × MVA/pauta | Protocolos/Convênios ⚠️ |

> ⚠️ **`cfg_produto_ncm.aliquota_ibpt` não serve.** É uma coluna `numeric(10,2)` única, hoje toda
> zerada, e o IBPT tem **quatro** alíquotas por NCM **por UF** e **por vigência**. A coluna fica
> (não quebra nada), mas o `vTotTrib` sai de `cfg_ibpt`.

**Camada 3 — o que foi calculado naquela nota (imutável, F9).**
`documento_fiscal_item` grava, além dos campos do XML (`nItem`, `cProd`, `xProd`, `NCM`, `CEST`,
`CFOP`, `uCom`, `qCom`, `vUnCom`, `vProd`, `cEAN`, `cEANTrib`, `uTrib`, `qTrib`, `vDesc`, `orig`):

- **ICMS:** `cst_icms` **ou** `csosn`, `base_calculo`, `aliquota`, `valor`, `perc_reducao_bc`,
  `base_st`, `mva_st`, `valor_st`, `base_st_retido` (`vBCSTRet`), `icms_st_retido` (`vICMSSTRet`),
  `perc_st` (`pST`), `aliquota_fcp`, `valor_fcp`, `pcred_sn`, `valor_cred_icms_sn`
- **PIS / COFINS / IPI:** CST, base, alíquota, valor de cada
- **IBS/CBS/IS:** `cst_ibscbs`, `cclasstrib`, `base_ibscbs`, `aliq_ibs_uf`, `valor_ibs_uf`,
  `aliq_ibs_mun`, `valor_ibs_mun`, `aliq_cbs`, `valor_cbs`, `valor_is`
- **Rastro (F9):** `id_perfil_fiscal_aplicado`, `id_regra_aplicada`, `versao_motor`,
  `versao_tabela_ibpt`, `versao_tabela_cclasstrib`, `valor_total_tributos_item`

A camada 3 **nunca** é recalculada. Perfil corrigido em 2027 não muda nota de 2026 — é isso que
permite responder ao fiscal *"por que esta nota saiu assim"* anos depois.

**Camada 3-bis — os tributos que chegam nas notas de ENTRADA** 🆕. A Entrada de Produtos por XML já
lê ICMS-ST, IPI e frete do XML do fornecedor e (com `cfg_rateia_frete_entrada`) rateia no custo —
mas **não guarda nada disso por item**. Uma tabela `entrada_item_tributo` (espelhando os campos da
camada 3, ligada a `produto_movimento_detalhe`) entrega de graça: conferência de ST recolhido, custo de reposição correto e a base da devolução ao
fornecedor — que precisa **espelhar a tributação da nota de compra original** para o fornecedor
recuperar o crédito ⚠️. 🔴 **DF30 — entra no v1?** Recomendação: **sim, na F1**, porque o dado já
passa pelo parser e descartá-lo agora significa reimportar XML depois.

---

## 8. Motor tributário

Serviço puro, **sem I/O**, testável isoladamente:

```
TributacaoResultado calcular(OperacaoFiscal operacao, ContextoFiscalEmpresa contexto)
```

`OperacaoFiscal` = itens (produto, quantidade, valor, desconto rateado), destinatário (UF,
contribuinte ou não, consumidor final ou não), tipo de operação, data.
`ContextoFiscalEmpresa` = **CRT e UF do emitente, só isso** — a DF37 tirou o regime de apuração
(deixou de existir) e o `equiparado_industrial` (deixou de ter caso).

> **O que a DF37 deixou de fora do motor.** Depois que Lucro Real e Presumido saíram do produto,
> **PIS/COFINS ad-valorem e IPI deixaram de existir**: os dois estão dentro do DAS (LC 123/2006,
> art. 13). O que o motor realmente calcula é **ICMS** (só quando o CSOSN/CST destaca) e
> **IBS/CBS**. Isso é bom para o risco — menos superfície de erro — e ruim para a intuição, porque
> um motor que devolve zero em quase tudo parece quebrado. Por isso a massa de teste checa os zeros
> explicitamente, e o motor **recusa** os CST que só fazem sentido nos regimes que saíram.

### 8.1 Ordem de cálculo

1. Resolver o **perfil fiscal** do produto → regra que casa com (CRT × UF destino × tipo de
   destinatário × tipo de operação). **Sem regra que case → erro explícito, nunca chute.**
2. Definir **CFOP** (1º dígito por destino geográfico, 2º pela natureza da operação).
3. **ICMS** — por CSOSN (CRT 1/2/4) ou, só no CRT 2, por CST; com redução de base, ST, FCP e DIFAL
   quando couber.
4. **PIS/COFINS** — CST 99 zerado no caso normal; alíquota da regra nos CST de tratamento próprio
   (§8.3).
5. **IBS/CBS/IS** — §8.5.
6. **`vTotTrib`** (Lei 12.741) — §8.6.
7. Totalizar por nota, conferindo item × total. As rejeições novas batem justamente aí ⚠️ — o total
   é a **soma dos itens já arredondados**, nunca um cálculo sobre a base somada.

### 8.2 ICMS por regime

**Simples Nacional (CRT 1/2/4) — grupo `ICMSSN`, campo `CSOSN`** ⚠️ (a aplicabilidade do grupo ao
CRT 2, empresa com excesso de sublimite, é ponto de divergência de mercado — confirmar no MOC na
F0; é um caso raro no ICP, mas mal resolvido gera rejeição sistemática):

| CSOSN | Uso no varejo | Campos obrigatórios |
|---|---|---|
| **102** | Tributada sem permissão de crédito — **o caso mais comum** | nenhum valor de ICMS |
| 101 | Tributada com crédito | `pCredSN`, `vCredICMSSN` (a alíquota efetiva vem da apuração do DAS — 🔴 **DF31**: entrada manual por empresa/mês ou fora do v1?) |
| 103 / 300 / 400 | Isenta por faixa · imune · não tributada | nenhum |
| 202 | Tributada sem crédito, com ST | grupo ST completo |
| **500** | **ICMS já retido por ST** (o lojista é o substituído) — muito comum em confecção e calçado | `vBCSTRet`, `vICMSSTRet`, `pST`. **CFOP 5.405**, não 5.102 |
| 900 | Outras | depende |

**Grupo `ICMS`, campo `CST` — só CRT 2** ⚠️. A DF37 tirou o CRT 3 do produto, mas **não** apagou o
CST: a empresa do Simples com excesso de sublimite recolhe ICMS **fora** do DAS, e a divergência
acima é justamente sobre qual grupo ela usa. O banco garante o limite
(`CHECK crt = 2 OR cst_icms IS NULL`) e o motor recusa CST em CRT 1 e 4 com mensagem própria.

| CST | Uso | Campos-chave |
|---|---|---|
| **00** | Tributada integralmente | `vBC`, `pICMS`, `vICMS` |
| 20 | Redução de base | `pRedBC` |
| 40 / 41 / 50 | Isenta · não tributada · suspensão | — |
| 51 | Diferimento | `pDif`, `vICMSDif` |
| **60** | ICMS retido anteriormente por ST | `vBCSTRet`, `vICMSSTRet` — **CFOP 5.405** |
| 10 / 70 | Com ST · redução + ST | grupo ST |

> **Implementar como máquina de estados por código**, não como campos opcionais soltos: cada
> CST/CSOSN determina quais campos são obrigatórios **e quais são proibidos**. Campo a mais rejeita
> tanto quanto campo a menos. Validar **antes** de assinar e transmitir — erro pego no motor custa
> milissegundos, erro pego na SEFAZ custa a venda.

> ✅ **2026-08-19 — o motor recusa CST tributado (00/10/20/51/70/90) com alíquota zerada.** Achado
> real: o cadastro de perfil fiscal aceitava salvar essas linhas sem alíquota, e a nota saía
> autorizada com ICMS R$ 0,00, silenciosamente. **60 é a exceção** — ICMS já retido por ST não tem
> imposto novo nesta venda, alíquota 0 é o valor certo. Ver `docs/telas/fiscal-perfil.md` §2.

**FCP / FECOP.** Adicional de até 2% em operações internas a consumidor final para produtos
listados. No PR ⚠️ a alíquota modal é **19,5%** desde 18/03/2024 (Lei 21.850/2023) e há FECOP de
+2% (Lei 11.580/1996, art. 14-A). Campos próprios (`pFCP`, `vFCP`, `pFCPST`, `vFCPST`) — o motor
precisa suportar desde a F2, é a UF piloto.

### 8.3 PIS/COFINS

**Todas as empresas do produto são Simples ou MEI (DF37), e nelas o PIS/COFINS está dentro do DAS,
sem apuração separada.** O caso normal é **CST 99 com base, alíquota e valor zerados** ⚠️ —
destacar valor aqui cobraria o tributo duas vezes.

| CST na saída | Quando | Alíquota |
|---|---|---|
| **99** | O caso normal de todo Simples/MEI: o tributo está no DAS | zero, com base e valor zerados |
| 04 · 06 | Tratamento próprio que o optante **segrega da receita**: monofásico (bebida, combustível, autopeça) e alíquota zero (cesta básica, medicamento) | do **produto**, via `cfg_perfil_fiscal_regra` |
| ~~01~~ | Saída tributada normal — **recusado pelo motor** | só existe em Lucro Real/Presumido |

O CST **01** merece a recusa explícita e não o silêncio: num perfil do Simples ele destacaria
PIS/COFINS **por cima do DAS**, e a nota seria autorizada normalmente — o erro só apareceria na
contabilidade, meses depois. A mensagem do motor manda usar o 99.

> **Isto é o que sobrou da DF36.** A decisão anterior mandava a alíquota vir de
> `fiscal_config_empresa.regime_apuracao`, porque o CRT 3 cobria Presumido (0,65/3,00) e Real
> (1,65/7,60) e a regra do perfil só distinguia por CRT. Com os dois regimes fora do produto
> (DF37), a coluna foi removida e a pergunta deixou de existir. As alíquotas do perfil, que eram
> "override", voltaram a ser simplesmente a alíquota — dos CST de tratamento próprio.

**Consequência de cadastro:** os impostos **não** se separam por empresa. O produto declara *o que
ele é* (NCM, CEST, origem, unidade, perfil — igual em toda empresa) e a empresa declara *como
tributa* (CRT). Replicar cadastro fiscal por empresa multiplicaria N produtos × M empresas de linhas
para manter, sem ganho — a auditoria já está preservada pela camada 3 (§7.4), que congela o que foi
calculado em cada nota.

**IPI: fora do produto (DF37).** Varejo que compra para revender não é contribuinte, e o optante do
Simples recolhe IPI **dentro do DAS** (LC 123/2006, art. 13, II), sem destaque na saída. A DF15
("só se equiparada a industrial") ficou sem caso possível: `equiparado_industrial`, `cst_ipi`,
`aliquota_ipi` e `valor_ipi` saíram do schema e do motor. ⚠️ Isto vale para a nota **emitida**; o
IPI que a loja **paga na compra** vem no XML do fornecedor e é assunto da DF30.

### 8.4 Transferência entre estabelecimentos: ADC 49 🆕

**A regra mudou e o desenho anterior estava errado.** Desde a decisão do STF na **ADC 49** e a
**LC 204/2023**, **não há incidência de ICMS** na transferência de mercadoria entre
estabelecimentos do **mesmo titular** ⚠️. O que circula é **crédito**:

- A nota de transferência é obrigatória (a mercadoria circula fisicamente), CFOP **5.152** (mesma
  UF) ou **6.152** (interestadual).
- O crédito transferido é limitado ao valor do crédito apropriado na entrada; o valor da nota usa o
  **custo**, não o preço de venda — no Niner, `produto_movimento_detalhe.preco_custo`, que a
  Transferência já grava.
- O **Convênio ICMS 109/2024** ⚠️ (que substituiu o 178/2023) permite ao contribuinte **optar** por
  equiparar a transferência a operação tributada, por opção **anual** formalizada no livro fiscal.
  As duas hipóteses geram XML diferente → coluna `opcao_transferencia_tributada` em
  `fiscal_config_empresa`, com o ano da opção.
- Para empresa do **Simples**, que não se credita, o tratamento é outro e varia por UF ⚠️.

> **Consequência de produto:** a tela de Transferência de Produtos hoje escolhe empresa de destino
> e pronto. Com nota, ela passa a exigir que **as duas empresas tenham configuração fiscal
> completa**, e a nota sai da empresa de origem. Se as duas empresas estiverem em UFs diferentes, é
> operação **interestadual** com todas as consequências. Este é o item da §4.1 que mais precisa de
> validação com o contador do piloto antes de codificar.

### 8.5 IBS/CBS/IS — a reforma (NT 2025.002-RTC)

> ✅ **DF4:** o motor calcula e o schema carrega IBS/CBS **para todos os regimes desde o v1**,
> incluindo Simples e MEI. O que fica condicional é apenas a **transmissão** dos grupos, governada
> pela regra de validação vigente.

**Situação em 15/08/2026.** Versão vigente da NT: **1.51, de ~31/07/2026** (Ato Conjunto CGIBS/RFB
nº 1/2026), que adiou ~20 regras de validação para 01/09/2026 (homologação) e **05/10/2026
(produção)** ⚠️. O cronograma está em movimento — conferir o PDF oficial antes da F2.

| Quem | Obrigatório em produção |
|---|---|
| ~~CRT 3 (Presumido / Real)~~ | ~~03/08/2026~~ — fora do produto (DF37), fica só como referência |
| **CRT 1, 2 e 4** (Simples e MEI) | **04/01/2027** (art. 348 da LC 214/2025) ⚠️ — **é a data de 100% da base de clientes** |
| Regras de referenciamento de documentos | 01/09/2026 ⚠️ |

**O achado que sustenta o "sempre pronto":** a rejeição **1021** ("Grupo IBS/CBS informado
indevidamente", regra UB13-20) dispara pelo **CST do item**, não pelo CRT do emitente ⚠️. O gate do
motor é **por item/CST**, não um `if (crt == SIMPLES)`. **Validar a condição exata da UB13-20 no
texto oficial antes de codar — é a regra que sustenta a estratégia inteira.**

> **Depois da DF37 isso deixou de ser detalhe.** Se o gate fosse por CRT, ele desligaria o IBS/CBS
> para **a base inteira** — não sobra nenhuma empresa em regime regular no produto. E como todas
> compartilham a mesma data, não haverá um grupo de clientes servindo de cobaia antes do prazo:
> quando 04/01/2027 chegar, chega para todos ao mesmo tempo.

🔴 **Questão nova aberta pela DF37 (para a F0):** o optante do Simples pode ficar **dentro** do DAS
para IBS/CBS ou optar pelo **regime regular** (recolhendo por fora) — LC 214/2025. Isso muda o CST e
o `cClassTrib` do item, e possivelmente exige o grupo `gTribRegular`. Antes era um caso de minoria;
agora é a configuração de todo cliente. **Não implementar por dedução — confirmar no texto oficial e
no XSD.** É o candidato número 1 a virar campo em `fiscal_config_empresa`.

**Alíquotas de teste de 2026:** CBS **0,9%**; IBS **0,1% integralmente estadual** (`pIBSUF = 0,1%`,
`pIBSMun = 0%` — a divisão UF/município só começa em 2027-2028, arts. 343/344 da LC 214/2025) ⚠️.
São compensáveis com PIS/COFINS no mesmo período — **carga efetiva zero**, mas o emissor calcula e
destaca normalmente.

**Grupos do layout** ⚠️ (nomes conferidos em modelo de referência do ACBr e fontes técnicas —
**validar contra o XSD oficial, única fonte de verdade**): `IBSCBS` por item → `gIBSCBS` (ad
valorem) e `gIBSCBSMono` (monofásico, fora do varejo); `gIBSUF`, `gIBSMun`, `gCBS`, `gIS`;
`gTribRegular` (simula tributação cheia quando há benefício); `gIBSCredPres`; por item, `CST` de 3
dígitos + `cClassTrib` de 6; totais em **`IBSCBSTot`** (`vBCIBSCBS`, `vIBS`, `vCBS`, `vIS`).

> ✅ **DF32 — RESPONDIDA em 2026-08-17, por fonte primária (o próprio XSD).** IBS, CBS e IS
> **não** entram no `vNF`: o layout tem um campo **separado** para isso, irmão do `ICMSTot` dentro
> de `total`:
>
> ```xml
> <xs:element name="vNFTot" type="TDec_1302Opc" minOccurs="0">
>   <xs:documentation>Valor Total da NF considerando os impostos por fora IBS, CBS e IS</xs:documentation>
> ```
>
> Se os tributos por fora compusessem o `vNF`, esse campo não precisaria existir — a própria
> existência dele é a resposta. Consequência prática: **o `vNF` continua sendo o que o caixa
> recebe**, e a conferência com `caixa_detalhe` não muda; o `vNFTot` é informativo, para quando a
> carga deixar de ser compensada integralmente. O montador (B5) emite `vNF` sem IBS/CBS.
>
> ⚠️ O que ainda falta: `vNFTot` e o grupo de totais `IBSCBSTot` **não são emitidos pelo v1** —
> só entram quando a obrigatoriedade chegar (04/01/2027 para Simples/MEI, ou seja, 100% da base).
> O grupo `IBSCBS` **por item** já é emitido e validado contra o XSD.

**Convivência:** durante toda a transição os dois conjuntos coexistem na mesma nota — ICMS, PIS,
COFINS e IPI continuam, IBS/CBS/IS entram ao lado. O motor calcula os dois mundos.

### 8.6 Lei da Transparência (12.741/2012) — `vTotTrib`

Valor aproximado dos tributos no cupom, via **tabela IBPT** (NCM × UF × vigência). Precisa de
rotina de atualização, fallback quando o NCM não é encontrado, e a versão gravada no documento
(F9). 🔴 **DF16** — a tabela IBPT é licenciada; confirmar a forma de distribuição para um SaaS
multi-tenant (uma licença da Vetor cobrindo todos os tenants, ou cada lojista com a sua).

> **Como o B5 tratou isso (2026-08-17):** o campo `vTotTrib` é `minOccurs="0"` no XSD, então o
> montador simplesmente **não o emite** enquanto `cfg_ibpt` estiver vazia. Emitir `0.00` seria
> afirmar ao consumidor, no cupom, que a compra não tem tributo — informação errada é pior que
> ausência de informação, e a Lei 12.741 não é atendida por um zero falso de qualquer forma.
> Mesma decisão que o motor tomou no B4, agora coerente ponta a ponta.

### 8.7 DIFAL

Aplica-se em venda **interestadual a consumidor final não contribuinte** — cenário de venda não
presencial (DF26), não de NFC-e de balcão. ⚠️ Empresa do **Simples Nacional não recolhe DIFAL como
remetente** (o STF suspendeu a exigência na ADI 5464) — regra que o motor precisa conhecer, senão
calcula imposto que não é devido. Implementado na F2, exercitado só quando a NF-e de venda não
presencial entrar.

---

## 9. Emissão

### 9.1 Máquina de estados do documento

```
RASCUNHO ──► VALIDADO ──► ASSINADO ──► TRANSMITINDO ──┬─► AUTORIZADO ──► CANCELADO
    │            │                          │         │
    │            └─► REJEITADO ◄────────────┘         └─► DENEGADO (fim de linha)
    │                   │
    └───────────────────┘  (corrige e reprocessa, MESMO número — F4/F5)

CONTINGENCIA ──► TRANSMITINDO ──► AUTORIZADO   (cupom já impresso e entregue ao consumidor)
```

- **REJEITADO** mantém o número alocado. Corrige e retransmite com o **mesmo número**. Abandonado,
  o número vai para **inutilização**.
- **DENEGADO** (irregularidade cadastral do destinatário) é fim de linha: número queimado, não se
  cancela, não se reaproveita.
- **TRANSMITINDO** com timeout → **consulta a chave na SEFAZ antes de qualquer retransmissão** (F5).
  A nota pode ter sido autorizada e a resposta ter se perdido.
- **NAO_EMITIDO** 🆕 — estado terminal para a operação que o v1 reconhece mas ainda não sabe emitir
  (venda a contribuinte, §9.6). **Nenhum número é alocado**, a venda existe e o motivo fica
  gravado. Serve para o lojista enxergar na tela de Documentos Fiscais o que ficou sem nota, em vez
  de descobrir na contabilidade — e para medir se a NF-e de venda precisa ser antecipada.

### 9.2 Numeração (F4)

Alocação sob trava por `(id_empresa, modelo, serie)` — `SELECT … FOR UPDATE` em `fiscal_numeracao`
ou *advisory lock*. Transação de alocação **curta** e separada da transmissão (F2). A alocação
acontece **no momento de montar o XML**, não quando a venda começa: quanto menos tempo entre alocar
e transmitir, menos buraco para inutilizar depois.

🔴 **DF33 — série própria para contingência?** Não é exigido, mas separa a fila de contingência da
numeração normal e facilita a conferência. Recomendação: série 1 normal, série 9 contingência.

### 9.3 A chave de acesso e o CNPJ alfanumérico 🆕

A chave tem **44 dígitos numéricos**: `cUF(2) + AAMM(4) + CNPJ(14) + mod(2) + série(3) + nNF(9) +
tpEmis(1) + cNF(8) + cDV(1)` ⚠️.

> ⚠️ **Ponto de atenção que o projeto já conhece por outro caminho.** Desde a IN RFB 2.229/2024, o
> CNPJ é **alfanumérico** nas posições 1–12 (o Niner já trata isso em `Documentos.java` e
> `masks.ts`). A chave de acesso, porém, é numérica. **Como a chave acomoda um emitente com CNPJ
> alfanumérico não foi possível confirmar em fonte oficial** — CNPJs já existentes seguem
> numéricos, então o problema só aparece em empresa aberta a partir de julho/2026, que é
> exatamente o perfil de quem abre loja e contrata um ERP novo. **Item obrigatório da F0.**

### 9.4 Autorizadores e endpoints (F10)

`cfg_uf_autorizador` guarda, por UF × modelo × ambiente: URL de cada serviço (`NFeAutorizacao4`,
`NFeRetAutorizacao4`, `NFeStatusServico4`, `NFeRecepcaoEvento4`, `NFeInutilizacao4`,
`NFeConsultaProtocolo4`), autorizador, prazo de cancelamento, prazo pós-contingência, alíquota
interna, FCP e flags de particularidade.

**UF piloto: Paraná.** ✅ Confirmado no portal Sped-PR: o PR tem **autorizador próprio** para NFC-e
— **não usa o SVRS**. Produção em `https://nfce.sefa.pr.gov.br/nfce/<Serviço>`; homologação em
`https://homologacao.nfce.sefa.pr.gov.br/nfce/<Serviço>`.
Fonte: `sped.fazenda.pr.gov.br/NFCe/Pagina/Web-Services-NFC-e`.

**Particularidades do PR que entram em `cfg_uf_autorizador` e no roteiro da F1:**

- **Norma-base:** NPF 100/2014 (consolidada com a NPF 036/2015) — NFC-e só para venda presencial a
  consumidor final em território paranaense; NCM obrigatório; forma de pagamento obrigatória.
- **Credenciamento:** em **homologação é automático** (qualquer contribuinte ativo no cadastro de
  ICMS testa sem pedir nada — ótimo para a PoC da F0); em **produção** exige solicitação no Portal
  Receita/PR pelo representante legal (NPF 063/2012 + NPF 101/2014).
- **CSC:** gerado no Portal Receita/PR (DF-e → NFC-e → CSC), até 2 códigos simultâneos, produção e
  homologação separados. ⚠️ Com o QR v3.00 o CSC saiu do QR, mas não foi extinto — confirmar no
  MOC-PR se ainda é exigido no credenciamento.
- **Contingência offline:** permitida; transmissão em **até 24 horas** após cessada a falha (NPF
  100/2014, fonte oficial); cupom com "EMITIDA EM CONTINGÊNCIA".
- **Nota Paraná:** cashback estadual — CPF na nota é prática difundida; o PDV deve facilitar a
  captura (régua de obrigatoriedade por valor não confirmada ⚠️).

**Ampliação (F6):** as demais UFs entram pelo **SVRS** (`nfce.svrs.rs.gov.br/ws/…`), que cobre a
maior parte dos estados de uma vez; autorizadores próprios restantes (SP, MG, BA…) vêm depois, um
a um.

### 9.5 QR Code da NFC-e — v3.00

✅ **DF17: só a versão 3.00** (NT 2025.001), obrigatória em produção desde 01/09/2025. O modelo v2.0
(hash SHA-1 com CSC) **não será implementado**.

✅ **Estrutura confirmada no B7 contra o pattern do `leiauteNFe_v4.00.xsd`** (⚠️ o que esta seção
descrevia antes estava errado nas duas formas — codificar por ela daria rejeição por schema):

| Emissão | Formato (depois de `?p=`) |
|---|---|
| **Online** (`tpEmis` 1/3/4) | `<chave44>` \| `3` \| `<tpAmb>` |
| **Contingência** (`tpEmis` 9) | `<chave44>` \| `3` \| `<tpAmb>` \| `<dia>` \| `<vNF>` \| `<indicador>` \| `<documento>` \| `<assinatura>` |

- `<dia>` é **o dia do mês em 2 dígitos** (01–31), *não* o `dhEmi` completo — foi o erro mais
  perigoso do texto anterior.
- `<indicador>` (1 CPF · 2 CNPJ · 3 estrangeiro) e `<documento>` podem vir **vazios** (venda de
  balcão sem CPF), mas **os separadores continuam obrigatórios**.
- `<assinatura>` é **RSA-SHA1 em Base64** sobre exatamente esses parâmetros, com a chave privada do
  certificado da empresa — assinatura de **texto puro**, não XMLDSig. É ela que substitui a SEFAZ
  como prova de autenticidade enquanto a nota não foi transmitida.
- O CSC saiu da montagem no v3 (não há hash).

Independente da versão, `infNFeSupl` (`qrCode` + `urlChave`) é **obrigatório na NFC-e** e
**inexistente na NF-e**. Como `infNFeSupl` fica **fora** de `infNFe`, ele **não** é coberto pela
assinatura XMLDSig — daí o QR offline precisar de assinatura própria.

### 9.6 Fluxo síncrono no PDV (DF8 + DF13)

```
[F5 Efetivar Venda]
   │
   ├─ Cliente com indIEDest = 1 (contribuinte de ICMS)?
   │     ├─ SIM → NÃO emite. Toast explicando: "venda a contribuinte exige NF-e modelo 55,
   │     │        ainda não disponível". A VENDA É GRAVADA NORMALMENTE (F3);
   │     │        o documento fica registrado como NAO_EMITIDO com o motivo (P3)
   │     └─ NÃO → NFC-e 65 (consumidor final, com ou sem CPF, inclusive CNPJ não contribuinte)
   │
   ├─ TX1 (curta): grava venda, estoque, caixa, contas a receber   ← o que já existe hoje
   │               + cria documento_fiscal em RASCUNHO + documento_fiscal_pagamento
   ├─ COMMIT ────────────────────────────────────────────────  ◄── F2/F3: a venda já está salva
   │
   ├─ Popup pergunta se o CPF do cliente entra na nota (2026-08-19, `incluirCpf` — nunca
   │  decidido sozinho a partir do cliente vinculado à venda; ver docs/telas/papeleta-venda.md)
   ├─ Motor tributário (memória, ~ms)
   ├─ Monta XML + aloca número (TX curta) + assina (certificado do tenant)
   ├─ Transmite (timeout curto — 🔴 DF18, sugestão 10 s)
   │     ├─ AUTORIZADO   → TX2: grava protocolo, XML no bucket → imprime DANFCE
   │     ├─ REJEITADO    → Toast vermelho com o motivo; venda intacta; operador corrige
   │     └─ TIMEOUT/OFF  → CONTINGÊNCIA (§9.7)
   └─ A papeleta térmica vira DANFCE (42 colunas, calibragem de 2026-08-14)
```

O `web/` já tem o mecanismo de **rotina crítica** (`lib/rotinaCritica.tsx`, criado para o horário de
acesso não cortar uma venda no meio) — emitir NFC-e é exatamente uma rotina crítica.

**Ambiente de homologação:** ⚠️ **duas frases diferentes, cada uma no seu campo — nunca a mesma
constante para as duas** (incidente real em 2026-08-19: uma correção no `xNome` sobrescreveu por
engano o texto também usado no `xProd`, e a SEFAZ passou a rejeitar o item — venda 555, cStat 373).
`det/prod/xProd` (item 1) tem que ser literalmente `NOTA FISCAL EMITIDA EM AMBIENTE DE
HOMOLOGACAO - SEM VALOR FISCAL`; `dest/xNome` (não a razão social do emitente) tem que ser
literalmente `NF-E EMITIDA EM AMBIENTE DE HOMOLOGACAO - SEM VALOR FISCAL`. `MontadorXmlNfce` guarda
as duas em constantes separadas (`FRASE_HOMOLOGACAO` / `FRASE_HOMOLOGACAO_DESTINATARIO`) com
javadoc avisando pra nunca unificá-las de novo sem validar as duas ao vivo. A tela também precisa
de aviso visual permanente — nota de homologação não vale nada e já causou lojista achando que
estava emitindo.

**A papeleta e o DANFCE.** Com fiscal ligado, o cupom impresso **é** o DANFCE: chave em grupos de 4,
QR Code, URL de consulta, itens, totais, forma de pagamento, troco e o `vTotTrib` da Lei 12.741.
Isso não cabe em qualquer layout — a lição de 2026-08-24 vale de novo: documento que não couber em
42 colunas se **reorganiza** (item em 2 linhas), não se espreme. ✅ A DF32 foi respondida (§8.5):
IBS/CBS **não** compõem o total, então o valor a pagar impresso continua sendo o `vNF` — o cupom
bate com o caixa sem linha extra. O destaque de IBS/CBS, quando vier, é informativo.

### 9.7 Contingência offline (`tpEmis = 9`)

Só existe para **NFC-e**. Quando a SEFAZ não responde, o cupom sai com **"EMITIDA EM CONTINGÊNCIA"**
e a venda segue. A nota é transmitida depois.

- **Prazo de transmissão posterior:** no PR, **24 horas** ✅. Varia por UF → **coluna** em
  `cfg_uf_autorizador`, não constante (F10).
- **Entrada:** automática após N falhas consecutivas — 🔴 **DF19** (N e a janela) — com
  justificativa registrada. Manual pelo ADMIN também.
- **Saída:** *health check* periódico no `NFeStatusServico4`; ao voltar, o job drena a fila em ordem
  de emissão.
- **Se a nota transmitida em contingência for rejeitada**, o documento entregue ao consumidor é
  inválido — precisa de tela de tratamento e alerta ruidoso, não uma linha de log. É a situação mais
  desagradável do módulo e merece desenho explícito na spec da tela.
- Painel mostrando há quanto tempo a empresa está em contingência e quantas notas estão na fila.

---

## 10. Cancelamento, correção e inutilização

### 10.1 Cancelamento é uma operação só (DF10)

O **Cancelamento de Venda** (`vendas.cancelamento`, ADMIN-only, que já reverte estoque, caixa e
contas a receber numa transação) passa a cancelar **também o documento fiscal**:

```
ADMIN cancela a venda
   ├─ há NFC-e/NF-e autorizada?
   │    ├─ NÃO  → fluxo atual, sem mudança
   │    └─ SIM  → dentro do prazo legal?
   │              ├─ SIM → evento 110111 → autorizado? → reverte a venda (fluxo atual)
   │              │                       └─ rejeitado? → NADA é revertido, erro na tela
   │              └─ NÃO → BLOQUEIA e instrui: "emita nota de devolução"
   └─ Justificativa obrigatória (mín. 15 caracteres) — a tela já pede motivo
```

**Estoque/caixa e fiscal nunca divergem: ou os dois andam, ou nenhum anda.**

| Modelo | Prazo | Base |
|---|---|---|
| **NFC-e (65)** | **30 minutos** após a autorização | Ajuste SINIEF 07/2018 / NT 2018.004 ✅ (PR sem regra própria localizada ⚠️) |
| **NF-e (55)** | **24 horas** | Ajuste SINIEF 07/2005 ✅ |

Fora do prazo a SEFAZ devolve a **rejeição 220** ⚠️. O sistema não deve nem tentar: calcula pela
`data_autorizacao` e bloqueia antes, com mensagem que **ensina a saída** — mesma filosofia do guard
`exigirCaixaAbertoParaDesfazer` de 2026-08-14.

Duas correções da verificação, que mudam o desenho:

- O evento **110112 não é "cancelamento fora do prazo"** — é o **cancelamento por substituição** da
  NFC-e (NT 2018.004), caso típico de forma de pagamento errada. Fora do v1 (§4.2), mas é a
  **candidata mais provável a ser pedida cedo** — trocar a forma de pagamento é erro de balcão
  diário, e hoje a saída é cancelar em 30 minutos e refazer a venda inteira.
- **Cancelamento extemporâneo não existe como evento nacional** — é procedimento administrativo
  estadual, e o **PR está entre as UFs que não o oferecem** ⚠️. Na UF piloto, perdidos os 30
  minutos, o único caminho é a nota de devolução. Isso **simplifica** o v1.

### 10.2 Fora do prazo → nota de devolução

É o item 2 da §4.1, e **está no v1**: NF-e de **entrada** (`tpNF=0`, `finNFe=4`) referenciando a
chave da NFC-e original. A tela de **Devolução de Produtos** — que desde 2026-08-11 já restringe a
produtos efetivamente vendidos e amarra no número da venda — é o lugar natural. Perdidos os 30
minutos da NFC-e, este é o **único** caminho no PR, onde cancelamento extemporâneo não existe.

> 🔴 **DF20 — a pergunta mais importante do v1, e ainda sem resposta:** e quando a venda original foi
> **NFC-e sem identificação do consumidor**? Não há CPF, não há endereço, não há município IBGE — e
> o `dest` da nota de entrada precisa de alguém. O tratamento é próprio por UF ⚠️ (as saídas usuais
> são emitir contra o próprio CNPJ da loja com a justificativa nas informações complementares, ou
> exigir o CPF **no momento da devolução**). **É o caso mais frequente do balcão inteiro** — venda
> em dinheiro, sem CPF, cliente volta no dia seguinte. Sem resposta, a F5 entrega uma tela que falha
> na maioria das devoluções reais. **Item obrigatório da F0**, junto com a leitura do MOC-PR.

### 10.3 Carta de Correção (CC-e) — fora do v1, e por um motivo estrutural

⚠️ **CC-e não se aplica à NFC-e.** O evento 110110 é de NF-e 55 (e CT-e). Como o v1 emite NFC-e e
uma NF-e de **entrada** (a devolução, que a própria loja emite para si e corrige refazendo), não há
onde usá-la — ela só passa a fazer sentido com a NF-e de venda (§4.2).

Fica registrado para quando chegar: corrige erro que **não** altera valor, destinatário, produto nem
data de emissão (natureza da operação, dados de transporte, informações complementares); prazo de
**720 horas** ⚠️; cada CC-e nova **substitui** a anterior, com `sequencia` própria; o texto tem
mínimo e máximo de caracteres ⚠️. O custo incremental será pequeno, porque o transporte
(`NFeRecepcaoEvento4`) e a tabela `documento_fiscal_evento` já saem prontos da F4.

**No v1, erro em NFC-e tem exatamente dois caminhos:** cancelar dentro de 30 minutos, ou emitir a
nota de devolução (§10.2). Não há terceiro — e, no PR, cancelamento extemporâneo não existe.

### 10.4 Inutilização

Consequência inevitável de F4: número alocado e nunca autorizado deixa buraco. Tela ADMIN-only,
faixa + justificativa, prazo até o **10º dia do mês seguinte** ✅. O sistema deve **detectar buracos
sozinho** e avisar — ninguém confere sequência de nota manualmente.

---

## 11. Arquivamento e download

### 11.1 O que se guarda

| Artefato | Formato | Onde |
|---|---|---|
| Nota própria autorizada | `nfeProc` (XML + protocolo) | bucket, imutável (F6) |
| Eventos (cancelamento, CC-e) | XML do evento + protocolo | bucket |
| Inutilizações | XML | bucket |
| Notas rejeitadas / denegadas | XML + motivo | banco (sem valor fiscal, com valor de suporte) |
| **XML de entrada (fornecedor)** 🆕 | XML bruto | **já existe** em `entrada_xml.xml_bruto` desde 2026-08-11 |
| DANFE / DANFCE | PDF | gerado sob demanda, **não** guardado — o que vale é o XML |

**Caminho no bucket:** `tenants/{id_tenant}/fiscal/{ano}/{mes}/{modelo}/{chave}.xml` — hierarquia
que torna o ZIP por período uma listagem de prefixo, não uma varredura. O prefixo
`tenants/{id_tenant}/` é montado pelo adapter a partir do `TenantContext` (P8), nunca por quem
chama; é o que `ArmazenamentoPrivado.gravar(AreaPrivada.FISCAL_XML, "{ano}/{mes}/…", …)` devolve
como chave, e é isso que vai em `documento_fiscal.xml_objeto_bucket`.

**Retenção: 5 anos** ✅ para o contribuinte (prazo decadencial, arts. 173/174 do CTN; os 132 meses
do Ajuste SINIEF 2/2025 são obrigação dos **fiscos**, não do lojista). O bucket precisa de política
de retenção/lock — apagar XML fiscal por acidente é problema legal do lojista, e isso **não existe
hoje** em `docs/infra/armazenamento-imagens.md`, desenhado para fotos de produto (deletáveis).
✅ **DF21 (revisada em 2026-08-17, decisão do dono do produto):** bucket fiscal separado e privado **para os XML**, com versionamento e retenção de 5 anos. O **certificado NÃO vai para bucket** — fica cifrado no banco do cliente (`fiscal_certificado.arquivo_cifrado`), o que o coloca no mesmo backup/restore do tenant e sob o RLS (P8) sem depender de política de bucket.

📄 **A implementação foi especificada em `docs/HANDOFF-ARQUIVAMENTO-XML.md`** (contrato, montagem
do `nfeProc`, idempotência, critérios de aceitação e definição de pronto) — escrito para handoff a
outro desenvolvedor, mas **implementado no mesmo dia (2026-08-18) seguindo essa spec à risca**.

✅ **Bucket provisionado em 2026-08-17 (ADR-014):** **MinIO auto-hospedado** (S3), no docker-compose do projeto e em VPS dedicado quando o volume justificar — não GCS, por custo. Object Lock **GOVERNANCE de 1825 dias** ligado na criação do bucket, versionamento junto, e a credencial da API **sem permissão de apagar** no bucket fiscal: apagar XML é recusado três vezes (código, política da credencial e retenção do bucket). Sobe com `docker compose up -d minio minio-init`. Detalhes, riscos e o que falta: `docs/infra/armazenamento-privado-minio.md`.

✅ **Consumidor implementado em 2026-08-18** — `fiscal.documento.ArquivamentoXmlService` +
`ArquivamentoXmlJob` (V036). Toda nota/evento/inutilização **autorizada** é arquivada
automaticamente logo após a autorização (caminho quente, best-effort — nunca derruba a
emissão/cancelamento/inutilização se o bucket falhar) e há um job `@Scheduled` de 10 em 10 minutos
que varre o que ficou pendente (MinIO fora do ar, restart no meio, contingência). `nfeProc` montado
por **concatenação de texto** (nunca reserialização — invalidaria a assinatura em silêncio),
validado contra `procNFe_v4.00.xsd` **antes** de subir ao bucket (F11). Idempotente (P2): chave
determinística, 409 em regravação, hash conferido. `GET /api/v1/fiscal/documentos/{id}/xml` passa a
servir o `nfeProc` (com protocolo) assim que arquivado, com fallback para `xml_assinado` antes
disso. Testado contra MinIO real (Testcontainers) + Postgres real, incluindo validação de XSD de
verdade (não só "a coluna foi preenchida") — `ArquivamentoXmlTest`, 4 testes. Suíte completa:
687/687. Único achado de teste que valeu registrar: o fixture minimalista de `protNFe`
(`<infProt><cStat>100</cStat></infProt>`) usado nos outros testes fiscais não é XSD-válido —
`infProt` exige `tpAmb/verAplic/chNFe/dhRecbto/cStat/xMotivo` em sequência; só apareceu porque
nenhum teste anterior reconstruía e validava o `nfeProc` de verdade.

### 11.2 A rotina de download para a contabilidade

Filtros em popup obrigatório (padrão do projeto): período, empresa, modelo (55/65/todos), situação
(autorizadas / canceladas / todas), **saídas e/ou entradas** 🆕, com ou sem PDF.

Saída: **ZIP** organizado por `AAAA-MM/`, com subpastas `saidas/`, `entradas/` e `eventos/`, mais um
`relatorio.csv` de índice (chave, número, data, valor, situação, CNPJ da contraparte) — é isso que
o contador abre primeiro.

**Geração assíncrona com progresso ao vivo:** o padrão já existe (`GaugeProgresso` + polling,
construído na Importação de Dados). Um mês de loja movimentada são dezenas de milhares de XMLs;
isso não sai em requisição síncrona.

**Entrega:** 🔴 **DF22** — `comum.arquivocompartilhado` guarda em **memória** e limita a 20 arquivos
por tenant; um ZIP de centenas de MB não cabe nesse desenho. O ZIP fiscal vai para o bucket com URL
assinada de validade curta. **O bucket já existe** (ADR-014) — mas o `ArmazenamentoPrivado` **não
tem** método de URL assinada, de propósito: hoje arquivo privado sai pela API, autenticado.
Oferecer URL assinada é decisão a registrar junto com a DF22, não método a acrescentar em
silêncio. E o ZIP não pertence à área `FISCAL_XML` (imutável, 5 anos): é artefato descartável,
regerável a partir dos XMLs, então precisa de uma área nova, apagável.

### 11.3 Acesso do contador

🔴 **DF23:** criar um papel `CONTADOR` (só leitura, só fiscal, sem ver custo nem margem) ou o
lojista baixa e repassa? O modelo hoje é ADMIN/OPERADOR, e o ADMIN é **único e imutável por
tenant** — um terceiro papel é mudança estrutural em `identidade`, não um detalhe. Recomendação:
v1 sem papel novo (o lojista baixa e envia); papel `CONTADOR` na F6, junto com o backoffice.

### 11.4 Consulta pública

Toda NFC-e autorizada tem URL pública de consulta pela chave. A tela de documentos deve oferecer o
link e o QR — é o caminho que o consumidor usa.

### 11.5 Distribuição DF-e (futura)

Baixar da SEFAZ as NF-e que **fornecedores emitiram contra o CNPJ do lojista** eliminaria o upload
manual de XML na Entrada de Produtos — que já existe, já faz o matching por EAN/código do fornecedor
e já foi testada com NF-es reais. É a integração de maior valor/esforço do módulo. Exige consulta
por NSU com controle do último NSU lido, descompressão GZIP e **manifestação do destinatário**.

---

## 12. Telas

Todas seguem o padrão consolidado: ✕ de fechar (`BotaoFecharTela`), Enter navega como Tab, foco
automático no 1º campo, popup obrigatório de filtros nas telas de lista, `Toast` para erro/sucesso
(nunca banner inline), `AjudaDaTela` (R22), grid com paginação por página e ordenação por coluna,
maiúsculas nos campos de texto, seção de auditoria (`InfoRegistro.tsx`) no fim do formulário.

| Chave | Nome | Papel | O que faz |
|---|---|---|---|
| `fiscal.configuracao` | Configuração Fiscal da Empresa | ADMIN | CRT, regime, IE, série, ambiente, CSC, flags. Singleton **por empresa**, no espírito de `configuracao.geral` |
| `fiscal.certificado` | Certificado Digital | ADMIN | Upload do `.pfx`, validade, alerta de vencimento (30/15/7 dias). Nunca devolve o arquivo |
| `fiscal.perfil` | Perfis Fiscais | ADMIN | CRUD de perfil + regras por UF/destinatário/operação. Padrão de cadastro |
| **`fiscal.conformidade`** 🆕 | Conformidade Fiscal | ADMIN | **Antes de ligar o fiscal:** lista o que impede emitir — produtos sem NCM/unidade/origem/perfil, clientes sem município IBGE, empresa sem certificado ou sem CNAE. Com contagem, filtro e link para a tela de correção. É a diferença entre ligar o fiscal numa segunda-feira e descobrir os problemas no caixa |
| `fiscal.documentos` | Documentos Fiscais | ADMIN + OPERADOR | Lista com popup de filtros; ver XML, DANFCE/DANFE, reprocessar, cancelar, consultar na SEFAZ. Mostra também os `NAO_EMITIDO` (§9.1) |
| `fiscal.download` | Download de XML | ADMIN | ZIP por período com progresso ao vivo (§11.2) |
| `fiscal.inutilizacao` | Inutilização de Numeração | ADMIN | Faixa + justificativa; detecta buracos sozinho |
| `fiscal.contingencia` | Painel de Contingência | ADMIN | Estado, fila pendente, entrar/sair manualmente |
| `catalogo.produto` | *(alteração)* | — | Seção fiscal: unidade, perfil, CEST, origem, unidade tributável |
| `identidade.empresa` | *(alteração)* | — | Município IBGE, CNAE, IM, matriz/filial |
| `cadastros.cliente` | *(alteração)* | — | Município IBGE e **indicador de IE** — sem este, o PDV não sabe se pode emitir NFC-e |
| `financeiro.tipocarteira` | *(alteração)* 🆕 | — | `tPag`, bandeira, CNPJ da credenciadora, tipo de integração |
| `vendas.pdv` | *(alteração)* | — | Emissão síncrona da NFC-e, DANFCE, tratamento de rejeição, recusa em venda a contribuinte (§9.6) |
| `vendas.cancelamento` | *(alteração)* | — | Cancelamento fiscal integrado com trava de prazo |
| `vendas.devolucao` | *(alteração)* | — | Emite a NF-e de entrada referenciando a NFC-e original |
| `configuracao.importacao` | *(alteração)* 🆕 | — | Planilha de produtos passa a aceitar as colunas fiscais — sem isso, migrar 10.000 produtos é digitação manual |

---

## 13. API (superfície `/api/v1`, JWT com `aud=tenant`)

```
GET/PUT  /api/v1/fiscal/config/{idEmpresa}
POST     /api/v1/fiscal/certificado                        (multipart; write-only)
GET      /api/v1/fiscal/certificado/{idEmpresa}            (metadados; nunca o arquivo)
GET/POST/PUT/DELETE /api/v1/fiscal/perfis
GET      /api/v1/fiscal/conformidade/{idEmpresa}           (pendências que impedem emitir)
GET      /api/v1/fiscal/documentos                         (filtros, paginação por página, ordenação)
GET      /api/v1/fiscal/documentos/{id}
GET      /api/v1/fiscal/documentos/{id}/xml
GET      /api/v1/fiscal/documentos/{id}/danfe
POST     /api/v1/fiscal/documentos/{id}/reprocessar
POST     /api/v1/fiscal/documentos/{id}/cancelar            { justificativa }
POST     /api/v1/fiscal/documentos/{id}/consultar-sefaz
POST     /api/v1/fiscal/nfce/emitir                         { idVenda }
POST     /api/v1/fiscal/nfe/devolucao                       { idDevolucao }
POST     /api/v1/fiscal/inutilizacoes
GET      /api/v1/fiscal/status-sefaz/{idEmpresa}
POST     /api/v1/fiscal/contingencia/{entrar|sair}
POST     /api/v1/fiscal/download                            (assíncrono, devolve idJob)
GET      /api/v1/fiscal/download/progresso/{idJob}
GET      /api/v1/fiscal/simular-tributos                    (motor puro, sem emitir — ouro pro suporte)
```

Fora do v1, com o desenho já previsto para não quebrar a superfície depois:
`POST /documentos/{id}/carta-correcao`, `POST /nfe/emitir { tipoOperacao, idOrigem }` (venda a
contribuinte, transferência, devolução ao fornecedor) e `POST /nfe/complementar`. O
`POST /nfe/devolucao` do v1 é deliberadamente **específico**, não genérico: endpoint genérico com um
único caso de uso vira parâmetro que ninguém valida.

Padrões do projeto que valem aqui: **Problem Details (RFC 9457) com corpo sempre preenchido** — o
`ResponseStatusException` sem corpo já mordeu o projeto em 2026-08-11 —, filtro `id_tenant`
explícito em **toda** query (F8), `GlobalExceptionHandler` cobrindo violação de FK, e paginação por
número de página (`pagina`/`limite`).

---

## 14. Arquitetura Java

Módulos: `fiscal.motor` (cálculo puro) · `fiscal.documento` (montagem e estados) · `fiscal.sefaz`
(transporte) · `fiscal.certificado` · `fiscal.arquivo` · `fiscal.danfe`.

**O risco real da DF7.** A lib open-source mais madura e a única com suporte confirmado à NT
2025.002 é **`br.com.swconsultoria:java-nfe`** (MIT, v4.1.1 de jun/2026; cobre eventos,
inutilização, Distribuição DF-e, QR Code e contingência). Só que ela compila para **Java 8**, usa
**JAXB `javax.*`** e traz **Apache Axis2 1.7.5** — e o Niner é **Spring Boot 4 / Java 25, jakarta
nativo**. Não é detalhe de build: é conflito de `javax.xml.bind` × `jakarta.xml.bind` no mesmo
classpath. Alternativa mapeada: `com.github.wmixvideo:nfe` (Apache-2.0, v5.0.65 de jul/2026), sem
confirmação de suporte à reforma e com foco menor em NFC-e.

✅ **DF7 — desenho híbrido**, com a PoC da F0 como gate:

1. Usar a lib para **modelo/XSD, assinatura XMLDSig e QR Code** — a parte chata e bem resolvida.
2. **Não** usar o Axis2. Os WebServices da SEFAZ são POST de XML sobre HTTPS com **mTLS**; o
   `HttpClient` do JDK 25 faz isso nativamente, com controle fino de timeout e pool.
3. Isolar num módulo Maven próprio, com *shading/relocation* se preciso, para o `javax.*` legado não
   vazar para o resto da API.
4. **PoC antes de comprometer a arquitetura** (F0): subir em Java 25 com Spring Boot 4, assinar um
   XML e autorizar uma NFC-e **completa, com grupos IBS/CBS**, na homologação do PR. Se travar, a
   alternativa é escrever a assinatura direto com `java.xml.crypto` (XMLDSig é padrão do JDK) e
   gerar as classes do XSD com JAXB jakarta — caminho mais longo, sem dívida.

**Assinatura:** o padrão vigente é **RSA-SHA1** com canonicalização C14N ⚠️ — nenhuma NT migrando a
SHA-256 foi encontrada. Ler o MOC vigente na F0; se um dia migrar, é troca de constante, não de
arquitetura.

> **O que o B5 já descobriu sobre a assinatura, lendo o XSD (economiza tentativa no B6):**
> 1. A `Signature` é **obrigatória** no `TNFe` — `<xs:element ref="ds:Signature"/>` sem
>    `minOccurs="0"`. Um XML não assinado **nunca** passa no schema; não existe variante do tipo
>    que a dispense. Consequência prática: a validação XSD em produção roda **depois** de assinar,
>    e o teste do B5 completa o documento com um esqueleto de assinatura para poder validar a
>    estrutura sem depender do B6.
> 2. O atributo `URI` do `Reference` é restrito a **`minLength=2`** — ou seja, o XSD **proíbe**
>    `URI=""` (que assinaria "o documento todo"). Tem que ser `#NFe<chave>`, apontando para o
>    `Id` do `infNFe`. O PoC do B0 já fazia assim; agora se sabe que é o schema exigindo.
> 3. A `Signature` fica **fora** do `infNFe` (é irmã dele dentro de `NFe`), depois do
>    `infNFeSupl`. O QR Code, portanto, **não é assinado** — o que é coerente com ele poder ser
>    remontado a partir da chave.

**mTLS multi-tenant:** cada requisição usa o certificado **do lojista**, não da Vetor. É um
`KeyStore` por empresa, montado a partir do `.pfx` decifrado, com cache em memória de vida curta e
`SSLContext` por empresa. Cuidado explícito: **nunca cachear `SSLContext` sem chave de tenant** —
seria emitir nota com o certificado do lojista errado. É o `TenantContext` (P8) valendo para um
recurso que não é linha de banco.

> ✅ **Implementado e testado no B6 (2026-08-17).** A chave do cache é a **impressão digital do
> certificado**, o que dá dois ganhos sobre uma chave `(tenant, empresa)`: trocar o certificado
> invalida a entrada sozinho (sem TTL nem invalidação manual) e duas empresas nunca colidem mesmo
> se os ids se repetissem. O teste que garante isso sobe um **servidor HTTPS real com
> `setNeedClientAuth(true)`** e confere o DN que chegou do outro lado — duas empresas em
> sequência têm que apresentar cada uma o seu. Controle negativo: cacheando com chave fixa, é
> exatamente esse teste que quebra.

**DANFE/DANFCE em PDF:** não há biblioteca Java open-source viável (as encontradas estão
abandonadas). Construir com o que o projeto já usa — `jsPDF` no front para o cupom térmico (caminho
já calibrado) e/ou OpenPDF/Jasper no backend para o DANFE A4. Lembrar de 2026-08-14: **PDF e
impressão térmica são caminhos separados e precisam ser conferidos separadamente.**

---

## 15. Segurança e multi-tenant

- `id_tenant` explícito em toda query, além do RLS (F8).
- Certificado cifrado no bucket, senha em KMS, nunca em log/dump/resposta (F7); uso auditado.
- Ambiente `HOMOLOGACAO` × `PRODUCAO` **por empresa**, com aviso visual permanente na tela.
- 🔴 **DF24 — tenant `INADIMPLENTE` pode emitir nota?** Ligado à D10 do plano de negócio. Bloquear
  emissão fiscal por inadimplência de assinatura é **parar a loja do lojista**; a régua precisa ser
  deliberada, não herdada por acidente do gate de login. Recomendação: nunca bloquear emissão —
  bloquear cadastro, relatório e telas de gestão, mas deixar vender e emitir.
- 🔴 **DF34** — impersonação de suporte (P9/R21) tocando dado fiscal: o staff da Vetor pode
  **emitir** ou **cancelar** nota em nome do lojista durante impersonação? Recomendação: **não** —
  leitura sim, emissão nunca, com o bloqueio no serviço e não só na tela.

---

## 16. Testes, homologação e operação

**16.1 Testes automatizados.** O projeto está em 509/509 verdes com Testcontainers; o fiscal mantém
o padrão:

- **Motor tributário: teste de tabela** por (CRT × CST/CSOSN × UF × operação). É a maior massa de
  testes do módulo e a mais barata — **nenhum toca a rede**.
- **Montagem de XML validada contra o XSD oficial** em teste, sempre.
- **Transporte com SEFAZ mockada** (WireMock) para autorização, rejeição, timeout e queda.
- **Numeração:** teste de concorrência (N threads emitindo) provando ausência de número duplicado e
  de buraco.
- **Regra de 2026-08-14:** teste que só confere 200 no endpoint passa com o bug presente. Todo teste
  de emissão/cancelamento **confere o banco e o bucket**.
- **Regra de 2026-08-14 (bis):** fixture não usa `now()` quando a produção compara por data/prazo. O
  cancelamento tem janela de **30 minutos** — é exatamente o teste que quebra sozinho às 23:58.
- **Privilégios:** toda tabela nova entra em `PrivilegiosNinerAppTest`, porque
  `TestcontainersConfiguration` conecta a app como superusuário e `GRANT`/RLS ficam invisíveis para
  o resto da suíte.

**16.2 Homologação na SEFAZ.** Roteiro obrigatório antes de qualquer lojista real: emitir, cancelar,
inutilizar e operar em contingência no ambiente de homologação do PR, com certificado real da
empresa piloto. O credenciamento de homologação do PR é automático — dá para começar na F0.

**16.3 Piloto.** Uma loja real, uma UF, acompanhamento diário, **com o contador dela olhando as
primeiras notas**. Nota fiscal não tem *beta* silencioso: erro aparece como cliente parado no caixa.

**16.4 Rotina permanente (o custo da DF1).** Alguém na Vetor precisa **assinar** isto: acompanhar
Notas Técnicas do Portal Nacional e dos portais estaduais; manter calendário das obrigatoriedades (a
próxima já marcada: **Simples/MEI e IBS/CBS em janeiro/2027**); monitorar disponibilidade da SEFAZ
por UF e **taxa de rejeição por tenant** (rejeição subindo é sintoma de NT nova não implementada);
manter as tabelas nacionais atualizadas (cClassTrib, CEST, CFOP, IBPT, MVA).

---

## 17. Fases de entrega

| Fase | Entrega | Como se sabe que acabou |
|---|---|---|
| **F0 — Prova de conceito** | Lib escolhida; PoC de assinatura + autorização com IBS/CBS na homologação do PR; leitura manual do MOC-PR, da NT 2025.002 v1.51 e do XSD; resposta às quatro perguntas ⚠️ que o v1 não pode carregar em aberto: **§8.5 (DF32 — IBS/CBS compõem o total?)**, **§10.2 (DF20 — devolução sem consumidor identificado)**, §9.3 (chave × CNPJ alfanumérico) e §6.4 (tabela `tPag` vigente) | Uma NFC-e autorizada em homologação, por script, fora do produto — e as quatro respostas escritas com fonte |
| **F1 — Fundação** | Migrations V034+; colunas fiscais em `empresa`/`produto`/`cliente`/`tipo_carteira`; tabelas nacionais semeadas; certificado; configuração fiscal; **Conformidade Fiscal**; colunas fiscais na Importação de Dados; `entrada_item_tributo` (DF30) | Tela de certificado funcionando com alerta de vencimento; Conformidade listando pendências reais de um tenant de teste |
| **F2 — Motor tributário** | Cálculo dos 3 regimes + Simples/MEI + IBS/CBS + FCP + `vTotTrib`; cadastro de perfil fiscal; endpoint de simulação | Suíte de tabela verde; `/simular-tributos` batendo com nota real conferida por contador |
| **F3 — NFC-e** | Emissão síncrona no PDV, DANFCE térmico, contingência offline, consulta e reprocessamento | Loja piloto vendendo com nota autorizada por um dia inteiro, **incluindo queda de internet** |
| **F4 — Cancelamento e guarda** | Evento 110111 integrado ao Cancelamento de Venda; inutilização; arquivamento; download em ZIP (saídas + entradas) | Contador da loja piloto baixa o mês fechado e aceita |
| **F5 — NF-e de devolução** | Nota de **entrada** (`tpNF=0`, `finNFe=4`) referenciando a NFC-e original, espelhando a tributação dela; DANFE A4; integração com a tela de Devolução de Produtos | Cliente devolve depois dos 30 minutos e a nota de entrada sai autorizada, com o estoque batendo |
| **F6+ — Implementações futuras** | Tudo da §4.2, na ordem que o negócio pedir. Candidatas naturais a virem primeiro: **cancelamento por substituição** (o "fechou no cartão errado" do balcão), **baixa por perda** (a mais barata: o ajuste já existe) e **NF-e de venda a contribuinte** (a que o `NAO_EMITIDO` vai medir) | Cada uma com homologação própria |

### 17.1 Ponto de partida da codificação (estado em 2026-08-16)

**Feito:** o schema inteiro — `V034__fiscal_referencia.sql` (referência nacional, global),
`V035__fiscal_documento.sql` (tabelas de tenant, RLS FORCE) e as colunas fiscais nas migrations
donas (V014 empresa, V016 cliente, V017 produto, V025 tipo_carteira). 35/35 migrations aplicam
limpo num banco recriado do zero; 509/509 testes verdes.

**Não feito:** nenhuma linha de Java, nenhuma linha de React, nenhuma spec de tela.

**Ordem de ataque.** Os blocos B1–B3 e B4 são independentes entre si — dá para atacar o motor em
paralelo às telas, se fizer sentido.

| Bloco | O que é | Depende de | Precisa de certificado? |
|---|---|---|---|
| **B0** | **PoC da F0**: assinar um XML e autorizar uma NFC-e com IBS/CBS na homologação do PR, por script, fora do produto. Valida a DF7 (lib `java-nfe` é Java 8 + `javax` + Axis2 contra nosso Java 25 jakarta) e responde às 4 perguntas ⚠️ da F0 | — | **Sim** |
| **B1** | Specs de tela em `docs/telas/`: `fiscal-configuracao.md`, `fiscal-certificado.md`, `fiscal-perfil.md`, `fiscal-conformidade.md`. Spec antes do código (§5 da spec principal) | — | Não |
| **B2** ✅ | Cadastros fiscais: `FiscalConfigService`/`Controller` ✅, `FiscalCertificadoService` ✅ (upload multipart → arquivo E senha cifrados no banco, *write-only*, DF21 revisada), `PerfilFiscalService` ✅ (padrão de cadastro consolidado) — **+ as 3 telas React** ✅ (2026-08-17, testadas ao vivo no navegador). **Fora do B2**: campos fiscais em Produto/Cliente/Empresa/Tipo de Carteira ficam para o B3 (Conformidade Fiscal), que é quem precisa deles | B1 | Não |
| **B3** ✅ | **Conformidade Fiscal** — painel + drill-down (2026-08-17), backend + tela. Lista o que impede emitir (empresa/produtos/pagamentos bloqueiam; clientes só avisa). Achado real confirmado ao vivo: hoje todo tenant nasce bloqueado em "Formas de pagamento" (`codigo_tpag` sem tela própria) — é o que revela o tamanho do problema de base cadastral | B2 | Não |
| **B4** ✅ | **Motor tributário** (`fiscal.motor`): puro, sem I/O. ICMS (CSOSN, + CST no CRT 2) + PIS/COFINS + IBS/CBS + FCP + `vTotTrib`. Teste de tabela por (CRT × CST/CSOSN × UF × operação), sem Testcontainers. **Feito em 2026-08-17**, já sob a DF37 | — (o schema já basta) | Não |
| **B5** ✅ | Montagem do XML + validação contra o **XSD oficial** em teste — `fiscal.documento` (`MontadorXmlNfce`, `ChaveAcesso`, `ValidadorXsd`), 2026-08-17. 25 testes validando contra o schema real. Fechou a **DF32** por fonte primária | B4 | Não |
| **B6** ✅ | Assinatura (XMLDSig) + transporte (`HttpClient` do JDK com mTLS por empresa) — `AssinadorXmlNfe` + `fiscal.sefaz` (`SefazTransporte`, `SefazAutorizadorService`), 2026-08-17. 27 testes, incluindo mTLS contra servidor HTTPS real e validação criptográfica da assinatura. **Não precisou do certificado real**: certificados autoassinados cobrem tudo menos a homologação ponta a ponta, que o B0 já fez | B5, B0 | **Sim** |
| **B7** | PDV: emissão síncrona, DANFCE térmico, contingência offline, recusa em venda a contribuinte | B6 | **Sim** |
| **B8** | Cancelamento (110111), inutilização, arquivamento no bucket, download em ZIP | B7 | **Sim** |
| **B9** | NF-e de devolução de venda (entrada, `finNFe=4`), espelhando a tributação gravada em `documento_fiscal_item` | B8 | **Sim** |

**Nota (2026-08-18):** B7 e B8 fecharam em 2026-08-17 (ver `docs/PROGRESSO.md`), mas a linha do B8
acima ficou desatualizada — "arquivamento no bucket" tinha ficado **fora** do escopo fechado
naquele dia (DF21 ainda não tinha bucket provisionado). Fechado à parte em **2026-08-18**: ver
§11.1 e `docs/HANDOFF-ARQUIVAMENTO-XML.md`. Download em ZIP (§11.2) continua não implementado
(DF22 em aberto).

**Por onde começar, concretamente:**

- **Com o certificado em mãos → B0.** É meio dia de trabalho e é o único gate real de arquitetura:
  se a lib não subir em Java 25 com Spring Boot 4, o plano B (assinar com `java.xml.crypto` e gerar
  as classes com JAXB jakarta) muda o cronograma inteiro — e é muito mais barato descobrir isso
  antes de B5 do que depois.
- **Sem o certificado → B1 seguido de B2.** As telas de cadastro seguem um padrão consolidado
  (trabalho previsível) e o motor precisa de perfil fiscal cadastrado para ser exercitado de
  verdade. B4 pode andar em paralelo com fixtures inseridas direto no banco.

**O que pedir ao dono do produto junto com o certificado:** CNPJ e inscrição estadual da empresa
(para conferir contra o titular do certificado), o **pacote de XSD** da NF-e/NFC-e e a tabela
**cClassTrib** do IT 2025.002 (~173 códigos, portal do SVRS). O portal da NF-e bloqueia acesso
automatizado — os 10.515 NCMs de `cfg_produto_ncm` vieram por esse mesmo caminho.
⚠️ O `.pfx` **nunca** vai para o chat nem para o repositório: `api/secrets/` já está no `.gitignore`.

---

## 18. Riscos

| Risco | Impacto | Mitigação |
|---|---|---|
| Lib de DF-e incompatível com Java 25 / Spring Boot 4 | Alto — trava a arquitetura | PoC na F0, antes de qualquer código de produto (§14) |
| ~~IBS/CBS compondo ou não o total da nota (DF32)~~ | ~~Alto — cupom não bate com o caixa~~ | ✅ Resolvido: não compõem (XSD, `vNFTot` separado). O cupom bate com o caixa |
| QR Code v3.00 implementado errado | Alto — cupom inválido no dia 1 | Confirmar no MOC; validar cupom real com leitor no piloto |
| NT nova não implementada a tempo | **Crítico — todos os tenants param juntos** | Rotina de acompanhamento (§16.4); homologação sempre pronta |
| Vazamento de certificado | Crítico — terceiro emite no CNPJ do lojista | F7 + KMS + auditoria de uso |
| **Base cadastral incompleta no onboarding** | Alto — lojista liga o fiscal e não consegue vender | Conformidade Fiscal (§12) + colunas fiscais na Importação |
| SEFAZ instável | Médio — PDV travado | Contingência offline (§9.7) |
| Nota duplicada por retransmissão | Alto — problema fiscal do lojista | F5: consulta por chave antes de retransmitir |
| Motor tributário errado | Alto — multa para o lojista | F9 (auditabilidade), `/simular-tributos`, validação por contador no piloto |
| **Transferência com tratamento pré-ADC 49** | Alto — nota errada em toda transferência | §8.4 validado com contador **antes** de a transferência sair da §4.2 |
| **Lojista atender revendedor sem saber que ficou sem nota** | Médio — descoberto só na contabilidade | Estado `NAO_EMITIDO` visível na tela de Documentos (§9.1) + aviso no PDV, não silêncio |
| Suporte fiscal engolindo a equipe | Alto para o negócio | DF12: fiscal só no Profissional/Escala ou como add-on |
| Perda de XML no bucket | Crítico — obrigação legal de 5 anos | ✅ Bucket com Object Lock de 1825 dias, separado do de fotos (ADR-014). ⚠️ **Retenção não é backup:** WORM impede apagar, não protege contra perder o disco — plano de backup do MinIO é pré-requisito da migração para VPS |

---

## Anexo A — Decisões em aberto

| # | Decisão | Recomendação |
|---|---|---|
| DF12 | Fiscal entra em qual plano comercial? | Add-on, ou exclusivo do Profissional/Escala |
| DF14 | Devolução ao fornecedor: no Cancelamento de Entrada ou tela nova? *(decisão futura, §4.2)* | Tela nova — cancelar entrada e devolver mercadoria são operações diferentes |
| ~~DF15~~ | ~~Empresa equiparada a industrial (IPI) entra no v1?~~ | ⛔ Sem caso possível depois da DF37: optante do Simples recolhe IPI dentro do DAS e não destaca na saída |
| DF16 | Licenciamento da tabela IBPT para SaaS multi-tenant | Licença única da Vetor, se o contrato permitir |
| DF18 | Timeout de autorização no PDV antes de cair em contingência | 10 s |
| DF19 | Quantas falhas disparam contingência automática | 2 falhas consecutivas em 60 s |
| **DF20** | **Devolução de NFC-e sem consumidor identificado** | ⚠️ **Decisão de v1, não futura** — é o caso mais comum do balcão. Investigar na F0, decidir antes da F5 (§10.2) |
| **DF21** | Onde ficam certificado e XML? | ✅ **Fechada 2026-08-17, consumidor implementado 2026-08-18.** Certificado **cifrado no banco** do cliente; XML no **bucket privado** com WORM de 5 anos — **MinIO auto-hospedado** (ADR-014), arquivamento automático de nota/evento/inutilização autorizados. Ver §11.1 |
| DF22 | Entrega do ZIP grande | Bucket + URL assinada, não cache em memória |
| DF23 | Papel `CONTADOR` (leitura fiscal) | Futuro; no v1 o lojista baixa e repassa |
| DF24 | Tenant `INADIMPLENTE` pode emitir nota? | Sim — nunca bloquear emissão |
| **DF29** 🆕 | Vale-mercadoria na nota: forma de pagamento ou desconto? | Forma de pagamento; confirmar com contador no piloto. **Decisão do v1** — o vale já quita venda no PDV hoje |
| **DF30** 🆕 | Guardar tributos das notas de **entrada** (`entrada_item_tributo`)? | Sim, na F1 — o dado já passa pelo parser |
| **DF31** 🆕 | CSOSN 101 (crédito do Simples) no v1? | Fora do v1; só 102/300/400/500 |
| ~~DF32~~ | ~~IBS/CBS compõem o `vNF` em 2026?~~ | ✅ **RESPONDIDA (2026-08-17, XSD oficial): NÃO.** O layout tem `vNFTot` separado, "valor total considerando os impostos por fora" — a existência do campo é a resposta. `vNF` segue batendo com o caixa. Ver §8.5 |
| **DF33** 🆕 | Série própria para contingência? | Sim: 1 normal, 9 contingência |
| **DF34** 🆕 | Staff impersonando pode emitir/cancelar nota? | Não — leitura apenas |

---

## Anexo B — Verificação de fatos (2026-08-15/16)

**Limitação metodológica:** o portal `nfe.fazenda.gov.br` bloqueia acesso automatizado. Os itens
"◐" foram checados em fontes técnicas convergentes que citam a norma (TecnoSpeed, TOTVS, ACBr,
Avalara) e **devem ser batidos manualmente contra o PDF/XSD oficial na F0**.
Legenda: ✅ fonte oficial · ◐ fontes secundárias convergentes · ✳ corrigido pela verificação.

| # | Item | Veredito |
|---|---|---|
| 1 | Versão da NT 2025.002-RTC | ◐ ✳ **v1.51 (~31/07/2026)**; adia ~20 validações para 01/09 (homolog.) e **05/10/2026** (produção). Obrigatoriedade CRT 3 desde 03/08/2026 ✅ (site do CGIBS) |
| 2 | Simples/MEI: data em 2027 | ◐ **04/01/2027** (art. 348 da LC 214/2025) |
| 3 | QR Code v3.00 e CSC | ◐ v3.00 obrigatório desde 01/09/2025; autenticidade por assinatura; CSC fora do QR mas **não extinto** |
| 4 | Algoritmo de assinatura | ◐ **RSA-SHA1 + C14N** vigente; nenhuma NT de migração a SHA-256 localizada |
| 5 | UF piloto e autorizador | ✅ ✳ **PR NÃO usa SVRS** — autorizador próprio (portal Sped-PR) |
| 6 | Prazo pós-contingência | ✅ **PR: 24 horas** (NPF 100/2014). Varia por UF → coluna em `cfg_uf_autorizador` |
| 7 | Prazos de cancelamento | ✅ NF-e 24 h (SINIEF 07/2005) · NFC-e 30 min (SINIEF 07/2018) · ✳ rejeição de prazo é a **220**, não 501 · ✳ **110112 = cancelamento por substituição**, não extemporâneo; extemporâneo é administrativo e o **PR não oferece** |
| 8 | Inutilização e guarda | ✅ Inutilização até o **10º dia do mês subsequente** · guarda do contribuinte **5 anos** (CTN); os 132 meses do Ajuste SINIEF 2/2025 são obrigação dos fiscos |
| 9 | CST PIS/COFINS no Simples | ✅ **99**, com base/alíquota/valor zerados (orientação SEFAZ-BA) |
| 10 | Credenciamento NFC-e no PR | ✅ Homologação **automática**; produção via Portal Receita/PR; CSC no mesmo portal |
| 11 🆕 | **Transferência entre estabelecimentos** | ◐ ✳ **ICMS não incide** (ADC 49 + LC 204/2023); transfere-se **crédito**; Convênio ICMS 109/2024 permite opção anual por tributar. **A v1.1 deste documento estava conceitualmente errada aqui** |
| 12 🆕 | **CC-e não se aplica à NFC-e** | ◐ Evento 110110 é de NF-e 55 (e CT-e). Prazo de 720 h ⚠️ |
| 13 🆕 | **`infIntermed` em venda por marketplace** | ◐ Obrigatório desde a NT 2020.006 ⚠️ |
| 14 🆕 | **DIFAL e Simples Nacional** | ◐ Empresa do Simples não recolhe DIFAL como remetente (ADI 5464/STF) |
| 15 🆕 | **`cEAN` de produto sem GTIN** | ◐ Literal `SEM GTIN`; GTIN é validado contra base oficial e código inventado é rejeitado |
| 16 🆕 | **Chave de acesso × CNPJ alfanumérico** | 🔴 **Não confirmado.** A chave é numérica de 44 posições e embute o CNPJ do emitente. Item da F0 |
| 17 | **IBS/CBS compõem o total da nota?** | ✅ **Não** — confirmado no XSD (campo `vNFTot` separado do `vNF`), 2026-08-17 |
| 18 🆕 | Tabelas `tPag` / `tBand` vigentes | 🔴 Conferir a versão da NT 2023.004 no MOC — os códigos acima de 16 mudaram recentemente |

---

## Anexo C — De-para: campo do XML × dado do Niner

Resumo operacional do que a montagem do XML vai buscar onde. `(novo)` = coluna que não existe hoje.

| Grupo | Campo | Origem no Niner |
|---|---|---|
| `ide` | `cUF`, `cMun` | `cfg_uf_autorizador` / `empresa.codigo_municipio_ibge` *(novo)* |
| `ide` | `nNF`, `serie`, `cNF`, `cDV` | `fiscal_numeracao` + `documento_fiscal` |
| `ide` | `tpNF`, `finNFe`, `idDest`, `indFinal`, `indPres` | `documento_fiscal` *(novo)* |
| `emit` | CNPJ, IE, razão social, endereço | `empresa` (+ colunas novas de §6.1) |
| `emit` | `CRT` | `fiscal_config_empresa.crt` *(novo)* |
| `dest` | CPF/CNPJ, nome, endereço | `cliente` (validação alfanumérica já existente) |
| `dest` | `indIEDest`, `cMun` | `cliente` *(novo)* |
| `det/prod` | `cProd` | `produto_barra.sku` |
| `det/prod` | `cEAN`, `cEANTrib` | `produto_barra.ean` ou literal `SEM GTIN` |
| `det/prod` | `xProd` | `produto.descricao` + cor + tamanho |
| `det/prod` | `NCM`, `CEST`, `EXTIPI` | `produto.codigo_ncm` · `produto.cest` *(novo)* |
| `det/prod` | `uCom`, `qCom`, `vUnCom`, `vProd` | `produto.unidade_comercial` *(novo)* + `produto_movimento_detalhe` |
| `det/prod` | `uTrib`, `qTrib`, `vUnTrib` | `produto.unidade_tributavel` + fator *(novos)* |
| `det/prod` | `vDesc` | `produto_movimento_detalhe.valor_desconto` (já rateado pelo PDV) |
| `det/imposto` | tudo | motor tributário → `documento_fiscal_item` (§7.4, camada 3) |
| `total` | `vNF`, `vProd`, `vDesc`, `vTotTrib` | `documento_fiscal` |
| `transp` | `modFrete`, transportadora, volumes | `documento_fiscal_transporte` *(novo)* |
| `pag/detPag` | `tPag`, `vPag`, `card` | `tipo_carteira` + `documento_fiscal_pagamento` *(novos)* |
| `pag` | `vTroco` | PDV *(hoje não persistido)* |
| `infIntermed` | CNPJ e id do vendedor na plataforma | `documento_fiscal_intermediador` *(novo, DF26)* |
| `infNFeSupl` | `qrCode`, `urlChave` | gerado na emissão (NFC-e apenas) |

---

## Anexo D — Fontes

**Verificação (v1.1 e v2.0):**

- [Sped-PR — Web Services NFC-e](https://sped.fazenda.pr.gov.br/NFCe/Pagina/Web-Services-NFC-e) ·
  [Credenciamento de emissores](https://sped.fazenda.pr.gov.br/NFCe/Pagina/Credenciamento-de-emissores) ·
  [NPF 100/2014 consolidada](https://sped.fazenda.pr.gov.br/sites/sped/arquivos_restritos/files/migrados/File/NFCE/NPF_100_2014_NFCe_Consolidada_com_NPF036_2015.pdf)
- [CGIBS — obrigatoriedade IBS/CBS em 03/08/2026](https://www.cgibs.gov.br/prazo-para-adequacao-dos-sistemas-de-emissao-de-notas-fiscais-ao-regulamento-do-ibs-encerra-em)
- [TOTVS — NT 2025.002-RTC v1.51](https://www.totvs.com/blog/fiscal-clientes/nt-2025-002-rtc-v1-51-prorroga-inicio-das-validacoes-para-1o-de-setembro/)
- [CONFAZ — Ajuste SINIEF 07/2005](https://www.confaz.fazenda.gov.br/legislacao/ajustes/2005/AJ007_05) ·
  [Ajuste SINIEF 2/2025](https://www.confaz.fazenda.gov.br/legislacao/ajustes/2025/AJ002_25)
- [SEFAZ-BA — NF-e para o Simples Nacional (CST 99)](https://www.sefaz.ba.gov.br/docs/inspetoria-eletronica/icms/nfe_orientacoes_simples_nacional.pdf)
- [ACBr — modelo de referência das tags da reforma](https://acbr.sourceforge.io/ACBrMonitor/ModeloNFeINICompletoReformaTribu.html)
- [SVRS — Tabela de Classificação Tributária (cClassTrib)](https://dfe-portal.svrs.rs.gov.br/DFE/TabelaClassificacaoTributaria)
- [Webmania — rejeição 1021 (gate por CST)](https://ajuda.webmania.com.br/pt-BR/articles/12680846-rejeicao-1021-grupo-ibs-cbs-informado-indevidamente)
- [NS7 — rejeição 220 (prazo de cancelamento)](https://ns7.com.br/docs/ns7/rejeicao-220-prazo-de-cancelamento-superior-ao-previsto-na-legislacao/)
- [Oobj — cancelamento extemporâneo por UF](https://oobj.com.br/legislacao/cancelamento-extemporaneo-nfce/) ·
  [GTIN na NF-e 4.00 ("SEM GTIN")](https://oobj.com.br/legislacao/como-funciona-o-codigo-gtin-na-nfe-4-00/)

**Pesquisa inicial:**

- [Portal Nacional NF-e — Reforma Tributária do Consumo](https://www.nfe.fazenda.gov.br/portal/exibirArquivo.aspx?conteudo=AklZnck3o6I%3D) ·
  [Portal SVRS — Serviços NFC-e](https://dfe-portal.svrs.rs.gov.br/Nfce/Servicos)
- TecnoSpeed — [NT 2025.002](https://blog.tecnospeed.com.br/nota-tecnica-reforma-tributaria-nfe-nfce/) ·
  [Tabela cClassTrib](https://blog.tecnospeed.com.br/tabela-cclasstrib/) ·
  [Rejeições com IBS e CBS](https://blog.tecnospeed.com.br/principais-rejeicoes-da-nf-e-com-ibs-e-cbs/) ·
  [Prazo de cancelamento da NFC-e](https://blog.tecnospeed.com.br/prazo-de-cancelamento-da-nfc-e/)
- [RFB/Fenacon — IT 2025.002 v1.50](https://fenacon.org.br/wp-content/uploads/2026/04/IT-2025.002-v.1.50-Tabelas-de-Classificacao-do-IBS-e-da-CBS-RFB-2026_04_15-1.pdf)
- [Avalara — NT 2025.001: QR Code v3.00](https://www.avalara.com/br/pt/blog/2025/09/nt-qrcode-versao3-nfe-nfce.html)
- [Focus NFe — NFC-e em contingência](https://focusnfe.com.br/blog/nfc-e-em-contingencia-como-emitir-e-detalhes-para-aprovacao/)
- [Samuel-Oliveira/Java_NFe](https://github.com/Samuel-Oliveira/Java_NFe) · [wmixvideo/nfe](https://github.com/wmixvideo/nfe)
- [BuscadorNCM — CSOSN](https://buscadorncm.com.br/cst/csosn) · [Mentor Fiscal — CST ICMS](https://mentorfiscal.com.br/tabela-cst-icms-codigos-e-aplicacoes/)
- [Rz Sistemas — Lei 12.741/2012 e tabela IBPT](https://www.rzsistemas.com.br/lei-12-741-lei-da-transparencia-valor-aproximado-dos-tributos-fonte-ibpt/)

> **Nota sobre as fontes:** as ⚠️ deste documento são reais. Um módulo fiscal construído sobre blog
> técnico funciona até a primeira rejeição em massa. A F0 existe para trocar cada ⚠️ por uma
> referência de PDF oficial com página — e o resultado dessa leitura volta para cá, virando v2.1.
