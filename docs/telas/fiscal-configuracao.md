# Spec: Configuração Fiscal da Empresa (fiscal_config_empresa)      Status: Rascunho
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-17 · Módulo(s): `fiscal.configuracao` · Fase: F1 — Fundação (bloco B2)

## Problema

`fiscal_config_empresa` (V035) guarda tudo que decide **como uma empresa emite nota**: regime
tributário (CRT), série e numeração, ambiente (homologação × produção), CSC do
credenciamento e os dois gates `emite_nfce`/`emite_nfe`. Diferente de `cfg_geral`, **o signup não
semeia essa linha** — um tenant recém-assinado não tem configuração fiscal nenhuma, e é assim que o
F12 se cumpre ("fiscal desligado não muda o ERP").

Sem esta tela não há como ligar o fiscal, e nenhum dos blocos seguintes (motor, XML, PDV) tem de
onde ler o CRT do emitente.

## Solução proposta

Tela de **configuração singleton por EMPRESA** — não por tenant. É uma variante nova no projeto:
`configuracao.geral` é singleton por tenant (`id_tenant` é a PK), aqui a unidade é a empresa
(`fiscal_config_empresa_uk UNIQUE (id_tenant, id_empresa)`). Um tenant com matriz e duas filiais tem
**três** configurações fiscais independentes, e isso é deliberado: cada loja tem IE, série e
numeração próprias, e o ambiente é por empresa — senão uma filial em teste derrubaria a nota fiscal
da outra.

**Acesso: somente ADMIN**, leitura e escrita — mesma sensibilidade de `configuracao.geral`. Item de
menu com `adminOnly` em `web/src/lib/menu.ts`.

**Seletor de empresa no topo da tela** (não um popup de entrada): a tela carrega já na empresa ativa
da sessão (claim `eid`) e o ADMIN troca por um `<select>` das empresas do tenant. Trocar a empresa
recarrega o formulário inteiro — é outra linha, não outro filtro.

## Particularidades estruturais

1. **A linha pode não existir.** Diferente de `cfg_geral`, que o signup sempre insere, aqui a
   ausência é o estado inicial normal. `GET` numa empresa sem configuração responde **200** com os
   defaults do banco e `configurado: false` — não 404. O formulário renderiza normalmente e o
   primeiro `PUT` **cria** a linha (upsert). Responder 404 forçaria a tela a distinguir "empresa
   inexistente" de "fiscal ainda não configurado", que é ruído sem ganho.
2. **`csc_token_cifrado` é write-only.** O `GET` devolve apenas `cscConfigurado: true|false` e nunca
   o token, mesmo para ADMIN — mesma regra do certificado (F7). Gravar string vazia **não** apaga o
   token; para remover existe um campo explícito `removerCsc: true`. Sem isso, um `PUT` de outro
   campo qualquer zeraria o CSC silenciosamente.
3. **Os dois gates são o coração da tela.** `emite_nfce` e `emite_nfe` não são checkboxes comuns:
   ligá-los é o momento em que o F11 ("bloqueio preventivo, nunca rejeição no caixa") se aplica —
   ver a seção *Regra de ativação* abaixo.
4. **`ambiente` tem aviso visual permanente.** Em `HOMOLOGACAO`, a tela exibe uma tarja fixa
   "AMBIENTE DE HOMOLOGAÇÃO — NOTAS SEM VALOR FISCAL". Nota de homologação não vale nada e já causou
   lojista achando que estava emitindo (§9.6 do estudo).
   ⭐ **E a escolha de ambiente some quando a instalação estiver em produção** (decisão do dono do
   produto, 2026-08-27): *"quando o sistema estiver em produção, o sistema de emissão de notas
   fiscais não deverá ter a opção homologação ou produção — sempre vai ter que estar em produção,
   travado nisso"*. A trava é `niner.fiscal.ambiente-fixo` (env `NINER_FISCAL_AMBIENTE_FIXO`), hoje
   **vazia** porque o Nainer está homologando junto às SEFAZ dos estados; no go-live basta
   `NINER_FISCAL_AMBIENTE_FIXO=PRODUCAO`. Com ela ligada, o `GET` devolve `ambienteTravado: true`,
   a tela **esconde o campo** (não o desabilita — campo cinza convida a perguntar como se habilita,
   e a resposta é que não se habilita) e o `PUT` **sobrescreve** o que vier em vez de recusar, para
   não travar a edição dos outros campos de quem tinha HOMOLOGACAO gravado antes da virada.

   ⚠️ **Por que travar isto importa mais do que parece** (achado da auditoria de segurança de
   2026-08-27): a série já era imutável depois da primeira nota autorizada, o ambiente **não era**.
   Trocá-lo faz as vendas seguintes saírem com `tpAmb=2` — sem valor jurídico — **enquanto o PDV
   segue dizendo "Nota autorizada"**. E `fiscal_numeracao` tem PK `(tenant, empresa, modelo, série)`,
   **sem ambiente**: as notas de teste consomem números da sequência de produção e abrem buracos que
   depois exigem inutilização formal. Testes: `AmbienteFiscalTravadoTest`.
5. **Sem `cfg_tela_campo`.** Não há campo configurável por tenant aqui — a obrigatoriedade é
   determinada pelo regime e pelos gates, não por preferência do lojista.
6. **`InfoRegistro` normal** — a tabela tem `criado_em` e `atualizado_em`, então o bloco de auditoria
   padrão (`InfoRegistro.tsx`) aparece no fim do formulário, ao contrário de `configuracao.geral`.

## Campos do formulário

Quatro seções: **Regime**, **Emissão**, **Numeração** e **Credenciamento**.

### Regime

| Campo (banco) | Rótulo | Componente | Regra |
|---|---|---|---|
| `crt` | Regime Tributário (CRT) | `<select>` | **1** Simples · **2** Simples com excesso de sublimite · **4** MEI. Obrigatório. **O 3 não aparece na lista** |
| `inscricao_estadual_st` | IE de Substituto Tributário (outra UF) | texto | Opcional |
| `suframa` | Inscrição SUFRAMA | texto | Opcional, sem regra de motor no v1 |

**O `<select>` de CRT tem três opções, não quatro (DF37).** O Nainer atende Simples Nacional e MEI;
Lucro Real e Lucro Presumido estão fora do produto. O CRT 3 é recusado em três camadas — não é
excesso de zelo, é o custo de errar: uma empresa do Lucro Presumido operando como CRT 1 emitiria
**toda** nota com CSOSN e PIS/COFINS zerado, e a nota seria autorizada normalmente. O erro só
apareceria na contabilidade.

| Camada | O que faz |
|---|---|
| Tela | O 3 não existe no `<select>` |
| Serviço | 400 com mensagem de **escopo** (`"CRT 3 fora do escopo do produto…"`), não "não suportado" |
| Banco | `CHECK (crt IN (1, 2, 4))` em `fiscal_config_empresa` |

A mensagem importa: quem lê *"não suportado"* entende "ainda não" e procura um jeito de contornar.
Quem lê *"Lucro Real e Lucro Presumido não são atendidos"* procura outro ERP — que é o resultado
correto.

> **Sumiram desta seção (DF37):** `regime_apuracao` (com Real e Presumido fora, o CRT já diz tudo, e
> uma coluna que só aceita um valor é armadilha) e `equiparado_industrial` (optante do Simples
> recolhe IPI dentro do DAS e não destaca na saída — o flag não teria como ficar `true`).

### Emissão

| Campo (banco) | Rótulo | Componente | Regra |
|---|---|---|---|
| `emite_nfce` | Emitir NFC-e (modelo 65) no PDV | checkbox | Ver *Regra de ativação* |
| `emite_nfe` | Emitir NF-e (modelo 55) de devolução | checkbox | Ver *Regra de ativação* |
| `ambiente` | Ambiente | `<select>` | HOMOLOGACAO (default) · PRODUCAO |

### Numeração

| Campo (banco) | Rótulo | Componente | Regra |
|---|---|---|---|
| `serie_nfce` | Série da NFC-e | inteiro | ≥ 1, default 1 |
| `serie_nfe` | Série da NF-e | inteiro | ≥ 1, default 1 |
| `serie_contingencia` | Série de contingência | inteiro | ≥ 1, default 9 (DF33). **Tem que ser diferente** de `serie_nfce` — 400 se igual |

**A série é imutável depois da primeira nota autorizada naquela série.** Trocar a série com notas já
emitidas quebraria a sequência sem buraco exigida pelo F4. Assim que existir uma linha em
`fiscal_numeracao` para o par (empresa, modelo), o campo vira somente-leitura na tela e o servidor
recusa a alteração com 409 explicando por quê.

### Credenciamento

| Campo (banco) | Rótulo | Componente | Regra |
|---|---|---|---|
| `csc_id` | Identificador do CSC (CSC ID) | texto | Opcional; ver nota abaixo |
| `csc_token_cifrado` | Token do CSC | senha (write-only) | Nunca devolvido pelo `GET` |
| `versao_tabela_ibpt` | Versão da tabela IBPT | texto somente-leitura | Rastreabilidade F9; preenchido pela rotina de carga, não digitado |

⚠️ **O CSC é dado de credenciamento, não de montagem.** Com o QR Code v3.00 (DF17) o CSC saiu do
cálculo do QR, mas **não foi extinto** — o portal do PR ainda o exige no credenciamento. Está aqui
por isso, e o campo é opcional até a F0 confirmar no MOC-PR se segue exigido.

Campos **não** editáveis nesta tela, apesar de estarem na mesma tabela: `contingencia_ativa`,
`contingencia_desde`, `contingencia_justificativa` (donos: `fiscal.contingencia`) e
`opcao_transferencia_tributada`/`ano_opcao_transferencia` (a transferência é implementação futura,
§4.2 — os campos existem no schema mas nenhuma tela os escreve no v1).

## Regra de ativação (F11) — a parte que não pode ser um checkbox comum

Ligar `emite_nfce` ou `emite_nfe` é o momento em que o sistema **impede antes** em vez de deixar a
rejeição chegar no caixa. O `PUT` que tenta ligar um gate valida, **no servidor**, e responde 409 com
a lista do que falta:

| Precondição | Onde é conferida |
|---|---|
| Empresa com CNPJ preenchido e válido | `empresa.cnpj` (validação alfanumérica de `Documentos.java`) |
| Empresa com Inscrição Estadual preenchida | `empresa.inscricao_estadual` |
| Empresa com código de município IBGE | `empresa.codigo_municipio_ibge` |
| Empresa com CNAE | `empresa.cnae` |
| Certificado A1 ativo e dentro da validade | `fiscal_certificado` (ver `fiscal-certificado.md`) |
| CNPJ do certificado igual ao da empresa | `fiscal_certificado.cnpj_titular` |
| `crt` entre os atendidos (1, 2 ou 4 — DF37) | esta tela |

A resposta 409 **lista o que falta e manda para a Conformidade Fiscal** (`fiscal.conformidade`), que
é a tela desenhada para mostrar isso item a item — esta aqui não vira um relatório de pendências.

**Desligar um gate nunca é bloqueado.** Se o lojista quer parar de emitir, ele para; documento já
autorizado não é afetado (F6).

## Critérios de aceitação (viram testes)

- Dado uma empresa sem configuração fiscal, quando consulta `GET /api/v1/fiscal/config/{idEmpresa}`,
  então recebe 200 com os defaults do banco e `configurado: false`.
- Dado uma empresa sem configuração, quando o ADMIN salva pela primeira vez, então a linha é criada
  e o `GET` seguinte traz `configurado: true`.
- Dado um OPERADOR, quando tenta ler ou gravar, então 403 nos dois casos.
- Dado `crt = 3`, quando salva, então 400 com `detail` contendo "fora do escopo" (DF37).
- Dado `crt = 4` (MEI), quando salva, então 200 e o `GET` seguinte traz `crt: 4`.
- Dado `serie_contingencia` igual a `serie_nfce`, quando salva, então 400.
- Dado um CSC já gravado, quando salva sem informar o token, então o token anterior **permanece**
  (não é apagado) e `cscConfigurado` segue `true`.
- Dado um CSC já gravado, quando salva com `removerCsc: true`, então `cscConfigurado` vira `false`.
- Dado uma empresa sem certificado ativo, quando tenta ligar `emite_nfce`, então 409 listando a
  pendência, e o gate **permanece desligado** no banco.
- Dado uma empresa com todas as preconditions atendidas, quando liga `emite_nfce`, então 200 e o
  gate fica ligado.
- Dado uma série com nota já autorizada, quando tenta alterá-la, então 409.
- Dado dois tenants distintos, quando um configura sua empresa, então o outro não enxerga nem é
  afetado (isolamento — `id_tenant` explícito, P8/F8).
- Dado duas empresas do **mesmo** tenant, quando uma liga `emite_nfce`, então a outra permanece
  desligada (a configuração é por empresa, não por tenant).

Cobertos por `FiscalConfigCrudTest` (novo).

## Impacto no contrato de API

```
GET  /api/v1/fiscal/config/{idEmpresa}     lê a configuração (ADMIN). 200 com defaults se não existir
PUT  /api/v1/fiscal/config/{idEmpresa}     cria ou atualiza (ADMIN). 409 se um gate não pode ligar
GET  /api/v1/fiscal/config/empresas        empresas do tenant + se cada uma tem fiscal ligado (ADMIN)
```

Erros como Problem Details (RFC 9457) com `detail` **sempre preenchido** — o
`ResponseStatusException` sem corpo já mordeu o projeto em 2026-08-11
([[feedback_jackson_record_primitivo_e_problemdetails]]). Todo campo opcional do record de request é
boxed (`Boolean`/`Integer`), nunca primitivo, pelo mesmo motivo.

Toda query filtra `id_tenant = plataforma.tenant_atual()` **explicitamente no texto do SQL**, além do
RLS (P8/F8) — inclusive o lookup por `id_empresa` vindo do path e o `EXISTS` do certificado.

## Ajuda da tela (R22 / §3.7.1)

Entrada `fiscal.configuracao.form` em `AjudaDaTela.tsx`, cobrindo: o que é o CRT e por que só existem
três opções (DF37 — o produto não atende Lucro Real nem Presumido), o que muda entre homologação e produção, por que a série não
pode ser alterada depois da primeira nota, e o que fazer quando o gate recusa ligar.

## Impacto no banco

**Nenhuma migration nova.** `fiscal_config_empresa` já existe (V035:44-79) com todas as colunas
usadas aqui. As colunas de `empresa` que a regra de ativação confere (`codigo_municipio_ibge`,
`cnae`, `inscricao_municipal`, `tipo_estabelecimento`) já entraram em V014.

## Non-goals desta feature

- **Contingência** — os três campos (`contingencia_*`) são desta tabela mas desta tela não: donos em
  `fiscal.contingencia` (F3/B7).
- **Upload do certificado** — tela própria (`fiscal-certificado.md`); aqui só se confere que existe.
- **Transferência tributada** (`opcao_transferencia_tributada`) — a operação é futura (§4.2).
- **Listar pendências de cadastro** — é a Conformidade Fiscal (`fiscal-conformidade.md`); aqui a 409
  só aponta para lá.

## Questões abertas

- 🔴 **O CSC continua exigido no credenciamento do PR com QR v3.00?** Item da F0 (leitura do MOC-PR).
  Se não for, o campo some da tela; enquanto não se sabe, fica opcional.
- 🔴 **MEI (CRT 4) é cenário real deste produto?** A DF37 tornou a pergunta *mais* relevante, não
  menos: o MEI agora é um dos três regimes atendidos, e é dispensado de emitir nota a consumidor
  pessoa física. Se na prática nenhum MEI emite, o CRT 4 vira código sem uso — e se emite, é o caso
  mais sensível (limite de faturamento baixo, lojista sem contador). Confirmar com o contador do
  piloto **o que o MEI realmente precisa emitir** antes da F2.

## Métrica de sucesso

Um lojista consegue ligar a NFC-e sem abrir a documentação, e nenhuma nota é rejeitada pela SEFAZ por
dado de cadastro do emitente — as pendências aparecem aqui ou na Conformidade, nunca no caixa.
