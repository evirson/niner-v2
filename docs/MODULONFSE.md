# NFS-e Nacional — escopo dos blocos S5–S7 e a configuração que o lojista faz sozinho

**Produto:** Nainer (Vetor Sistemas) · **Banco:** `niner_db` · **Versão do documento:** 1.1
**Data:** 2026-08-29, atualizado em 2026-08-31 · **Status:** ⭐ **S0 CONCLUÍDO — uma NFS-e foi
emitida e cancelada em produção pelo código do Nainer** (§2.7). **S5 pronto** (V099/V100), **S5.5
pela metade** (V101 + `ParametrosMunicipaisClient`, sem tela) e **S6 com o motor construído**
(V102 + 15 classes em `fiscal/nfse/`).

⛔ **Mas nenhuma venda emite NFS-e ainda, e o motivo não é sutil:** não existe **controller**, não
existe **tela**, e **nenhuma classe fora de `fiscal/nfse/` referencia o módulo** — medido com
`grep -rln "nfse\|Nfse" api/src/main/java --include=*.java | grep -v /nfse/`, que devolve **zero**.
O motor está inteiro e **desconectado nas duas pontas**, que é exatamente o padrão que este
repositório já pagou para aprender (o outbox do marketplace, `CLAUDE.md`: *"antes de 'ligar' um
mecanismo pronto, meça se ele está ligado nas duas pontas"*).

> **Relação com os outros documentos.** O estudo que decidiu *se* o Nainer emite NFS-e é
> `docs/MODULOSERVICOS.md` §5 (DS7–DS10, decididas em 2026-08-28). Ele continua valendo como
> **decisão**. O que ele **não** tinha é evidência: foi escrito com fonte secundária e marcava como
> ⚠️ *não confirmado* quase tudo sobre a API. Este documento traz a evidência — medida em **dois
> sistemas da própria Vetor que emitem NFS-e Nacional em produção** — e a converte em escopo.
> Onde os dois divergirem sobre **fato técnico**, quem manda é este; onde divergirem sobre
> **decisão de produto**, quem manda é o `MODULOSERVICOS.md`.

---

## 1. Onde o projeto está — medido, não lembrado

| Bloco | Estado | Evidência |
|---|---|---|
| **S1** Serviço no catálogo | ✅ pronto | `V085`, `V086`, `produto.tipo_item`, `produto_servico` |
| **S2** Vender serviço no PDV | ✅ pronto | `VendaFiscalAssembler` filtra `tipo_item`, papeleta |
| **S3** Quem executou + comissão congelada | ✅ pronto | `V088`, `V089`, `V090` |
| **S4** Ordem de Serviço | ✅ pronto | `V087`, `V092`, `vendas/ordemservico/`, `docs/telas/ordem-servico.md` |
| **S0** Prova de conceito da NFS-e | ✅ **concluído em 2026-08-31** — NFS-e emitida **e cancelada** em produção | §2.7 · `api/scripts/EmitirNfseTeste.java` |
| **S5** Cadastro tributário de serviço | ✅ **pronto no banco** (2026-08-31) — `cfg_servico_lc116` (334 códigos) + `cfg_ramo_servico_lc116` (V099) e o bloco fiscal em `produto_servico` (V100). ⚠️ **A tela de Produtos ainda não expõe nenhum desses campos** | `V099`, `V100`, `db/scripts/gerar_lista_nacional_servicos.py` |
| **S5.5** Configuração autoatendida | ⚠️ **metade** — `fiscal_config_nfse` + `cfg_municipio_nfse` (V101) e `ParametrosMunicipaisClient`/`MunicipioNfseService` existem. ⛔ Faltam a **tela**, o assistente, o teste de conexão e a descoberta do `enviar_im` | `V101`, `fiscal/nfse/ParametrosMunicipaisClient.java` |
| **S6** Emissão | ⚠️ **motor construído, não ligado** — V102 + `IdDps`, `MontadorXmlDps`, `AssinadorXmlDps`, `EmpacotadorDps`, `NfseTransporte`, `RespostaSefin`, `EmissorDeNfse`/`EmissorNacionalNfse`/`EmissorFalso`, `NfseNumeracaoService`, `VendaNfseAssembler`, `NfseDocumentoRepositorio`, `NfseEmissaoService`. ⛔ **Sem controller, sem tela e sem chamador**: o PDV não emite | `V102`, `api/src/main/java/com/vetor/niner/fiscal/nfse/` |
| **S7** Ciclo de vida (cancelamento, exportação, aba) | ⚠️ **só o schema e o método** — `nfse_documento_evento` (V102) e o cancelamento no emissor; sem tela, sem prazo por município preenchido, e o ZIP do contador **não** leva NFS-e | `V102` |

⚠️ **Três lacunas de verificação que a tabela acima não mostra, e que valem mais que ela** (medidas
em 2026-08-31, ao conferir o dia):

1. ⛔ **O `EmissorFalso` foi construído para a suíte exercitar a máquina inteira — e nenhum teste o
   usa.** `grep -rl "EmissorFalso\|NfseEmissaoService\|nfse_documento" api/src/test/` devolve
   **vazio**. Os 28 testes do S6 cobrem `IdDps`, `MontadorXmlDps` e `RespostaSefin` (as três classes
   puras); **numeração, máquina de estados, gravação, recuperação de nota órfã e cancelamento não
   têm um único teste**. A promessa está escrita no `application.yml` de teste e ainda não foi
   cumprida — é a mesma forma de defeito que o próprio comentário lá cita.
2. ⛔ **O `ParametrosMunicipaisClient` não é chamado por ninguém** — `grep` fora do próprio arquivo
   devolve só uma menção em javadoc. Ou seja: a peça que o §5 chama de *"o coração do
   autoatendimento"* está escrita e **inerte**. Do `MunicipioNfseService`, a emissão usa apenas
   `enviarInscricaoMunicipal`; `registrarConvenio` e `registrarEnvioDeIm` — os dois métodos que o
   assistente chamaria — também não têm chamador.
3. ⚠️ **A alíquota do ISS tem dois donos, e nada os concilia hoje.** Quem monta a DPS lê
   `produto_servico.aliquota_iss` (V100, digitada pelo lojista); a alíquota oficial do ADN existe
   só no cliente inerte do item acima. Enquanto a tela do S5.5 não nascer, o §5 item 6 descreve uma
   intenção, não o comportamento.

✅ **E o que eu suspeitei e estava errado, registrado para ninguém "consertar" de novo:**
`nfse_documento_item` **é** gravado — `NfseDocumentoRepositorio` faz o par `DELETE`+`INSERT` do
idioma "apaga e regrava". A auditoria da tabela P3 está de pé.

⚠️ **`produto_servico` nasceu de propósito sem os campos fiscais** — está escrito na V085:
*"os campos FISCAIS (código da LC 116, alíquota de ISS, retenção, local de incidência) NÃO entram
aqui ainda — são o bloco S5"*. Ou seja: o catálogo de serviço **existe e funciona**, e é
**fiscalmente mudo**. Um serviço cadastrado hoje não tem como virar nota.

---

## 2. ⭐ O achado que muda o plano: o S0 já foi respondido, duas vezes, em produção

O `MODULOSERVICOS.md` §7.1 trata o **S0** como o gate mais caro do módulo: *"emitir UMA DPS em
Produção Restrita e responder cinco perguntas por fonte primária"*. E lista como risco **R3**
(*"alvo em movimento"*) e **R4** (*"bloqueio em terceiro"*).

**As cinco perguntas já foram respondidas — não por pesquisa, por emissão real.** Dois sistemas
nesta mesma pasta `~/Documents/projetos` emitem NFS-e Nacional contra o SEFIN todos os dias:

| Projeto | Stack | Desde | O que emite |
|---|---|---|---|
| **`workshop`** | TypeScript / Fastify / Postgres | 2026-05 | NFS-e de oficina, Curitiba/PR, produção |
| **`finance-v`** | ⭐ **Java 25 + Spring Boot 4 + Postgres** — a mesma stack do Nainer | 22/05/2026 | NFS-e do faturamento da própria Vetor, Curitiba/PR, produção |

⭐ **O `finance-v` é o achado que importa.** São ~2.650 linhas de Java, na mesma versão de JDK e de
Spring do Nainer, com ciclo **emissão + cancelamento validado em produção**, e um documento de
implementação escrito para ser reusado (`finance-v/docs/fiscal/nfse-nacional/IMPLEMENTACAO_FINANCE_V.md`,
§13 é literalmente um *"checklist pra replicar em outro projeto"*).

### 2.1 As cinco perguntas do S0, respondidas

| # | Pergunta do S0 | Resposta **medida** | Onde |
|---|---|---|---|
| (a) | mTLS / assinatura / formato | **Confirmado.** mTLS com o `.pfx` A1 (PKCS12) direto no `HttpClient` do JDK; **HTTP/1.1 obrigatório** (HTTP/2 volta `RST_STREAM: Use HTTP/1.1`); XMLDSig por **JSR 105** (built-in do JDK, sem Santuario) com **RSA-SHA256 + Exclusive C14N**; corpo JSON com o XML em **gzip + base64**; **mTLS sozinho autentica**, sem header `Authorization`; **truststore padrão do JDK basta** (SERPRO/GlobalSign em homologação, Sectigo em produção) | `finance-v` `DpsXmlSigner`, `Pkcs12HttpClientFactory`, `MAPA.md` |
| (b) | Como o `pAliq` do Simples é informado | **Confirmado, e a resposta é "quase sempre omitindo"**: com `opSimpNac=3` + `regApTribSN=1` + `tpRetISSQN=1`, **`pAliq` NÃO vai no XML**. O que vai é `pTotTribSN` — a **alíquota efetiva do Simples**, que vem do contador (campo de configuração, faixa 0–33) | `dps-builder.ts`, `DpsXmlBuilder`, `IMPLEMENTACAO §5.2` |
| (c) | O que sai para MEI | **`opSimpNac=2`** (MEI é valor próprio do enum: 1=não optante, 2=MEI, 3=ME/EPP). Sem `regApTribSN`, que só é obrigatório no 3 | `MAPA.md` §Regime tributário |
| (d) | Formato da chave e da numeração | **Confirmado.** `Id` da DPS = `"DPS" + cMun(7) + tpInsc(1) + CNPJ(14) + série(5) + nDPS(15)` = **45 chars**, determinístico. **Chave da NFS-e = 50 chars** (os 45 + DV de 5). `nDPS` é sequencial **nosso**; `nNFSe` é o número **da prefeitura**, devolvido na resposta — não existe consulta de "próximo número" | `DpsIdBuilder`, `IMPLEMENTACAO §5.3` |
| (e) | Cancelamento e substituição | **Confirmado em produção.** `POST /nfse/{chave}/eventos`, evento **101101**, `Id` = `"PRE" + chave(50) + tipoEvento(6)` = 59 chars, `xMotivo` de **15–255 chars obrigatório**. A resposta vem no campo `eventoXmlGZipB64` — **nome diferente** do da emissão, e o parser tem de cobrir os dois | `EventoCancelamentoBuilder`, `MAPA.md` §Eventos |

### 2.2 ⭐ E o R4 (bloqueio em terceiro) também está resolvido

O `MODULOSERVICOS.md` marca como *"o item mais provável de virar bloqueio"* a falta de um CNPJ com
Inscrição Municipal e CNAE de serviço. **Ele existe, e é o da própria Vetor:**

| | |
|---|---|
| CNPJ | `22120254000186` — E C TECNOLOGIA LTDA (Vetor Sistemas) |
| Município | **Curitiba/PR**, `cLocEmi` **4106902** |
| Inscrição Municipal | `07156092` |
| Regime | Simples Nacional **ME/EPP** → `opSimpNac=3`, `regApTribSN=1` |
| Certificado A1 | `AC SOLUTI Multipla v5`, válido até **06/03/2027** (o mesmo `.pfx` que já está em `~/Documents/projetos`) |
| Estado | **Emite NFS-e em produção desde 22/05/2026** pelo `finance-v` |

⚠️ **Mas há uma armadilha específica de Curitiba que muda o plano do S0**, e ela custou uma sessão
ao `finance-v`:

| Ambiente | IM do prestador | O que acontece |
|---|---|---|
| **Produção Restrita** (`tpAmb=2`) | ⛔ **nada passa** | `E0116` — **medido em 2026-08-29, ver §2.6** |
| **Produção** (`tpAmb=1`) | **proíbe** | `E0120` — *"IM não deve ser informada"*. Omitindo, **funciona desde 22/05** |

⚠️ **A documentação do `finance-v` se contradiz sobre o `E0116`** e eu repeti a contradição na v1.0
deste arquivo: o `MAPA.md` §Descobertas diz *"Curitiba exige a IM **cadastrada no CNC**"* e o
`MAPA.md` §IM do prestador, junto com o comentário do `DpsXmlBuilder`, diz *"**E0116 se ausente**"*.
São afirmações opostas — uma culpa o cadastro, a outra a omissão. **Medi as duas na §2.6, e nenhuma
está certa.**

⭐ **Consequência prática, e é uma decisão de escopo, não um detalhe:** com este CNPJ, **o S0 do
Nainer não roda em produção restrita**. O caminho que funcionou no `finance-v` foi **emitir em
produção uma nota de valor mínimo e cancelá-la dentro das 24 h** que Curitiba concede. É o que eu
recomendo, com duas guardas obrigatórias antes:

1. o guarda-costas de produção (`app.nfse.allow-production` do `finance-v` — no Nainer, o irmão de
   `NINER_FISCAL_AMBIENTE_FIXO`), para que produção seja um ato deliberado;
2. o cancelamento **testado no mesmo dia**, porque fora do prazo municipal ele deixa de ser uma
   opção e vira problema do contador.

### 2.3 O que **continua** sem resposta — e é curto

Duas coisas, e nenhuma delas bloqueia começar. *(A terceira — a API de parâmetros municipais —
foi **medida em 2026-08-29** e está na §2.4.)*

1. ⚠️ **O fuso do `dhEmi` para emitente fora de Brasília.** O `workshop` manda **sempre `-03:00`**,
   com um comentário dizendo que o SEFIN compara pelo *horário de parede* de Brasília e que mandar
   em UTC dá `E0008`. Isso é o oposto da regra do Nainer para NF-e, onde o `dhEmi` sai **no fuso da
   UF do emitente** (`comum.tempo.FusoDaUf`). Nenhum dos dois projetos tem emitente fora de
   Curitiba (UTC−3), então **ninguém mediu o caso do Acre ou do Amazonas**.
   ⭐ **Recomendação:** enviar o **mesmo instante normalizado para `America/Sao_Paulo`**
   (`atZoneSameInstant`, que é o que o Nainer já faz ao arquivar). Preserva o instante *e* não
   depende de o SEFIN honrar um offset diferente de −03:00. E entra na lista de limites declarados
   do `ComparacaoDeDataNoFusoCertoTest`, que hoje não alcança `DateTimeFormatter.format(OffsetDateTime)`.
2. ⚠️ **NT 008 e o DANFSe** (ago/2026): o novo padrão e a descontinuação da API antiga de DANFSe
   continuam **não confirmados em fonte oficial** — as fontes divergiam entre julho e agosto. O
   `workshop` construiu o **próprio PDF** (`nfse-pdf.service.ts`); o `finance-v` **faz proxy** do
   `GET /danfse/{chave}` no ADN. Os dois caminhos existem e funcionam hoje.

> ⭐ **O que isso faz com o gate.** O S0 deixa de ser *"descobrir se dá"* e vira *"conferir duas
> coisas e emitir uma nota de R$ 1 em produção"*. O risco **R3** (*"alvo em movimento"*) encolhe
> para o item 3 acima; o **R4** (*"bloqueio em terceiro"*) fecha. O que **não** encolhe é o **R1**
> — *"o módulo fiscal de serviço é maior do que parece"* —, e as §4 e §9 deste documento existem
> para dimensioná-lo com honestidade.

### 2.4 ⭐ Medido em 2026-08-29: a API que entrega a configuração mastigada

Rodei o certificado da Vetor contra a API de verdade. **Tudo abaixo é resposta real do governo**,
não leitura de manual — e é o que sustenta a §5 deste documento.

**Primeiro, a correção de rota que ninguém tinha:** `parametros_municipais` **não está no SEFIN**.
O swagger do SEFIN (`GET /SefinNacional/swagger/docs/v1`, que responde 200 com o certificado)
declara em duas rotas *"Este serviço foi movido"* — `ParametrosMunicipais` e `DANFSe` vivem no
**ADN**, em `https://adn.nfse.gov.br/parametrizacao` e `/danfse`. Chamar o caminho do `MAPA.md` do
`finance-v` devolve uma **página HTML de 404 do IIS**, que é exatamente o tipo de resposta que o
parser tem de reconhecer como falha de rota, não como rejeição.

**As sete rotas do ADN Parâmetros Municipais** (`/parametrizacao/swagger/v1/swagger.json`):

| Rota | Serve para |
|---|---|
| `GET /{cMun}/convenio` | ⭐ **o município opera no Emissor Nacional?** (a DS8, resolvida por município) |
| `GET /{cMun}/{cServ}/{competencia}/aliquota` | ⭐ **a alíquota do ISS**, com vigência |
| `GET /{cMun}/{cServ}/historicoaliquotas` | histórico (para nota retroativa) |
| `GET /{cMun}/{cServ}/{competencia}/regimes_especiais` | regimes especiais do município |
| `GET /{cMun}/{competencia}/retencoes` | ⭐ as hipóteses de **retenção** do município, com o texto da lei |
| `GET /{cMun}/{nBenef}/{competencia}/beneficio` | benefício municipal |
| `POST` (3 rotas) | manutenção — é a prefeitura que usa, não nós |

**O convênio, para quatro municípios:**

| Município | `aderenteAmbienteNacional` | `aderenteEmissorNacional` | `situacaoEmissaoPadraoContribuintesRFB` |
|---|---|---|---|
| Curitiba (4106902) | 1 | **1** | 1 |
| Rio de Janeiro (3304557) | 1 | **1** | 1 |
| São Paulo (3550308) | 1 | **1** | 0 |
| Salvador (2927408) | 1 | **0** | 0 |

⭐ **Salvador é a prova de que isto vale a pena.** O `MODULOSERVICOS.md` §5.3 registra que *"aderir
≠ operar"* e que **não conseguiu fechar em fonte oficial** quantos municípios operam de fato — as
fontes secundárias diziam 392 e 1.898. **A pergunta não precisava de um número agregado: ela é por
município, e a fonte oficial responde.** `aderenteEmissorNacional = 0` é a DS8 acontecendo, e o
sistema pode dizer isso ao lojista **na implantação**, sem lista curada por nós (que envelheceria
em silêncio) e sem chamado.

**A alíquota, para o mesmo código de serviço (14.01.01.000 — manutenção de veículos):**

| Município | Resposta |
|---|---|
| Curitiba | `{"Incidencia":"SIM","Aliq":5.00,"DtIni":"2023-08-30"}` |
| Campo Largo/PR (4104808) | `{"Incidencia":"SIM","Aliq":3.00,"DtIni":"2025-11-01"}` |
| Salvador | `{"Incidencia":"SIM","Aliq":5.00,"DtIni":"2025-12-10"}` |
| São Paulo · Rio de Janeiro | `{"aliquotas":null,"mensagem":"Alíquotas não encontradas."}` |

⭐ **Campo Largo com 3,00% é o teste de sabotagem** — sem ele, quatro respostas de 5% pareceriam
constante. A alíquota **varia de verdade**, e vem com data de início de vigência, que é o que
permite emitir nota de competência passada com a alíquota da época.

⚠️ **E "não encontradas" é resposta legítima, não erro** — São Paulo e Rio não publicaram a tabela
no ADN. A tela **degrada**: sugere quando tem, pede ao lojista quando não tem, e **nunca bloqueia**
por causa disso. É a mesma regra que o `workshop` provou no CEP: *não conseguiu checar nunca
bloqueia*.

> ⛔ **A armadilha, e ela é cara:** o código do serviço nesta API vai **pontuado e com o desdobro
> municipal** — `14.01.01.000`, **não** `140101` nem `140101000`. E a mensagem de erro manda para o
> lado errado: `"O código do serviço deve ser composto por nove dígitos"` — mandei os nove dígitos
> (`140101000`) e tomei **a mesma mensagem**. São nove dígitos, sim, mas no formato
> `NN.NN.NN.NNN`. Perdi quatro tentativas nisso; quem ler a mensagem sem esta linha perde as
> mesmas. ⚠️ **A competência é data ISO** (`2026-08-01`); `202608` volta *"is not valid"*.

**Consequência de desenho:** o `cTribNac` que vai no **XML da DPS** tem **6 dígitos**
(`item+subitem+desdobro nacional`) e o código que a **API de parâmetros** pede tem **9 pontuados**
(os 6 + o desdobro **municipal** de 3). São a mesma coisa em dois formatos, e é o Java que
converte — nunca duas colunas.

### 2.5 ⭐ E a lista nacional de serviços estava no disco, com a regra de incidência junto

O anexo oficial que o `finance-v` guarda (`AnexoI-LeiautesRN_DPS_NFSe-SNNFSe_v1.01.00-homologacao.xlsx`)
tem uma aba — `RN MUN.INCID  INFO.SERV.` — que é **a Lista Nacional de Serviços inteira**:
**334 códigos** (o `MODULOSERVICOS.md` §5.4 dizia "338 ⚠️ fonte secundária, conferir"; agora está
medido) com a descrição oficial de cada um **e a regra de incidência do ISS**.

⭐ **Isso mata uma pergunta que o estudo tinha dado por perdida.** A §5.4 registra: *"não consegui
listar as 25 exceções do art. 3º da LC 116 em fonte confiável única — e não vou inventá-las"*, e
propunha `produto_servico.local_incidencia` preenchido pelo lojista com default `PRESTADOR`. **Não
é preciso perguntar:** a fonte diz, código a código. Medido na carga da V099:

| Local de incidência | Códigos |
|---|---|
| `PRESTADOR` (estabelecimento do prestador — a regra geral) | 271 |
| `PRESTACAO` (local da prestação) | 47 |
| `TOMADOR` (estabelecimento do tomador) | 10 |
| `ESPECIAL` (apuração no MAN / trecho de concessão rodoviária) | 5 |
| `SEM_INCIDENCIA` (990101) | 1 |

A aba traz ainda, por código, **qual bloco extra o leiaute da DPS exige** (`obra`, `atvEvento`,
`lsadppu`, `explRod`) — 33 dos 334. São exatamente os serviços que o v1 **não** atende (§4), e ter
a marca no banco é o que permite a tela **recusar o código com uma mensagem honesta** em vez de
montar um XML incompleto.


### 2.6 ⭐⭐ Medido em 2026-08-29: uma DPS do Nainer chegou ao SEFIN, e só o município a barrou

Escrevi uma sonda (`SondaDps.java`, arquivo único rodando em `java SondaDps.java` no JDK 25 do
host) que monta a DPS, assina com **JSR 105 · RSA-SHA256 · Exclusive C14N**, comprime em
**gzip+base64** e faz `POST /nfse`. **Produção restrita, `tpAmb=2`, sem valor fiscal.** Cinco
disparos, e cada um respondeu uma pergunta:

| # | O que foi enviado | Resposta do SEFIN | O que isso prova |
|---|---|---|---|
| 1 | XML sem a declaração `<?xml … encoding="UTF-8"?>` | **`E1229`** *"Xml não está utilizando codificação UTF-8"* | ⚠️ Código **novo**, não catalogado em nenhum dos dois projetos. `OMIT_XML_DECLARATION=yes` no `Transformer` derruba a DPS — e a mensagem fala em *codificação*, não em *declaração ausente*, que é onde a cabeça vai primeiro |
| 2 | DPS completa, **com** IM `07156092` | **`E0116`** | passou do XSD e da assinatura |
| 3 | DPS completa, **sem** IM | **`E0116`**, mensagem idêntica | ⛔ **derruba as duas explicações do `finance-v`**: não é "IM ausente" (o disparo 2 a informou) nem formato |
| 4 | IM em 4 formatos (`07156092`, `7156092`, `071560920`, `0715609`) | **`E0116`** nos quatro | não é máscara nem zero à esquerda |
| 5 | ⭐ **assinatura sabotada** (1 caractere trocado no `SignatureValue`) | **`E0714`** *"Arquivo enviado com erro na assinatura"* | ⭐⭐ **a prova que vale**: assinatura inválida para em `E0714`, **antes** das regras de negócio. Logo, quando a assinatura é a nossa, ela **passa** — e o `E0116` do disparo 2 é a primeira regra de negócio, não um erro nosso |

> ⭐⭐ **A conclusão do disparo 5 é a mais importante deste documento.** O XSD, a ordem dos
> elementos, o `Id` de 45 caracteres, a assinatura XMLDSig e o empacotamento gzip+base64 **do
> Nainer** já estão **corretos contra o SEFIN real**. Não é inferência a partir do código do
> `finance-v`: é o servidor do governo dizendo. O que falta para uma NFS-e existir é **uma regra de
> cadastro do município**, não código nosso.
>
> ⚠️ **O limite honesto:** o `E0116` é a **primeira** regra de negócio a falhar. Pode haver outras
> atrás dela que só aparecem quando ela sair do caminho. Quem prova isso é a emissão real (§2.2).

**Segunda rodada de sondagem (mesma sessão), preparando a emissão em produção:**

| # | O que foi enviado | Resposta | O que isso muda |
|---|---|---|---|
| 6 | `<toma>` só com `<CPF>` | **`E1235`** + `Complemento`: *"the element 'toma' has incomplete content. List of possible elements expected: **CAEPF, IM, xNome**"* | ⭐ **`xNome` do tomador é OBRIGATÓRIO.** O `MAPA.md` do `finance-v` marca `xNome` como `[0..1]` — **não é**, ao menos com CPF. O `finance-v` nunca tropeçou porque sempre manda o nome |
| 7 | `<toma>` com CPF **+ xNome** | `E0116` | o bloco passou |
| 8 | `<totTrib><indTotTrib>0</indTotTrib>` no lugar do `pTotTribSN` | `E0116` | passa no **schema** — mas ver o disparo 9 |
| 9 | ⭐ o mesmo `indTotTrib=0`, agora em **PRODUÇÃO** (2026-08-30) | **`E0712`** *"Para ME/EPP o indicador de informação de valor total de tributos não pode ser informado."* | ⭐⭐ **A regra de negócio proíbe.** `pTotTribSN` é **obrigatório** para optante do Simples — não há caminho sem a alíquota efetiva. ✅ E a recusa **não queimou o número**: 400 com `erros[]` é rejeição, a DPS 2001000 continua livre |
| 10 | o bloco `<totTrib>` **omitido** (restrita, 2026-08-31) | **`E1235`** · *"the element 'trib' has incomplete content. List of possible elements expected: 'tribFed, **totTrib**'"* | ⭐⭐ **Fecha o cerco pelo outro lado:** o schema **exige** `totTrib` e a regra de negócio **proíbe** o `indTotTrib` para ME/EPP. Logo `pTotTribSN` é **incontornável** — e a alíquota efetiva do contador é **pré-requisito de emissão**, não preferência |

⭐ **Ordem de validação do SEFIN, deduzida dos disparos 1–8:** encoding/declaração (`E1229`) →
schema XSD (`E1235`) → assinatura (`E0714`) → regras de negócio (`E0116`…). Saber a ordem vale
porque **um erro esconde os seguintes**: enquanto o `E0116` estiver de pé, nenhuma regra de negócio
posterior foi exercitada.

⭐ **E o `E1235` traz um campo `Complemento` com o texto do validador XSD** — foi ele que disse
exatamente qual elemento faltava. O catálogo de erros da tela (§5, item 11) **tem de exibir o
`Complemento`**, não só código e descrição: sem ele o `E1235` é *"falha no esquema XML"*, que não
aciona nada.

**O que o `E0116` realmente diz**, no texto integral que só apareceu ao medir:

> *"A IM deve ser informada para o emitente prestador do serviço na DPS, **conforme informações
> complementares registradas no CNC NFS-e do município emissor** informado na DPS."*

⚠️ A mensagem **acusa a omissão** (*"deve ser informada"*) num caso em que a IM **foi informada** —
e a causa está na oração subordinada: o que Curitiba registrou no **CNC da produção restrita** não
casa com o que enviamos. É um cadastro na prefeitura, num ambiente que existe só para teste, e não
há nada a corrigir do nosso lado. ⭐ **É exatamente o padrão que o `catch` genérico do front produz
e que este projeto já documentou:** uma mensagem que afirma algo sobre a nossa entrada quando o
problema é de outra camada.

**Consequência de produto, e ela é maior que o caso da Vetor:** *"testar em homologação primeiro"*
**pode simplesmente não estar disponível**, e isso varia por município. O produto tem de tratar
**"a primeira emissão é em produção"** como caminho suportado — com a nota de valor mínimo, o
cancelamento no mesmo dia e o aviso na tela —, não como exceção.


### 2.7 ⭐⭐ 2026-08-31 — o ciclo fechou: NFS-e emitida e cancelada pelo Nainer, em produção

Autorizado pelo dono do produto. Nota de **R$ 1,00**, cancelada **na mesma execução**
(`api/scripts/EmitirNfseTeste.java`).

| # | Operação | Resposta |
|---|---|---|
| 11 | `POST /nfse` com `pTotTribSN` | **HTTP 201** · chave `4106902222212025400018600000000073082608 7756429072` |
| 12 | `POST /nfse/{chave}/eventos` — evento 101101 | **HTTP 201** · `eventoXmlGZipB64` devolvido |
| 13 | ⭐ o **mesmo** evento reenviado, para conferir | **`E0840`** *"o evento de Cancelamento de NFS-e já está vinculado à NFS-e indicada"* |

⭐ **O disparo 13 é a verificação, não a operação.** HTTP 201 diz que o pedido foi aceito; quem
prova que o cancelamento **ficou** é a recusa do segundo. É o mesmo princípio do teste de sabotagem
do disparo 5 — *validação que confere o próprio resultado não prova nada*.

**A NFS-e gerada:** número **7308** na prefeitura de Curitiba, competência 2608.

#### ⛔ A chave de acesso NÃO é derivável — e os dois projetos de referência dizem que é

Isto é uma correção de fato, não um detalhe. O `IMPLEMENTACAO_FINANCE_V.md` §5.3 afirma:

> *"Chave NFSe = (45 chars do ID DPS) + DV(5) = 50 chars … A chave de acesso é **determinística** —
> viabiliza idempotência por OrphanRecovery"*

**Medido: falso.** O `Id` da DPS sem o prefixo tem **42** caracteres, a chave tem **50**, e as duas
**divergem já no 11º caractere**. O leiaute oficial (aba `LEIAUTE NFS-e` do Anexo I) dá a razão:

| Campo | Posições | Nossa nota |
|---|---|---|
| Cód. Município | 7 | `4106902` |
| **Amb. Gerador** | 1 | `2` (Sefin Nacional, não a prefeitura) |
| Tipo de inscrição | 1 | `2` (CNPJ) |
| Inscrição federal | 14 | `22120254000186` |
| **nNFSe** | 13 | `0000000007308` ← o número **da prefeitura**, que só existe depois de autorizada |
| AnoMês da DPS | 4 | `2608` |
| ⭐ **Cód. numérico aleatório** | 9 | `775642907` |
| DV | 1 | `2` |

⛔ **Dois campos tornam a chave impossível de calcular antes da emissão:** o `nNFSe`, que o SEFIN
atribui, e um **código numérico aleatório de 9 posições**. ⭐ **Consequência direta para o S6:** a
recuperação de nota órfã (timeout depois de o SEFIN ter gerado) **só pode ser por consulta**
(`GET /dps/{id}`, que é o caminho que de fato funciona — usei-o para varrer a numeração), **nunca
por chave calculada**. Quem escrever `EmissorNacionalNfse` acreditando na frase do `finance-v`
implementa um caminho que não existe.

#### ⚠️ O contêiner de erro muda de nome entre emissão e evento

| Operação | JSON de recusa |
|---|---|
| Emissão | `{"erros":[{"Codigo":…,"Descricao":…}]}` — plural, chaves com maiúscula |
| Evento | `{"erro":[{"codigo":…,"descricao":…}]}` — **singular**, chaves minúsculas |

⚠️ O `descreverErros` do `workshop` procura `erros`/`Erros`/`mensagens`/`Mensagens` — **não procura
`erro`**. Uma recusa de cancelamento cairia no genérico e perderia o código, que é exatamente o que
o `docs/nfse-rejeicoes.md` deles descreve como o defeito mais caro. O parser do Nainer tem de cobrir
as **quatro** combinações.

#### ⚠️ O CNPJ e o software do teste NÃO são os do produto

Lembrete do dono do produto (2026-08-31): *"o software emissor e o CNPJ da empresa de software que
emite NFS-e não são os mesmos do finance-v — ele é apenas base de exemplo do que funciona hoje"*.

Vale escrever, porque duas conclusões desta seção **encolhem** quando lidas assim:

1. ⭐ **A numeração não é compartilhada no produto.** O `nDPS` é sequencial por **(CNPJ, série)**, e
   no Nainer cada empresa de cada tenant tem a sua — `nfse_numeracao` no molde de
   `fiscal_numeracao`. Toda a preocupação com colisão contra o `finance-v` foi artefato de **este
   teste** ter tomado emprestado o CNPJ da Vetor. ⚠️ Mas o `setval` continua devido: o teste
   **consumiu de verdade** o `nDPS 2001000` naquele CNPJ.
2. ⚠️ **"Homologação bloqueada" é um fato sobre a Vetor em Curitiba, não sobre o produto.** O
   `E0116` vem do que **aquele município** registrou no CNC para **aquele emitente**. Outro lojista,
   em outro município, pode homologar normalmente. O que o teste prova é que **o caminho existe** —
   e que o produto precisa suportar "a primeira emissão é em produção" como possibilidade, não que
   ela seja a regra.
3. O `verAplic` do produto é o do **Nainer** (aqui foi `Nainer-S0`), nunca o de outro sistema.

#### ⚠️ O que o script ainda não faz, e o S6 tem de fazer

O `EmitirNfseTeste.java` grava os XMLs que **envia**, mas descarta os que o SEFIN **devolve** — o
XML da NFS-e autorizada e o XML do evento de cancelamento assinado pela Sefin. Esses dois são a
prova fiscal (guarda de 5 anos, `AreaPrivada.FISCAL_XML`), e é o que o `finance-v` persiste em
`nfse_xml_assinado_retorno` / `nfse_xml_cancelamento_retorno`. **Não repetir a omissão no S6.**


---

## 3. O que o Nainer já tem e se reaproveita — e onde os projetos de referência estão *piores*

⚠️ **Copiar o `finance-v` inteiro seria um erro.** Ele é mono-tenant na prática, guarda o `.pfx`
**em disco** e o XML fiscal **numa coluna `TEXT`**. O Nainer já resolveu essas três coisas melhor.
A tabela separa o que se copia do que **não** se copia:

| Peça | No Nainer hoje | Veredito |
|---|---|---|
| Certificado A1 | `fiscal_certificado` — `.pfx` **cifrado no banco** (AES-256-GCM, chave fora do banco), senha cifrada, CNPJ conferido contra a empresa, histórico de certificados, log de uso (`fiscal_certificado_uso`) | ✅ **usa o do Nainer.** ⛔ **Não copiar** o `APP_NFSE_SECRETS_DIR` do `finance-v`: arquivo em disco não entra no backup do tenant, não é isolado por RLS e exige `chmod` no deploy |
| Cifra de segredo | `comum.seguranca.SegredoCifrador` | ✅ equivale ao `SecretCrypto`; já existe |
| Assinatura XMLDSig | `AssinadorXmlNfe` — **RSA-SHA1 + C14N inclusivo**, `secureValidation=false` | ⚠️ **não serve como está.** A NFS-e exige **RSA-SHA256 + Exclusive C14N** e a `Signature` é irmã de `infDPS` no **root `<DPS>`**. É classe nova (`AssinadorXmlDps`) ou parametrização — não é reescrita: o `DpsXmlSigner` do `finance-v` são 80 linhas |
| Transporte mTLS | `SefazTransporte` — ⭐ **um `SSLContext` por certificado, com a impressão digital na chave do cache** | ✅ **o padrão se copia inteiro** (é a proteção contra emitir no CNPJ do lojista errado). ⚠️ O resto **não**: SEFAZ é POST de XML com truststore ICP-Brasil própria; SEFIN é **REST/JSON com o truststore padrão do JDK** e **HTTP/1.1 forçado** |
| Guarda do XML | MinIO `AreaPrivada.FISCAL_XML` — imutável, recusa sobrescrever e apagar, 5 anos | ✅ **usa o do Nainer.** ⛔ Não copiar a coluna `TEXT` do `finance-v` |
| Numeração | `fiscal_numeracao`, PK `(tenant, empresa, modelo, série)` | ✅ o padrão serve para o `nDPS`. ⚠️ **A PK não tem `ambiente`** — o mesmo defeito documentado na NF-e (nota de teste consome número de produção). Aqui dói **menos** (o `nDPS` é sequencial nosso e o SEFIN indexa pelo `idDPS`), mas a trava `NINER_FISCAL_AMBIENTE_FIXO` vale igual |
| Ambiente travado | `NINER_FISCAL_AMBIENTE_FIXO` + `ambienteTravado` no `GET` | ✅ **o gate de produção do S0 sai de graça daqui** |
| Bloqueio preventivo | **F11** — Conformidade Fiscal diz o que falta **antes** do balcão | ✅ é exatamente onde entram "serviço sem `cTribNac`", "sem alíquota" e "município não conveniado" |
| A venda não some | **F3** + `cfg_emite_fiscal_apos_venda` (DS13) | ✅ a NFS-e entra **no mesmo parâmetro**, não num paralelo |
| Cota do plano | `LimiteVendasService`, uma dimensão só (ADR-015) | ✅ **nada a fazer** — NFS-e não é dimensão nova |
| Exportação ao contador | `ExportacaoXmlLoteService` (síncrona e particionada) | ⚠️ **altera** — o ZIP tem de levar a NFS-e junto |
| CEP → código IBGE | ⭐ `web/src/lib/viacep.ts` **já devolve `ibge`** e o `EmpresaForm` **já preenche** `codigoMunicipioIbge` desde 2026-08-24 | ✅ **`cLocEmi` já é autoatendido** — não há trabalho aqui |
| Fuso | `FusoDaUf` / `FusoDaPlataforma` + `ComparacaoDeDataNoFusoCertoTest` | ⚠️ ver §2.3 item 2 |
| Suíte | ⚠️ roda com `emite_nfe = false` — foi assim que dois defeitos fiscais passaram por 911 testes verdes | ⭐ **corrigir aqui:** o `finance-v` tem `SefinNacionalClientStub` como **default**, e o cliente real só com `mode=real`. Adotar isso faz a suíte **exercitar o caminho da NFS-e**, que é o oposto do que acontece hoje com a devolução |

---

## 4. O escopo — os blocos

Cada bloco entrega algo verificável e depende só dos anteriores. Próxima migration livre: **V100**. ⚠️ A V096–V098 foram ocupadas pelas rodadas de auditoria enquanto este documento era escrito, e a minha migration nasceu como V096 e precisou virar V099 — duas migrations com o mesmo número fazem o Flyway recusar subir INTEIRO. Conferir `db/migration/` antes de escolher o número, sempre.

| Bloco | Entrega | Como se sabe que acabou |
|---|---|---|
| **S0′** — Conferência (§2.3) | Três `curl` com o certificado da Vetor: `/parametros_municipais/{4106902}/convenio`, `/parametros_municipais/{4106902}/{cTribNac}` e uma emissão + cancelamento de R$ 1 **em produção** pelo Nainer. **Fora do produto**, por script | As três respostas gravadas no repositório, e uma NFS-e do Nainer **autorizada e cancelada** no mesmo dia |
| **S5** — Cadastro tributário do serviço | `cfg_servico_lc116` (global, sem RLS, **carregado da fonte oficial**); `produto_servico` ganha `codigo_tributacao_nacional`, `codigo_tributacao_municipal`, `aliquota_iss`, `iss_retido_padrao`, `local_incidencia`; busca por texto; sugestão por ramo (V093); Conformidade Fiscal apontando o que falta | A Conformidade aponta pendência real num tenant de teste, e **nenhum código foi digitado de memória** |
| **S5.5** — ⭐ **Configuração autoatendida** (§5) | `fiscal_config_nfse` por empresa; assistente de configuração; teste de conexão; descoberta do `enviar_im`; consulta de parâmetros municipais; catálogo de erros acionáveis | Um lojista liga a NFS-e **do zero, sem abrir chamado**, e a tela diz o que falta em cada passo |
| **S6** — Emissão | `EmissorDeNfse` + `EmissorNacionalNfse` + **`EmissorFalso`** (default na suíte); `nfse_documento`/`nfse_item`; máquina de estados; numeração do `nDPS`; guarda do XML no MinIO; DANFSe | Uma NFS-e autorizada em produção para a loja piloto, e o XML no MinIO |
| **S7** — Ciclo de vida | Cancelamento (evento 101101); prazo por **município** em tabela (F10); NFS-e no ZIP do contador; aba em Documentos Fiscais; reprocessamento da pendente | Cancelar **dentro e fora** do prazo, com mensagem certa nos dois casos |

⛔ **O que fica de fora do v1, declarado:** substituição de NFS-e (evento 105102), manifestação do
tomador, `Distribuição DF-e` do ADN (notas emitidas fora do Nainer), NFS-e de obra/evento/rodovia
(blocos `obra`, `atvEvento`, `explRod` do layout) e IBS/CBS no serviço — este último **não porque
não importe**, mas porque o prazo é **01/01/2027** e o grupo `IBSCBS` já existe no layout, então
entra depois sem retrabalho de estrutura.

---

## 5. ⭐ A configuração que o lojista faz sozinho — o pedido dele

> *"nesse projeto o máximo que nosso usuário puder fazer sozinho pra configurar é a busca do nosso
> projeto, pra não depender do nosso suporte para isso"*

Esta seção é o coração do pedido. Para cada dado que a NFS-e exige, a pergunta é a mesma:
**o lojista consegue preencher isso sozinho, ou ele vai ligar para o suporte?**

| # | O que a NFS-e exige | Hoje no Nainer | O que falta para ser autoatendido |
|---|---|---|---|
| 1 | **Certificado A1** | ✅ upload pela tela, cifrado no banco, CNPJ conferido | ⚠️ Só falta a mensagem: hoje o erro de senha errada precisa dizer *"senha do certificado"*, não um 500 |
| 2 | **`cLocEmi`** (código IBGE, 7 dígitos) | ✅ **já resolvido** — o ViaCEP devolve e o `EmpresaForm` grava (2026-08-24) | nada |
| 3 | **Regime** (`opSimpNac`, `regApTribSN`) | ✅ `fiscal_config_empresa.crt` já existe (1, 2 ou 4) | ⭐ **derivar, nunca perguntar de novo:** CRT 1/2 → `opSimpNac=3` + `regApTribSN=1`; CRT 4 → `opSimpNac=2`. Perguntar o regime duas vezes é como se cria divergência |
| 4 | **Inscrição Municipal** | ✅ campo existe em `empresa` | ⚠️ o problema não é ter, é **saber se manda** — item 7 |
| 5 | **`cTribNac`** (6 dígitos, **334** códigos) | ✅ **V099** — lista oficial carregada + atalho por ramo | **A maior fonte de chamado do módulo.** Ninguém sabe que banho e tosa é `170500`. Solução: `cfg_servico_lc116` **carregada da fonte oficial**, busca por texto no cadastro do serviço, e ⭐ **atalho por ramo de atividade** — a V093 acabou de criar os cinco ramos de serviço (oficina, petshop, salão, assistência técnica, lava-rápido); cada um sugere uma lista curta de códigos prováveis. O lojista escolhe entre 5, não entre 338 |
| 6 | **Alíquota do ISS** (2% a 5%, lei municipal) | ⛔ não existe (⭐ mas a fonte já foi medida, §2.4) | ⭐ **É aqui que está o maior ganho, e ele é novo:** `GET /parametros_municipais/{codMun}/{cTribNac}` devolve **a alíquota e os regimes do município** para aquele código de serviço — do próprio SEFIN. Em vez de perguntar ao lojista (que não sabe) ou ao suporte (que teria de procurar na lei municipal), **o sistema pergunta à fonte e preenche**, mostrando de onde veio e deixando editar. ⚠️ **Nenhum dos dois projetos de referência usa este endpoint** — é preciso medir no S0′ antes de prometer |
| 7 | **Mandar ou não a IM** | ⛔ não existe | ⚠️ Depende de **município × ambiente**, e errar dá `E0116` ou `E0120` — dois códigos que não dizem nada a quem não conhece o CNC. Solução: coluna em `cfg_municipio_nfse (codigo_ibge, ambiente)`, com **descoberta automática no assistente**: o teste de configuração emite uma DPS de sondagem, lê o código, **inverte a bandeira e persiste a descoberta**. ⛔ A sondagem roda **no assistente**, nunca numa emissão de verdade — rejeição queima número |
| 8 | **Município atendido?** (DS8) | ⛔ não existe (⭐ resolvido pela medição da §2.4) | `GET /parametros_municipais/{codMun}/convenio` responde isso **por município**, sem lista curada por nós (que envelheceria em silêncio). Vira um item da Conformidade Fiscal, verificado na implantação — **antes** do balcão, F11 |
| 9 | **Alíquota efetiva do Simples** (`pTotTribSN`) | ⛔ não existe | ⛔ **MEDIDO em 2026-08-30 (§2.6, disparo 9): é obrigatório.** Sem ele, optante do Simples toma `E0712` e **não emite** — então o campo é pré-requisito da tela (F11 barra antes do balcão), não um "opcional que melhora a nota". ⚠️ **O limite honesto do autoatendimento.** O número depende do RBT12 (receita bruta de 12 meses) da empresa, que o Nainer **não tem** e não deve inventar (o `MODULOSERVICOS.md` §5.5 já recusa derivar de `uso_venda_mes`). O que dá para fazer, e ajuda muito: pedir **RBT12 + anexo (III ou V)** e **calcular** a alíquota efetiva pelas tabelas da LC 123 — ⛔ **carregadas da lei, não digitadas** —, mantendo o campo editável e validado (0–33). O `finance-v` já tem metade disso (`SimplesPartilhaTable`, anexos III e V) |
| 10 | **CEP do tomador** | ⚠️ ViaCEP só no front | ⚠️ **`E0240`** — CEP extinto. O `workshop` documentou o caso inteiro: a **NF-e passa** (a SEFAZ não confere CEP contra o DNE) e a **NFS-e rejeita**; a Receita e as bases abertas continuam servindo o CEP velho, e **só o ViaCEP diz a verdade**. Solução: checagem no **servidor**, antes de consumir número, com a regra que o `workshop` provou — *não conseguiu checar (`null`) **nunca** bloqueia* |
| 11 | **Ler o erro que voltou** | ⛔ não existe | ⭐ **O item que decide se o lojista resolve ou liga.** O `codigo_status` é **HTTP** (400 para toda recusa) e nunca identifica a causa; o código fiscal (`E0240`, `E0120`…) vem dentro de `erros[]`. Guardar `"E0240 — <descrição do SEFIN>"` **no início da mensagem**, traduzir só os códigos **vistos de verdade**, e deixar o desconhecido cair no genérico **exibindo a descrição do próprio SEFIN**, que já é acionável. ⛔ E a lição de 2026-08-24 vale dobrado aqui: `catch` genérico no front **apaga** a mensagem que o back trabalhou para produzir |
| 12 | **Saber se está funcionando** | ⛔ não existe | Botão **Testar conexão**: `GET /nfse/{50 zeros}` — a resposta esperada é **404 + `E2401`**, e é isso que prova que certificado, mTLS e ambiente estão certos. Resultado gravado (`ultimo_teste_em/status/mensagem`) e exibido na tela |

### 5.1 O desenho da tela

**Uma tela nova, `fiscal.nfse-configuracao`**, singleton **por empresa** — mesma variante da
`docs/telas/fiscal-configuracao.md` (seletor de empresa no topo, ADMIN-only, upsert no primeiro
`PUT`, `configurado: false` em vez de 404). Não é aba da configuração fiscal existente: aquela é de
NF-e/NFC-e, o vocabulário é outro e misturar produz o que a DS9 recusou no banco.

**Quatro seções, na ordem em que o lojista consegue responder:**

1. **Emissão** — `emite_nfse` (o gate F12, desligado por padrão), ambiente (escondido quando
   `NINER_FISCAL_AMBIENTE_FIXO`), série e próximo `nDPS`.
2. **Município** — código IBGE (já preenchido), botão **"Consultar parâmetros do município"** →
   convênio + alíquota sugerida (item 6/8), e a bandeira de IM (item 7) com o resultado da
   descoberta escrito em texto, não em jargão.
3. **Tributação** — regime (**derivado do CRT, somente leitura, com link para onde se altera**),
   RBT12 + anexo → alíquota efetiva calculada e editável (item 9).
4. **Certificado e teste** — reaproveita o certificado já enviado para a NF-e (⭐ **não pedir de
   novo**: é o mesmo A1, e pedir duas vezes é o tipo de atrito que gera chamado), com validade,
   titular e o botão **Testar conexão**.

⭐ **E um passo que nenhum dos dois projetos tem: o assistente.** Um botão **"Verificar
configuração"** que roda os seis testes em sequência (certificado válido → CNPJ bate → município
conveniado → parâmetros obtidos → conexão OK → IM descoberta) e devolve **uma lista com o que
passou e o que falta, cada pendência com o link da tela que a resolve**. É o padrão que a
Conformidade Fiscal já usa e que o `EmpresaForm` documenta ter corrigido em 2026-08: *"'sem IBGE' /
'sem CNAE' no painel de Conformidade não levavam a lugar nenhum"*.

---

## 6. Impacto no banco

| Migration | O quê | Risco |
|---|---|---|
| **V099** ✅ **aplicada** | `cfg_servico_lc116` (334 códigos + regra de incidência) e `cfg_ramo_servico_lc116` (atalho por ramo) — **globais, sem `id_tenant`, sem RLS**, mesma exceção de `cfg_produto_ncm`. Carga **derivada** do anexo oficial por `db/scripts/gerar_lista_nacional_servicos.py` | baixo. ✅ **Conferida no banco depois do Flyway** (334 linhas · 271/47/10/5/1 por incidência · 33 com bloco extra), porque migration que ninguém conferiu não foi verificada, foi torcida. ⚠️ O **mapa ramo→código é curadoria minha**, não fonte oficial — está escrito na própria migration |
| **V100** | `produto_servico` += `codigo_tributacao_nacional`, `codigo_tributacao_municipal`, `aliquota_iss`, `iss_retido_padrao`, `local_incidencia` (default `PRESTADOR`) | baixo — todas nullable (F12). ⚠️ `CHECK` na alíquota **0–5**: a auditoria de 2026-08-27 achou percentual sem teto fechando venda errada |
| **V101** | `fiscal_config_nfse` (por empresa: `emite_nfse`, ambiente, série, `enviar_im`, RBT12, anexo, alíquota efetiva, últimos testes) + `cfg_municipio_nfse` (por `codigo_ibge` × ambiente: convênio, `enviar_im`, **prazo de cancelamento** — o F10 aplicado ao município) | médio — RLS obrigatória em `fiscal_config_nfse`, e **não é automática** |
| **V102** | `nfse_documento`, `nfse_item`, `nfse_evento`, `nfse_numeracao` — **tabelas próprias (DS9)**, com a mesma *forma* de `documento_fiscal` e as colunas certas (`chave_acesso char(50)`, `nDPS`, `nNFSe`, ISS, competência) | médio |
| **V103** | `INSERT INTO cfg_tela` das telas novas | ⚠️ **`AcoesPorTelaConferemTest` reprova o build** se a ação declarada divergir do que o interceptor deriva — foi o que salvou o PDV de nascer sem "incluir" |

⚠️ **Duas armadilhas do repositório que valem aqui:** migration que **lê** dado de tenant sai vazia
em silêncio sob `FORCE RLS` (V057, V089→V090), e **migration aplicada nunca se edita** — o
`checksum mismatch` derruba o deploy e não reproduz na máquina de quem editou.

---

## 7. Contrato de API

```
# Cadastro tributário  (S5)
GET    /api/v1/servicos/lc116?busca=&ramo=            catálogo nacional (global)
PUT    /api/v1/produtos/{id}                          + bloco servico.fiscal{}

# Configuração  (S5.5)
GET    /api/v1/fiscal/nfse/configuracao?idEmpresa=
PUT    /api/v1/fiscal/nfse/configuracao
POST   /api/v1/fiscal/nfse/configuracao/testar-conexao
POST   /api/v1/fiscal/nfse/configuracao/verificar        ⭐ o assistente (§5.1)
GET    /api/v1/fiscal/nfse/municipio/{codigoIbge}/parametros?cTribNac=

# Emissão e ciclo de vida  (S6/S7)
POST   /api/v1/nfse/vendas/{idVenda}/emitir
GET    /api/v1/nfse/documentos?...
POST   /api/v1/nfse/documentos/{id}/cancelar             motivo 15–255 chars
GET    /api/v1/nfse/documentos/{id}/danfse               ⚠️ PDF em memória
GET    /api/v1/nfse/documentos/{id}/xml
```

⚠️ **`TenantContext` é `ScopedValue`** — o DANFSe **não pode** sair por `StreamingResponseBody`: o
corpo é escrito depois que o controller retorna, fora do escopo, e o `ArmazenamentoPrivado` monta o
prefixo `tenants/{id_tenant}/` a partir do contexto (P8). PDF de uma nota cabe em memória; o teto
por requisição é o que torna isso seguro.

---

## 8. Telas

| Tela | O que acontece |
|---|---|
| **Produtos** (aba de serviço) | ⚠️ **altera** — `cTribNac` com busca, alíquota, retenção, local de incidência |
| **Configuração da NFS-e** | 🆕 nova (§5.1), ADMIN-only, singleton por empresa |
| **Conformidade Fiscal** | ⚠️ **altera** — serviço sem `cTribNac`/alíquota, município não conveniado, IM indefinida |
| **Documentos Fiscais** | ⚠️ **altera** — aba de NFS-e, com a **pendente** visível e reprocessável (a consequência de tela da DS13) |
| **PDV / papeleta** | ⚠️ **altera** — emitir NFS-e como passo separado, pelo mesmo `cfg_emite_fiscal_apos_venda` |
| **Recibo de Serviço** | 🆕 nova — bobina 80 mm/42 colunas, ⛔ **sem chave, sem QR, sem "DANFSe"**, com o aviso de que não é documento fiscal |
| **Exportação de XML em Lote** | ⚠️ **altera** — o ZIP do contador leva a NFS-e |
| **Menu** | ⚠️ item novo — e ⛔ **apagar a rota placeholder e o item "Em construção"** se existirem (o erro que aconteceu duas vezes em 2026-08-26) |

⚠️ Toda tela nova precisa de `AjudaDaTela` (R22) e de spec própria em `docs/telas/`.

---

## 9. Armadilhas herdadas — a lista que os dois projetos pagaram para aprender

Cada uma destas custou uma sessão a alguém. Estão aqui para não custar de novo.

| Código / sintoma | O que significa de verdade | O que fazer |
|---|---|---|
| **`E0004`** | O `Id` não bate com a concatenação dos campos | Bug do montador (`nDPS` divergente) |
| **`E0008` / `E0015`** | Emissão posterior ao processamento / competência futura | Fuso do `dhEmi` (§2.3) e competência que **ainda não começou** |
| **`E0014`** | DPS **duplicada** | ⛔ **Não reenviar.** É o meio-termo perigoso: *timeout* depois de o SEFIN já ter gerado a nota. Recuperar com `GET /dps/{id}` |
| **`E0116`** | ⚠️ **NÃO é "IM ausente"** — é o CNC do município (§2.6). Sai com e sem IM, em qualquer formato | Nada a corrigir no XML; é cadastro da prefeitura naquele ambiente |
| **`E0120`** | IM presente onde o município não a quer (produção Curitiba) | Item 7 da §5 — bandeira por município × ambiente |
| **`E0712`** | *"Para ME/EPP o indicador de valor total de tributos não pode ser informado"* | ⭐ `indTotTrib` é **proibido** para `opSimpNac=3`; o campo tem de ser `pTotTribSN`. É o que torna a alíquota efetiva do Simples um **pré-requisito de emissão**, não um campo opcional |
| **`E0840`** | *"o evento de Cancelamento já está vinculado à NFS-e"* | ⭐ Cancelar duas vezes. Serve como **verificação** de que o primeiro cancelamento registrou |
| **`E0714`** | Erro na assinatura | ⭐ Serve de **teste de sabotagem**: se aparecer quando a assinatura deveria estar boa, o problema é o assinador, não o XML |
| **`E1229`** | *"Xml não está utilizando codificação UTF-8"* | ⚠️ Quase sempre é a **declaração XML ausente**, não o encoding: `OMIT_XML_DECLARATION=no` + `ENCODING=UTF-8` no `Transformer` |
| **`E1235`** | Falha no esquema XSD | ⭐ Vem com `Complemento` dizendo **qual elemento** falta — exibir sempre, senão a mensagem não aciona nada |
| **`E0121` / `E0128`** | `xNome` / endereço do prestador presentes | ⭐ Com `tpEmit=1`, **o SEFIN já tem os dados do emitente**: mandar dados redundantes é erro 400 |
| **`E0160`** | Situação do Simples na DPS ≠ cadastro real | Consultado na Receita — não adianta insistir |
| **`E0190`** | *"CNPJ do tomador não encontrado"* — mas pode significar **"não existia naquela competência"** | ⚠️ O caso do `finance-v`: 2.211 tentativas contra um cadastro **correto**. Antes de mexer no cliente, **comparar o XML de uma emissão que deu certo com o da que falhou** |
| **`E0240`** | CEP do tomador extinto | Item 10 da §5. **A NF-e passa e a NFS-e rejeita com o mesmo cadastro** |
| **`E0617`** | Alíquota informada para não optante | O campo é governado pelo **regime** — item 3 da §5 |
| **`E1226` / `E1235`** | gzip/base64 inválido / falha de esquema | Ordem dos elementos, enum inválido, campo a mais |
| **HTTP 5xx ou HTML** | ⚠️ **Não é rejeição — é indisponibilidade** | A DPS **não foi avaliada** e o número **não queimou**: reenviar com o **mesmo** número. Status `pendente`, tarja amarela — nunca `rejeitada` |
| **2.211 tentativas** | Fila sem teto contra erro permanente | ⭐ Teto de tentativas **obrigatório** no job (o `finance-v` levou ~350/dia por 13 dias antes de existir). E o teto tem de sobreviver ao fracasso — a lição de 2026-08-27: contador incrementado **dentro** da transação que a exceção desfaz **não conta nada** |
| **Ordem: assinar → empacotar** | Inverter quebra a assinatura | O gzip altera os bytes do XML |
| **HTTP/2** | `RST_STREAM: Use HTTP/1.1` | `.version(HttpClient.Version.HTTP_1_1)` |

⚠️ **E a armadilha que é do Nainer, não deles:** *"corrigir uma ponta e não varrer as outras"* é o
defeito mais comum medido neste projeto. A NFS-e cria um **segundo** documento fiscal por venda —
antes de dar qualquer coisa por pronta, `grep` por **todos os leitores** de "a nota da venda":
papeleta, Documentos Fiscais, exportação, cancelamento de venda, devolução e o histórico do cliente.

---

## 10. Riscos e o que continua aberto

**R1 — ⚠️ O tamanho continua sendo o risco principal, e o `finance-v` engana.** Ele fez o ciclo
completo em ~25 h efetivas, e isso é verdade — para **um** tenant, **um** município, **um** serviço,
com a nota nascendo de um `receivable` que já tinha valor e competência. No Nainer é **multi-tenant,
por empresa, por município, com catálogo de serviços e venda mista**. O que se copia são as ~2.650
linhas de infraestrutura (assinatura, transporte, empacotamento, IDs, evento) — que é a parte
**difícil e perigosa**, mas é minoria do trabalho. O domínio é nosso.

**R2 — ⚠️ Compromisso permanente em dobro** (do `MODULOSERVICOS.md` §7.7, e continua valendo): dois
emissores, dois calendários de Nota Técnica, duas homologações. ⭐ O que mudou é que **a Vetor já
paga esse custo** no `finance-v` e no `workshop` — o que **não** significa que o Nainer o herda de
graça: significa que existe quem já sabe ler as NTs.

**R3 — ⚠️ Homologação bloqueada em Curitiba** (§2.2). O primeiro emissão de verdade tende a ser em
**produção**, com nota de valor mínimo e cancelamento no mesmo dia. Isso é um **procedimento**, não
um acidente — e precisa estar escrito antes de alguém tentar.

**R4 — ⚠️ NT 008 / DANFSe.** Não construir nada em cima da API antiga sem confirmar (§2.3 item 3).

**R5 — ⛔ Prometer antes de existir.** Vale a lição do `MODULOSERVICOS.md` R8: **01/11/2026** é a
data em que ME/EPP do Simples passa a ser obrigada ao Emissor Nacional. Um lojista ME que escolher
"só papeleta" depois dela está irregular — a escolha tem de ser **informada na tela**, não
silenciosa.

### 🔴 Para o dono do produto

1. **O CNPJ da Vetor (22120254000186) pode ser o tenant de teste do Nainer?** É o único que já emite
   NFS-e em produção, e ele responde os três fatos que faltavam (regime, município, IM). Se sim, o
   R4 do `MODULOSERVICOS.md` fecha hoje.
2. **A primeira emissão pode ser em produção, com nota de R$ 1 cancelada no mesmo dia?** É o que a
   homologação bloqueada por Curitiba impõe (§2.2), e é o que o `finance-v` fez.
3. **Confirmação do que já foi decidido:** a NFS-e sai **depois da papeleta**, por decisão do
   usuário (DS13), e o lojista pode operar **só com a papeleta** (P5). Este documento assume as
   duas.

---

## Anexo — onde está cada coisa nos projetos de referência

| Preciso de | Arquivo |
|---|---|
| Montagem da DPS (com os comentários do que cada campo custou) | `workshop/api/src/modules/nfse-emissao/nfse-nacional/dps-builder.ts` · `finance-v/.../service/nfse/DpsXmlBuilder.java` |
| Assinatura XMLDSig (JSR 105, 80 linhas) | `finance-v/.../service/nfse/DpsXmlSigner.java` |
| mTLS + cache por certificado | `finance-v/.../service/nfse/Pkcs12HttpClientFactory.java` |
| `Id` da DPS (45 chars, com as validações) | `finance-v/.../service/nfse/DpsIdBuilder.java` |
| Evento de cancelamento 101101 | `finance-v/.../service/nfse/EventoCancelamentoBuilder.java` |
| Leitura de erro / rejeição × indisponibilidade | `workshop/.../nfse-nacional/nfse-client.ts` (`descreverErros`, `falhaComunicacao`) |
| Anexos III e V do Simples | `finance-v/.../service/nfse/SimplesPartilhaTable.java` |
| Mapa do layout, endpoints e códigos de erro | `finance-v/docs/fiscal/nfse-nacional/MAPA.md` |
| Guia de replicação (checklist §13) | `finance-v/docs/fiscal/nfse-nacional/IMPLEMENTACAO_FINANCE_V.md` |
| Como ler uma rejeição | `workshop/docs/nfse-rejeicoes.md` |
| CEP extinto (E0240) | `workshop/docs/ceps-extintos-nfse-e0240.md` |
| E0190 e a competência | `finance-v/docs/nfse-conforto-fitness-e0190.md` |
| XSDs oficiais (DPS 1.00, NFSe 1.01, eventos) | `workshop/nfse-xsd/Schemas/` |
| Manual da API + anexos oficiais | `finance-v/docs/fiscal/nfse-nacional/` |
