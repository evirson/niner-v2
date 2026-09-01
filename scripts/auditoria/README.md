# Scripts de auditoria

Duas verificações que **nenhuma ferramenta do projeto faz** — nem o `tsc -b`, nem a suíte, nem o
build. As duas nasceram da pendência **#68** (`docs/PENDENCIAS.md`), que declarava o limite da
auditoria de 2026-08-29: *"Fechar isso pede um script comparando os dois lados, que não existe"*.

Rode da **raiz do repositório**:

```bash
node scripts/auditoria/classes-css-orfas.js
node scripts/auditoria/contrato-ts-java.js
```

## `classes-css-orfas.js` — classe CSS que não existe

Compara as classes usadas em `web/src/**/*.tsx` com as declaradas em `web/src/**/*.css`.
**Classe inexistente não dá erro em lugar nenhum** — o elemento só perde o estilo, e o PDF dos
relatórios é captura visual, então o arquivo do contador sai igual ao defeito. Já achou os KPIs
empilhados do Relatório de Contas a Pagar, o seletor Mensal/Anual idêntico nos dois estados da
Minha Conta e a NF-e rejeitada saindo com a mesma cor da autorizada.

⚠️ **O que ele NÃO pega, e por quê:**
- Classes montadas por interpolação (`` className={`uso-${x}`} ``) — ele **lista** essas ocorrências
  numa segunda seção, para conferência humana, mas não resolve o valor.
- Classes declaradas em `<style>` **injetado em tempo de execução** — é o caso das amarrações de
  `@page` nomeado do Orçamento e da Ordem de Serviço, que aparecem como falso positivo.

## `contrato-ts-java.js` — campo que o TS declara e o Java não manda

As interfaces de `web/src/lib/*.ts` são **escritas à mão**: um campo que não existe (ou deixou de
existir) no record Java vira `undefined` na tela **sem erro de compilação**, porque `api<T>()`
acredita na assinatura. O script extrai os componentes de todo `record` de `api/src/main` (com
balanceamento de parênteses, para não quebrar dentro de anotação) e casa por nome, tentando também
os sufixos `Response`/`Request`/`Dto`.

Achou `UsaServicos.cfgExigeSangriaFechamento` em 2026-08-30: declarado no TS e **nunca enviado**
pelo endpoint — quem o lesse receberia `undefined`, que é falsy, ou seja *"não exige sangria"*, o
**oposto** do padrão (o parâmetro nasce ligado, V095).

⚠️ **Limites conhecidos:**
- Casa por **nome**; DTO cujo nome no TS não bate com nenhuma das variantes fica de fora.
- Objeto **aninhado** dentro de uma interface (`itens: Array<{...}>`) gera falso positivo — o corpo
  aninhado é lido como se fosse do tipo de fora.
- Compara só numa direção: campo que o **Java manda e o TS ignora** é inofensivo e não é reportado.

⛔ **Nenhum dos dois substitui abrir a tela.** Os três defeitos de CSS de 2026-08-29 escaparam de
dez varreduras de código e seriam pegos em dois minutos de navegador.

## `contagem-de-telas.js` (2026-09-01, pendência #79)

Reconcilia as **três** bases de contagem de telas — `docs/TELAS.md`, `web/src/App.tsx` e
`cfg_tela` — e **lista as diferenças item a item, com nome de rota**. Sai com código 1 quando
diverge. Ver o cabeçalho do arquivo para o motivo de cada base medir coisa diferente.

```bash
docker exec -i niner-db psql -U niner_owner -d niner_db -tAc \
  "SELECT chave FROM cfg_tela ORDER BY chave;" > "$TEMP/cfg_tela.txt"
node scripts/auditoria/contagem-de-telas.js
```

⚠️ O guarda que **reprova o build** é o `ContagemDeTelasConfereTest`; o script é a ferramenta de
diagnóstico que diz *o quê* divergiu.

## `contrato-ts-java.js` — reescrito em 2026-09-01

Passou a casar por **ENDPOINT** (URL + método HTTP), não por nome de tipo. O motivo está no
cabeçalho: existem dois `ResultadoEmissao` (NFC-e e NFS-e), e com 558 records o homônimo é a regra
— a versão antiga comparava um com o outro e errou **4 de 4** achados.

⛔ **Não reprova build de propósito**, e declara os limites: não compara *tipo* de campo, não
alcança DTO montado como `Map<String,Object>`, não pega campo que mudou de *significado*, e não
resolve rota montada por concatenação. `--verboso` lista o que ficou sem resolver.
