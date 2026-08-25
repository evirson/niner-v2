# Chamado SEFAZ/PR — `cStat 974` com CSRT ativo e válido (homologação)

> Texto pronto para abrir o chamado. Gerado em 25/08/2026 a partir dos registros reais de
> transmissão. **Não contém o CSRT** — só o identificador, que é público e vai no XML em claro.

---

## Assunto

NF-e modelo 55 rejeitada com `cStat 974` em homologação, com CSRT ativo e CNPJ conferido

## Identificação

- **Razão social:** MITRYUSCASH LTDA
- **CNPJ:** 37.829.453/0001-35
- **Inscrição Estadual:** 91227931-65
- **Situação no credenciamento de fornecedor:** Credenciado
- **Sistema:** NAINER — código 79413, sigla NAINER
- **Ambiente:** Homologação (`tpAmb=2`)

## Descrição do problema

Toda tentativa de autorização de **NF-e modelo 55** em homologação é rejeitada com:

```
cStat 974 — "CNPJ do responsavel tecnico diverge do cadastrado"
verAplic: PR-v4_9_87-2
```

O CNPJ enviado no grupo `infRespTec` é **37829453000135**, exatamente o mesmo que consta como
credenciado no portal Receita/PR (tela "Solicitação de Token - Fornecedor") e o mesmo do emitente.

A **NFC-e modelo 65** do mesmo emitente, no mesmo ambiente, **autoriza normalmente** — ela não exige
o grupo CSRT no PR, o que isola o problema no cadastro do responsável técnico.

## Evidências (todas em homologação)

| Data/hora | Modelo | Chave de acesso | Resultado |
|---|---|---|---|
| 24/08/2026 17:01:27 | 65 | 41260837829453000135650010000000511900767341 | ✅ `cStat 100` — protocolo 141260001550802 (`verAplic PR-v4_5_39`) |
| 25/08/2026 08:14:33 | 55 | 41260837829453000135550010000000171883990454 | ❌ `cStat 974` — token id 1 |
| 25/08/2026 08:55:06 | 55 | 41260837829453000135550010000000181434210734 | ❌ `cStat 974` — token id 1 |
| 25/08/2026 09:18:59 | 55 | 41260837829453000135550010000000191304925986 | ❌ `cStat 974` — **token id 2, ativado minutos antes** |

Grupo `infRespTec` efetivamente transmitido na última tentativa (hash preservado, CSRT omitido):

```xml
<infRespTec>
  <CNPJ>37829453000135</CNPJ>
  <xContato>MITRYUSCASH</xContato>
  <email>suporte@nainer.com.br</email>
  <fone>4133334444</fone>
  <idCSRT>02</idCSRT>
  <hashCSRT>Ceb8aoRdgvvQjwtF9n5rwudlv68=</hashCSRT>
</infRespTec>
```

## O que já foi verificado e descartado

1. **CNPJ do responsável técnico** — `37829453000135`, idêntico ao credenciado no portal e ao do
   emitente (conferido na tela de credenciamento).
2. **Identificador do token (`idCSRT`)** — o portal exibe "Id do Token" 1 e, depois, 2; enviados
   como `01` e `02`, no formato de 2 dígitos exigido pelo XSD (`<xs:pattern value="[0-9]{2}"/>`).
3. **Ambiente** — o portal só possui tokens de **homologação** (não há token de produção emitido) e
   todas as transmissões usaram `tpAmb=2`.
4. **Situação do token** — ATIVO, conforme o portal.
5. **Valor do CSRT** — conferido caractere a caractere contra o portal; 36 caracteres.
6. **Cálculo do `hashCSRT`** — SHA-1 do digest binário de `CSRT + chave de acesso`, codificado em
   Base-64 (28 caracteres), conforme a NT 2018.005.
7. **Token novo** — foi solicitado um **segundo** token de homologação (id 2) e a rejeição
   permaneceu idêntica, em transmissão feita minutos após a ativação.

## Pergunta / solicitação

O credenciamento aparece ativo no portal Receita/PR, mas o autorizador de NF-e (`PR-v4_9_87-2`) não
reconhece o CNPJ 37.829.453/0001-35 como responsável técnico. Solicitamos verificação de
**sincronismo entre o cadastro de fornecedor/CSRT do portal e a base de validação do autorizador de
NF-e em homologação**, uma vez que:

- um token recém-ativado apresenta o mesmo comportamento do anterior;
- a rejeição ocorre antes de qualquer validação do hash (não há `cStat 976`), sugerindo que a
  consulta ao par (CNPJ, `idCSRT`) não localiza o registro.

Podemos fornecer o XML assinado completo de qualquer uma das tentativas acima, se necessário.
