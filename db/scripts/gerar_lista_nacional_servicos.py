#!/usr/bin/env python3
"""Gera o bloco de INSERT da V099 (cfg_servico_lc116) a partir do anexo OFICIAL da NFS-e Nacional.

⛔ A lista nacional de serviços NÃO se digita — é a mesma regra do NCM (V017), das 27 UFs
(V047) e do mapa CNAE→ramo (V093). Este script existe para que a migration seja DERIVADA da
fonte e possa ser reconferida quando o anexo mudar de versão.

FONTE: aba "RN MUN.INCID  INFO.SERV." de
  AnexoI-LeiautesRN_DPS_NFSe-SNNFSe_v1.01.00-homologacao.xlsx
publicado em gov.br/nfse → Documentação Técnica. A cópia usada em 2026-08-29 está em
  ~/Documents/projetos/finance-v/docs/fiscal/nfse-nacional/

COLUNAS DA ABA (conferidas nas linhas 1-4 do próprio arquivo):
  A = código de tributação nacional (cTribNac)   B = descrição do desdobro nacional
  C = EDP (estab./domicílio do prestador)        D = LP (local da prestação)
  E = EDT (estab./domicílio do tomador)          F = casos especiais (MAN / exploração de rodovia)
  G = EDEmit (importação)                        H = grupo extra exigido no leiaute da DPS
  I..K = comércio exterior / informações complementares

USO:  python3 gerar_lista_nacional_servicos.py <caminho-do-xlsx>
"""
import sys
import zipfile
from xml.etree import ElementTree as ET

NS = '{http://schemas.openxmlformats.org/spreadsheetml/2006/main}'
ABA = 'RN MUN'

# Coluna marcada -> valor do enum local_incidencia_iss.
INCIDENCIA = {'C': 'PRESTADOR', 'D': 'PRESTACAO', 'E': 'TOMADOR', 'F': 'ESPECIAL'}


def ler_aba(caminho):
    z = zipfile.ZipFile(caminho)
    shared = []
    for si in ET.fromstring(z.read('xl/sharedStrings.xml')).findall(NS + 'si'):
        shared.append(''.join(t.text or '' for t in si.iter(NS + 't')))
    wb = ET.fromstring(z.read('xl/workbook.xml'))
    rid2t = {r.get('Id'): r.get('Target')
             for r in ET.fromstring(z.read('xl/_rels/workbook.xml.rels'))}
    alvo = None
    for s in wb.find(NS + 'sheets'):
        if ABA.lower() in s.get('name').lower():
            alvo = rid2t[[v for k, v in s.attrib.items() if k.endswith('}id')][0]]
    if alvo is None:
        sys.exit(f'aba {ABA!r} não encontrada em {caminho}')
    alvo = alvo if alvo.startswith('xl/') else 'xl/' + alvo.lstrip('/')
    for row in ET.fromstring(z.read(alvo)).iter(NS + 'row'):
        cel = {}
        for c in row.findall(NS + 'c'):
            letra = ''.join(ch for ch in c.get('r') if ch.isalpha())
            v = c.find(NS + 'v')
            txt = ''
            if v is not None:
                txt = shared[int(v.text)] if c.get('t') == 's' else (v.text or '')
            cel[letra] = ' '.join(txt.split())
        yield cel


def marcada(valor):
    """A planilha marca com 'X', e nos casos com nota de rodapé com '(3) X' / '(1) X'."""
    return 'X' in (valor or '').upper()


def main():
    caminho = sys.argv[1]
    linhas = []
    for cel in ler_aba(caminho):
        a = cel.get('A', '')
        if not (a.isdigit() and 4 <= len(a) <= 6):
            continue                      # cabeçalho, rodapé, linha vazia
        codigo = a.zfill(6)               # o Excel come o zero à esquerda: 10101 -> 010101
        descricao = cel.get('B', '')
        flags = [k for k in 'CDEF' if marcada(cel.get(k))]
        if len(flags) == 1:
            incidencia = INCIDENCIA[flags[0]]
        elif not flags:
            incidencia = 'SEM_INCIDENCIA'  # 990101 — serviços sem incidência de ISSQN e ICMS
        else:
            incidencia = 'ESPECIAL'        # 200101 — EDP, salvo quando o LP é "Águas Marítimas"
        grupo = cel.get('H', '')
        grupo = None if grupo in ('', '-') else grupo
        linhas.append((codigo, descricao, incidencia, grupo))

    vistos = {c for c, *_ in linhas}
    if len(vistos) != len(linhas):
        sys.exit('código duplicado na fonte — conferir o anexo antes de gerar')

    def lit(s):
        return 'NULL' if s is None else "'" + s.replace("'", "''") + "'"

    print(f'-- {len(linhas)} códigos, derivados de {caminho.split("/")[-1]}')
    print('INSERT INTO cfg_servico_lc116 (codigo, descricao, local_incidencia, grupo_dps) VALUES')
    for i, (cod, desc, inc, gru) in enumerate(linhas):
        fim = ';' if i == len(linhas) - 1 else ','
        print(f"  ('{cod}', {lit(desc)}, '{inc}', {lit(gru)}){fim}")


if __name__ == '__main__':
    main()
