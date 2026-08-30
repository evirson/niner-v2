import { useState } from 'react'
import {
  CRT_OPCOES_PERFIL,
  CSOSN_OPCOES,
  CST_ICMS_OPCOES,
  CST_PIS_COFINS_OPCOES,
  TIPO_DESTINATARIO_OPCOES,
  TIPO_OPERACAO_OPCOES,
  type PerfilFiscalRegraRequest,
  type TipoDestinatarioFiscal,
  type TipoOperacaoFiscal,
} from '../lib/perfilFiscal'
import { completarPercentual, desmascararPercentual, formatarPercentual, mascararPercentual } from '../lib/masks'
import { fecharAoClicarNoFundo } from '../lib/modais'

interface FormState {
  crt: number
  ufDestino: string
  tipoDestinatario: TipoDestinatarioFiscal
  tipoOperacao: TipoOperacaoFiscal
  cfop: string
  cfopInterestadual: string
  modoIcms: 'CSOSN' | 'CST'
  csosn: string
  cstIcms: string
  aliquotaIcms: string
  percReducaoBc: string
  mvaSt: string
  aliquotaFcp: string
  cstPis: string
  aliquotaPis: string
  cstCofins: string
  aliquotaCofins: string
  cstIbscbs: string
  cclasstrib: string
  codigoBeneficio: string
}

function estadoInicial(r: PerfilFiscalRegraRequest | null): FormState {
  if (!r) {
    return {
      crt: 1,
      ufDestino: '*',
      tipoDestinatario: 'CONSUMIDOR_FINAL',
      tipoOperacao: 'VENDA_CONSUMIDOR',
      cfop: '',
      cfopInterestadual: '',
      modoIcms: 'CSOSN',
      csosn: '102',
      cstIcms: '',
      aliquotaIcms: '',
      percReducaoBc: '',
      mvaSt: '',
      aliquotaFcp: '',
      cstPis: '99',
      aliquotaPis: '',
      cstCofins: '99',
      aliquotaCofins: '',
      cstIbscbs: '',
      cclasstrib: '',
      codigoBeneficio: '',
    }
  }
  return {
    crt: r.crt,
    ufDestino: r.ufDestino,
    tipoDestinatario: r.tipoDestinatario,
    tipoOperacao: r.tipoOperacao,
    cfop: r.cfop,
    cfopInterestadual: r.cfopInterestadual ?? '',
    modoIcms: r.cstIcms ? 'CST' : 'CSOSN',
    csosn: r.csosn ?? '102',
    cstIcms: r.cstIcms ?? '00',
    aliquotaIcms: formatarPercentual(r.aliquotaIcms),
    percReducaoBc: formatarPercentual(r.percReducaoBc),
    mvaSt: formatarPercentual(r.mvaSt),
    aliquotaFcp: formatarPercentual(r.aliquotaFcp),
    cstPis: r.cstPis ?? '99',
    aliquotaPis: formatarPercentual(r.aliquotaPis),
    cstCofins: r.cstCofins ?? '99',
    aliquotaCofins: formatarPercentual(r.aliquotaCofins),
    cstIbscbs: r.cstIbscbs ?? '',
    cclasstrib: r.cclasstrib ?? '',
    codigoBeneficio: r.codigoBeneficio ?? '',
  }
}

function paraRequisicao(f: FormState): PerfilFiscalRegraRequest {
  return {
    crt: f.crt,
    ufDestino: f.ufDestino.trim().toUpperCase() || '*',
    tipoDestinatario: f.tipoDestinatario,
    tipoOperacao: f.tipoOperacao,
    cfop: f.cfop.trim(),
    cfopInterestadual: f.cfopInterestadual.trim() || null,
    csosn: f.crt === 2 && f.modoIcms === 'CST' ? null : f.csosn,
    cstIcms: f.crt === 2 && f.modoIcms === 'CST' ? f.cstIcms : null,
    aliquotaIcms: desmascararPercentual(f.aliquotaIcms),
    percReducaoBc: desmascararPercentual(f.percReducaoBc),
    mvaSt: desmascararPercentual(f.mvaSt),
    aliquotaFcp: desmascararPercentual(f.aliquotaFcp),
    cstPis: f.cstPis,
    aliquotaPis: f.cstPis === '99' ? 0 : desmascararPercentual(f.aliquotaPis),
    cstCofins: f.cstCofins,
    aliquotaCofins: f.cstCofins === '99' ? 0 : desmascararPercentual(f.aliquotaCofins),
    cstIbscbs: f.cstIbscbs.trim() || null,
    cclasstrib: f.cclasstrib.trim() || null,
    codigoBeneficio: f.codigoBeneficio.trim() || null,
  }
}

/**
 * Formulário de UMA regra do Perfil Fiscal, em popup (docs/telas/fiscal-perfil.md — "não
 * inline", são ~15 campos). CST de ICMS só é oferecido no CRT 2 (excesso de sublimite); nos
 * demais CRT o campo nem aparece — é CSOSN sempre. PIS/COFINS: CST 99 (o caso normal, dentro
 * do DAS) desabilita a alíquota; qualquer outro CST habilita.
 */
export default function RegraFiscalModal({
  regraInicial,
  aoSalvar,
  aoFechar,
}: {
  regraInicial: PerfilFiscalRegraRequest | null
  aoSalvar: (payload: PerfilFiscalRegraRequest) => void
  aoFechar: () => void
}) {
  const [f, setF] = useState<FormState>(() => estadoInicial(regraInicial))

  const valido = f.cfop.trim().length === 4 && (f.modoIcms === 'CSOSN' ? !!f.csosn : !!f.cstIcms)

  return (
    <div className="modal-overlay" onClick={fecharAoClicarNoFundo(aoFechar)}>
      <div className="modal modal-largo" role="dialog" aria-label="Regra do perfil fiscal" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>{regraInicial ? 'Editar regra' : 'Nova regra'}</h2>

        <div className="form-grid">
          <div className="col-4">
            <label htmlFor="regra-crt">CRT *</label>
            <select
              id="regra-crt"
              value={f.crt}
              onChange={(e) => {
                const crt = Number(e.target.value)
                setF((s) => ({ ...s, crt, modoIcms: crt === 2 ? s.modoIcms : 'CSOSN' }))
              }}
            >
              {CRT_OPCOES_PERFIL.map((o) => (
                <option key={o.valor} value={o.valor}>
                  {o.rotulo}
                </option>
              ))}
            </select>
          </div>
          <div className="col-4">
            <label htmlFor="regra-uf">UF destino</label>
            <input
              id="regra-uf"
              value={f.ufDestino}
              maxLength={2}
              placeholder="* (qualquer)"
              onChange={(e) => setF((s) => ({ ...s, ufDestino: e.target.value.toUpperCase() }))}
            />
          </div>
          <div className="col-4">
            <label htmlFor="regra-cfop">CFOP *</label>
            <input
              id="regra-cfop"
              value={f.cfop}
              maxLength={4}
              inputMode="numeric"
              onChange={(e) => setF((s) => ({ ...s, cfop: e.target.value.replace(/\D/g, '') }))}
            />
          </div>
          <div className="col-4">
            <label htmlFor="regra-cfop-inter">CFOP fora da UF</label>
            <input
              id="regra-cfop-inter"
              value={f.cfopInterestadual}
              maxLength={4}
              inputMode="numeric"
              placeholder="6xxx"
              onChange={(e) => setF((s) => ({ ...s, cfopInterestadual: e.target.value.replace(/\D/g, '') }))}
            />
            <p className="muted" style={{ fontSize: 12, marginTop: 4 }}>
              Usado quando o cliente é de outro estado. Vazio = a regra só vale dentro da UF, e a
              venda interestadual é recusada com aviso — nunca com um CFOP adivinhado.
            </p>
          </div>
          <div className="col-6">
            <label htmlFor="regra-destinatario">Destinatário *</label>
            <select
              id="regra-destinatario"
              value={f.tipoDestinatario}
              onChange={(e) => setF((s) => ({ ...s, tipoDestinatario: e.target.value as TipoDestinatarioFiscal }))}
            >
              {TIPO_DESTINATARIO_OPCOES.map((o) => (
                <option key={o.valor} value={o.valor}>
                  {o.rotulo}
                </option>
              ))}
            </select>
          </div>
          <div className="col-6">
            <label htmlFor="regra-operacao">Operação *</label>
            <select
              id="regra-operacao"
              value={f.tipoOperacao}
              onChange={(e) => setF((s) => ({ ...s, tipoOperacao: e.target.value as TipoOperacaoFiscal }))}
            >
              {TIPO_OPERACAO_OPCOES.map((o) => (
                <option key={o.valor} value={o.valor}>
                  {o.rotulo}
                </option>
              ))}
            </select>
          </div>
        </div>

        <p className="section-label" style={{ marginTop: 16 }}>
          ICMS
        </p>
        {f.crt === 2 && (
          <div className="form-grid">
            <div className="col-12">
              <label className="checkbox-linha" style={{ marginTop: 0 }}>
                <input
                  type="radio"
                  name="modo-icms"
                  checked={f.modoIcms === 'CSOSN'}
                  onChange={() => setF((s) => ({ ...s, modoIcms: 'CSOSN' }))}
                />
                Emite com CSOSN
                <span style={{ marginLeft: 16 }} />
                <input
                  type="radio"
                  name="modo-icms"
                  checked={f.modoIcms === 'CST'}
                  onChange={() => setF((s) => ({ ...s, modoIcms: 'CST' }))}
                />
                Emite com CST
              </label>
              <p className="muted" style={{ fontSize: 12, marginTop: 4 }}>
                A empresa com excesso de sublimite recolhe ICMS fora do Simples, e o mercado
                diverge sobre qual grupo ela usa. Confirme com o contador.
              </p>
            </div>
          </div>
        )}
        <div className="form-grid">
          {f.modoIcms === 'CSOSN' ? (
            <div className="col-4">
              <label htmlFor="regra-csosn">CSOSN *</label>
              <select id="regra-csosn" value={f.csosn} onChange={(e) => setF((s) => ({ ...s, csosn: e.target.value }))}>
                {CSOSN_OPCOES.map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </div>
          ) : (
            <div className="col-4">
              <label htmlFor="regra-cst-icms">CST de ICMS *</label>
              <select id="regra-cst-icms" value={f.cstIcms} onChange={(e) => setF((s) => ({ ...s, cstIcms: e.target.value }))}>
                {CST_ICMS_OPCOES.map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </div>
          )}
          <div className="col-4">
            <label htmlFor="regra-aliq-icms">Alíquota ICMS (%)</label>
            <input
              id="regra-aliq-icms"
              value={f.aliquotaIcms}
              onChange={(e) => setF((s) => ({ ...s, aliquotaIcms: mascararPercentual(e.target.value) }))}
              onBlur={() => setF((s) => ({ ...s, aliquotaIcms: completarPercentual(s.aliquotaIcms) }))}
            />
            {f.modoIcms === 'CST' && !['40', '41', '50', '60'].includes(f.cstIcms) && (
              <p className="muted" style={{ fontSize: 12, marginTop: 4 }}>
                Este CST destaca ICMS — sem alíquota, a nota é recusada na emissão.
              </p>
            )}
          </div>
          <div className="col-4">
            <label htmlFor="regra-reducao-bc">Redução de base (%)</label>
            <input
              id="regra-reducao-bc"
              value={f.percReducaoBc}
              onChange={(e) => setF((s) => ({ ...s, percReducaoBc: mascararPercentual(e.target.value) }))}
              onBlur={() => setF((s) => ({ ...s, percReducaoBc: completarPercentual(s.percReducaoBc) }))}
            />
          </div>
          <div className="col-4">
            <label htmlFor="regra-mva">MVA/ST (%)</label>
            <input
              id="regra-mva"
              value={f.mvaSt}
              onChange={(e) => setF((s) => ({ ...s, mvaSt: mascararPercentual(e.target.value) }))}
              onBlur={() => setF((s) => ({ ...s, mvaSt: completarPercentual(s.mvaSt) }))}
            />
          </div>
          <div className="col-4">
            <label htmlFor="regra-fcp">FCP (%)</label>
            <input
              id="regra-fcp"
              value={f.aliquotaFcp}
              onChange={(e) => setF((s) => ({ ...s, aliquotaFcp: mascararPercentual(e.target.value) }))}
              onBlur={() => setF((s) => ({ ...s, aliquotaFcp: completarPercentual(s.aliquotaFcp) }))}
            />
          </div>
          <div className="col-4">
            <label htmlFor="regra-cbenef">Código de benefício (cBenef)</label>
            <input id="regra-cbenef" value={f.codigoBeneficio} onChange={(e) => setF((s) => ({ ...s, codigoBeneficio: e.target.value.toUpperCase() }))} />
          </div>
        </div>

        <p className="section-label" style={{ marginTop: 16 }}>
          PIS/COFINS
        </p>
        <p className="muted" style={{ fontSize: 12, marginTop: -8 }}>
          CST 99 é o caso normal — tributo dentro do DAS, sem alíquota própria.
        </p>
        <div className="form-grid">
          <div className="col-3">
            <label htmlFor="regra-cst-pis">CST PIS *</label>
            <select id="regra-cst-pis" value={f.cstPis} onChange={(e) => setF((s) => ({ ...s, cstPis: e.target.value }))}>
              {CST_PIS_COFINS_OPCOES.map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
            </select>
          </div>
          <div className="col-3">
            <label htmlFor="regra-aliq-pis">Alíquota PIS (%)</label>
            <input
              id="regra-aliq-pis"
              disabled={f.cstPis === '99'}
              value={f.cstPis === '99' ? '' : f.aliquotaPis}
              onChange={(e) => setF((s) => ({ ...s, aliquotaPis: mascararPercentual(e.target.value) }))}
              onBlur={() => setF((s) => ({ ...s, aliquotaPis: completarPercentual(s.aliquotaPis) }))}
            />
          </div>
          <div className="col-3">
            <label htmlFor="regra-cst-cofins">CST COFINS *</label>
            <select id="regra-cst-cofins" value={f.cstCofins} onChange={(e) => setF((s) => ({ ...s, cstCofins: e.target.value }))}>
              {CST_PIS_COFINS_OPCOES.map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
            </select>
          </div>
          <div className="col-3">
            <label htmlFor="regra-aliq-cofins">Alíquota COFINS (%)</label>
            <input
              id="regra-aliq-cofins"
              disabled={f.cstCofins === '99'}
              value={f.cstCofins === '99' ? '' : f.aliquotaCofins}
              onChange={(e) => setF((s) => ({ ...s, aliquotaCofins: mascararPercentual(e.target.value) }))}
              onBlur={() => setF((s) => ({ ...s, aliquotaCofins: completarPercentual(s.aliquotaCofins) }))}
            />
          </div>
        </div>

        <p className="section-label" style={{ marginTop: 16 }}>
          Reforma (IBS/CBS)
        </p>
        <div className="form-grid">
          <div className="col-6">
            <label htmlFor="regra-cst-ibscbs">CST de IBS/CBS</label>
            <input
              id="regra-cst-ibscbs"
              value={f.cstIbscbs}
              maxLength={3}
              inputMode="numeric"
              onChange={(e) => setF((s) => ({ ...s, cstIbscbs: e.target.value.replace(/\D/g, '') }))}
            />
          </div>
          <div className="col-6">
            <label htmlFor="regra-cclasstrib">cClassTrib</label>
            <input
              id="regra-cclasstrib"
              value={f.cclasstrib}
              maxLength={6}
              inputMode="numeric"
              onChange={(e) => setF((s) => ({ ...s, cclasstrib: e.target.value.replace(/\D/g, '') }))}
            />
          </div>
        </div>

        <div className="ajuda-rodape">
          <button type="button" className="btn ghost" onClick={aoFechar}>
            Cancelar
          </button>
          <button type="button" className="btn" disabled={!valido} onClick={() => aoSalvar(paraRequisicao(f))}>
            {regraInicial ? 'Salvar regra' : 'Adicionar regra'}
          </button>
        </div>
      </div>
    </div>
  )
}
