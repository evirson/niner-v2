# Spec: Ramo de atividade da empresa               Status: Aprovada (parte 1 de 2 — a parte 2 está em contratacao-grupo.md)
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-27 · Módulo(s): `comum.ramo`, `identidade.empresa`, `plataforma.onboarding` · Fase: 1 — Núcleo do ERP

## Problema

O dono do produto descreveu assim: um cliente tem **3 empresas de calçados no mesmo tenant** e
resolve contratar uma quarta, de outro ramo. Ele pode pôr a nova no mesmo tenant ou num tenant
novo — e não tem como saber sozinho qual das duas escolhas o prejudica.

> *"Pro usuário não se perder na hora da contratação, acho que temos que perguntar o ramo de
> atividade, e verificar se o tenant que ele usa está neste ramo; se não estiver, perguntar se ele
> quer um novo tenant ou se vai continuar no mesmo — e avisar dos impactos dos dois lados."*

Para o sistema conseguir **detectar** que essa pergunta é necessária, o ramo precisa estar gravado.
Hoje não está: `empresa` tem só o `cnae`, texto livre digitado à mão.

## Escopo desta parte

Esta spec cobre **o dado**: a lista de ramos, o mapa CNAE→ramo, a gravação e as duas telas onde o
ramo é escolhido. O **fluxo de decisão de tenant** na contratação (comparar ramos, perguntar, e
explicar o impacto de cada escolha) é a **parte 2**, feita no mesmo dia e documentada em
[`contratacao-grupo.md`](contratacao-grupo.md).

## Solução

### A lista

**28 ramos**, definidos com o dono do produto (ele acrescentou 11 aos 17 propostos):

Açougue e peixaria · Agropecuária e insumos agrícolas · Armarinhos, aviamentos e tecidos · Artigos
esportivos · Artigos para festas e descartáveis · Artigos religiosos · Autopeças · Bebidas e adega ·
Brinquedos · Calçados · Confecção e moda · Eletrônicos e informática · Farmácia · Floricultura e
paisagismo · Instrumentos musicais · Joias e bijuterias · Livraria · Materiais de construção ·
Mercearia e mercado · Móveis e decoração · Ótica · Padaria e confeitaria · Papelaria · Perfumaria e
cosméticos · Pet shop · Produtos naturais e suplementos · Utilidades e bazar · **Outros**

**Por que não o CNAE direto:** ele é a classificação oficial e é o que vem no CNPJ, mas tem **1.332
subclasses**. Ninguém escolhe "4782-2/01 — comércio varejista de calçados" numa tela de
contratação. A lista curta é para o humano; o CNAE fica por trás.

### A sugestão

Decisão dele, literal: **"dar a sugestão, mas o usuário define"**.

Ao completar os 14 dígitos do CNPJ, a API consulta os dados públicos, lê o CNAE e devolve o ramo
correspondente. A tela **preenche apenas campo vazio** — quem já escolheu um ramo não o vê ser
trocado por um palpite.

⚠️ **Quatro ramos não têm CNAE, e isso é fato da fonte:** "artigos para festas e descartáveis",
"artigos religiosos" e "produtos naturais e suplementos" não existem como subclasse — essas lojas
se registram em códigos genéricos (`4789-0/99`, `4729-6/99`), que servem a dezenas de atividades.
Esses códigos ficam **fora** do mapa: sem palpite, o usuário escolhe. "Outros" nunca é sugerido.

⚠️ **A lista de CNAEs não foi digitada de memória.** Veio da tabela oficial do IBGE
(`servicodados.ibge.gov.br/api/v2/cnae/subclasses`) e cada um dos 65 códigos foi conferido contra
ela por script antes de virar SQL — mesma regra do NCM da Receita e das 27 UFs.

### De quem é o ramo

Da **empresa** — é o CNPJ que tem atividade econômica. O ramo do **tenant** é derivado (o conjunto
dos ramos das empresas dele). Guardá-lo também no tenant criaria duas versões da mesma verdade.

### Onde se escolhe

| Onde | Como |
|---|---|
| **Signup** (site público) | Campo "Ramo de atividade", escolha direta. **Sem CNPJ**: o signup não pede CNPJ, e acrescentar esse campo ao funil custaria conversão. |
| **Dados da Empresa** (ERP) | Campo "Ramo de Atividade" ao lado do CNAE. Aqui o CNPJ existe, então digitar o CNPJ **sugere** o ramo e preenche CNAE e nome fantasia se estiverem vazios. |

## Contrato de API

- `GET /api/publico/ramos` e `GET /api/v1/ramos` → `[{idRamo, codigo, nome}]`
- `GET /api/v1/cnpj/{cnpj}` → `{cnpj, razaoSocial, nomeFantasia, cnae, cnaeDescricao, ramoSugerido}`
  — **204** quando não deu para consultar (CNPJ inexistente, serviço fora do ar, tempo esgotado).
  Os quatro casos são o mesmo para a tela: seguir à mão, sem erro na cara do usuário.
- `POST /api/publico/assinar` aceita `idRamo` (opcional)
- `POST`/`PUT /api/v1/empresas` aceitam `idRamo`

⚠️ **A consulta de CNPJ é autenticada, de propósito.** Aberta na superfície pública, viraria um
consultor de CNPJ de graça em nome da Vetor, com nosso IP levando o bloqueio quando alguém
resolvesse varrer a base.

⚠️ **Duas réguas para id inválido**, e a diferença é deliberada: no **signup** um `idRamo`
inexistente vira "não informado" (ramo é segmentação, não pode impedir alguém de criar conta); em
**empresa**, é recusado com 400 (ali o usuário escolhe numa lista, então id fora dela é cliente de
API mandando lixo).

## Critérios de aceitação

- **Dado** o CNAE `4782201`, **quando** o ramo é consultado, **então** vem "Calçados"; com máscara
  (`4782-2/01`) também.
- **Dado** um CNAE genérico (`4789099`, `4729699`) ou de fora do varejo (`6422100`), **quando** o
  ramo é consultado, **então** não vem sugestão nenhuma.
- **Dado** um signup com `idRamo` válido, **então** a empresa nasce com o ramo; com `idRamo`
  inexistente, a conta é criada **sem** ramo (nunca com erro).
- **Dado** um `PUT` de empresa com `idRamo` inexistente, **então** 400; com id válido, 200.
- **Dado** um CNPJ real, **quando** consultado, **então** vêm razão social, CNAE e o ramo sugerido
  (verificado ao vivo: Banco do Brasil → CNAE `6422100`, sem sugestão; Magazine Luiza → `4713004`,
  sem sugestão, porque loja de departamento não é nenhum dos 28).

Testes: `api/src/test/java/com/vetor/niner/RamoAtividadeTest.java` (8 casos).

⚠️ **O `GRANT` de coluna não é coberto pela suíte.** `niner_app` tem privilégio **por coluna** em
`empresa` (25 colunas), então `id_ramo` nasceria inacessível — e o Testcontainers conecta como
superusuário, que não sofre isso. A V072 traz o `GRANT`, e ele foi verificado **ao vivo** no banco
de dev, gravando o ramo pela API.

## Parte 2 — feita no mesmo dia

O fluxo de contratação que usa este dado (comparar o ramo da empresa que entra com o das empresas
do tenant e oferecer **mesmo grupo × grupo separado**) está em [contratacao-grupo.md](contratacao-grupo.md).
