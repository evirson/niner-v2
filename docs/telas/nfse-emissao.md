# Spec: NFS-e — emissão, listagem e cancelamento      Status: Parcial (falta o PDV)
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-31 · Módulo: `fiscal.nfse` · Blocos: S6/S7

> ⚠️ **Escrita depois da implementação** — mesma dívida registrada em `nfse-configuracao.md`.
> ⚠️ **E está PARCIAL de propósito:** a ação de emitir a partir do PDV (bloco 4) e o Recibo de
> Serviço (bloco 5) **não foram construídos**. O que está descrito aqui é o que existe.

## O fato que governa esta tela

⭐ **A DPS carrega UM código de serviço.** Medido no leiaute oficial: `serv`, `cServ`, `xDescServ` e
`vServ` são todos `1-1`. Logo:

> **Uma NFS-e por código de serviço distinto da venda.**

Uma venda de petshop com banho e tosa (`050801`) e consulta veterinária (`050101`) gera **duas**
notas. ⚠️ Isso **quebra aqui** a invariante "uma nota por venda" que a V082 estabeleceu para a
NF-e/NFC-e — e quem ler as duas telas esperando a mesma cardinalidade se engana. A alternativa
(somar tudo num código "dominante") declararia serviço errado para parte do valor, com alíquota e
local de incidência errados junto.

## Onde a NFS-e aparece

**Aba própria em Documentos Fiscais** (`/fiscal/documentos`), ao lado de NF-e/NFC-e.

⚠️ **Aba, não um valor a mais no filtro de modelo:** vem de outro endpoint, tem outras colunas e
outra cardinalidade. Espremer as duas na mesma tabela deixaria metade das colunas vazia — o mesmo
motivo pelo qual a DS9 recusou juntá-las no banco.

⭐ **A listagem vem ordenada com pendente e rejeitada em cima.** É a consequência de tela da DS13:
nota que ninguém emitiu por esquecimento é pior que nota que falhou, porque não aparece em lugar
nenhum. Ordenar por data enterraria a pendente de ontem sob as autorizadas de hoje.

## Como a nota é chamada

| Situação | Nome na tela |
|---|---|
| Autorizada | `Nº 7308` — o `nNFSe`, número **da prefeitura** |
| Qualquer outra | `DPS 12` — o nosso sequencial |

⛔ Antes de autorizada **não existe número oficial**, e mostrar algo que imite numeração fiscal foi
o defeito que o `workshop` registrou (a mesma nota aparecendo com dois nomes).

## Situações e o que cada tarja significa

| Situação | Tarja | O que o operador faz |
|---|---|---|
| `AUTORIZADA` | verde | nada |
| `REJEITADA` | vermelha | **corrige o dado apontado** e reenvia |
| `RASCUNHO`/`ASSINADA`/`TRANSMITINDO` | amarela | **espera e reenvia** — não foi avaliada, o número não queimou |
| `CANCELADA`/`NAO_EMITIDA` | cinza | — |

⚠️ **A diferença entre amarelo e vermelho é a mais importante do módulo.** Tratar
indisponibilidade como rejeição faz a nota sumir da fila; o contrário faz o sistema martelar um
erro permanente — foi assim que o `finance-v` chegou a 2.211 tentativas contra o mesmo `E0190`.

## Mensagem de erro

O motivo é gravado como `"E0240 — <descrição do Sefin>"`, **com o código no início**. ⚠️ O
`codigo_status` é **HTTP** (400 para toda recusa) e nunca identifica a causa. E no `E1235` (falha de
schema) o campo `Complemento` — que diz **qual elemento** faltou — vai junto: sem ele a mensagem é
"falha no esquema XML", que não aciona nada.

## Cancelamento (evento 101101)

`DELETE /api/v1/nfse/{id}` — ⚠️ o verbo é `DELETE` porque a ação é *excluir* no RBAC, mas **nada é
apagado**: a nota muda de situação e o evento fica registrado para sempre (F6; a V102 não dá GRANT
de DELETE nessas tabelas).

| Campo | Regra |
|---|---|
| `codigoMotivo` | **1** erro na emissão · **2** serviço não prestado · **9** outros (Anexo II — não há outros) |
| `motivo` | **15 a 255 caracteres**, validado na tela com contador |

⭐ **`E0840` é tratado como sucesso.** Cancelar duas vezes devolve *"o evento já está vinculado à
NFS-e"* — que é o desfecho que o usuário queria. É também como o teste prova que o primeiro
cancelamento **ficou**, e não só que foi aceito.

⚠️ **O prazo avisa, não bloqueia.** É competência municipal (24 h em Curitiba, 5 dias no padrão
nacional), pode ser desconhecido, e mesmo conhecido quem decide se aceita fora do prazo é a
prefeitura. Barrar aqui inventaria uma regra nossa em cima da dela.

## Contrato de API

```
POST   /api/v1/nfse/vendas/{idVenda}/emitir     → LISTA (uma por código de serviço)
GET    /api/v1/nfse?idEmpresa=&de=&ate=&situacao=&pagina=
GET    /api/v1/nfse/vendas/{idVenda}
GET    /api/v1/nfse/{id}
DELETE /api/v1/nfse/{id}                        { codigoMotivo, motivo }
GET    /api/v1/nfse/{id}/xml
```

⚠️ **O XML é servido em memória, nunca por `StreamingResponseBody`:** o `TenantContext` é um
`ScopedValue` e o corpo de um streaming é escrito depois que o controller retorna, fora do escopo —
o `ArmazenamentoPrivado` confere o prefixo do tenant a partir dele (P8).

## Bloqueios preventivos (F11) — antes de consumir número

Empresa sem CNPJ · sem código IBGE · emissão desligada · **sem alíquota do Simples** (citando o
`E0712` e o PGDAS-D) · **serviço sem código LC 116** (nomeando o serviço). Todos 409, todos antes de
qualquer número ser reservado — provado por teste que confere que `nfse_numeracao` **não avançou**.

## O que ainda NÃO existe

- ⬜ **Ação de emitir no PDV** (bloco 4). Hoje a emissão só é alcançável pela API. A DS13 exige que
  ela seja um passo separado do fechamento, pelo mesmo `cfg_emite_fiscal_apos_venda` da NFC-e.
- ⬜ **Recibo de Serviço** (bloco 5), bobina 80 mm/42 colunas. ⛔ Sem chave, sem QR, sem "DANFSe",
  com o aviso de que não é documento fiscal.
- ⬜ **DANFSe** (o PDF da nota). ⚠️ A NT 008 está descontinuando a API antiga — não construir sobre
  ela sem confirmar.
- ⬜ NFS-e na **Exportação de XML em Lote** e na **Conformidade Fiscal**.
- ⬜ `AjudaDaTela` da aba de NFS-e.

## Testes

`NfseEmissaoIntegracaoTest` — 7 testes cobrindo as duas notas por venda (conferindo o **banco**), a
não duplicação, o cancelamento com o evento gravado, o motivo curto e os três bloqueios do F11.
Rodam pelo `EmissorFalso`, que é o padrão da suíte.
