import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { dataHora } from '../lib/api'
import { useAuth } from '../lib/auth'
import { buscarConfiguracao, executarBackup, salvarConfiguracao, type Configuracao as Config } from '../lib/plataforma'

interface Formulario {
  smtpHabilitado: boolean
  smtpHost: string
  smtpPorta: string
  smtpUsuario: string
  smtpSenha: string
  smtpStarttls: boolean
  smtpRemetenteEmail: string
  smtpRemetenteNome: string
  backupHabilitado: boolean
  backupHora: string
  backupRetencaoDias: string
  mpAccessToken: string
  mpWebhookSecret: string
  mpNotificationUrl: string
}

function paraFormulario(c: Config): Formulario {
  return {
    smtpHabilitado: c.smtpHabilitado,
    smtpHost: c.smtpHost ?? '',
    smtpPorta: c.smtpPorta ? String(c.smtpPorta) : '587',
    smtpUsuario: c.smtpUsuario ?? '',
    smtpSenha: '',                     // segredo nunca volta do servidor — em branco = manter
    smtpStarttls: c.smtpStarttls,
    smtpRemetenteEmail: c.smtpRemetenteEmail ?? '',
    smtpRemetenteNome: c.smtpRemetenteNome ?? 'Niner',
    backupHabilitado: c.backupHabilitado,
    backupHora: (c.backupHora ?? '03:00:00').slice(0, 5),
    backupRetencaoDias: String(c.backupRetencaoDias),
    mpAccessToken: '',
    mpWebhookSecret: '',
    mpNotificationUrl: c.mpNotificationUrl ?? '',
  }
}

/**
 * Configuração da plataforma. O ponto delicado da tela é o tratamento de segredo: o servidor
 * nunca devolve senha nem token, então o campo aparece <b>vazio</b> com a marca "configurado".
 * Deixar em branco mantém o que está gravado; apagar exige digitar <code>LIMPAR</code> — e a tela
 * diz isso, porque um campo vazio que apaga credencial derruba e-mail ou cobrança sem aviso.
 */
export default function Configuracao() {
  const { ehSuperAdmin } = useAuth()
  const qc = useQueryClient()
  const { data: config } = useQuery({ queryKey: ['configuracao'], queryFn: buscarConfiguracao })
  const [form, setForm] = useState<Formulario | null>(null)
  const [aviso, setAviso] = useState<string | null>(null)
  const [erro, setErro] = useState<string | null>(null)

  useEffect(() => {
    if (config && !form) setForm(paraFormulario(config))
  }, [config, form])

  const salvar = useMutation({
    mutationFn: () =>
      salvarConfiguracao({
        ...form,
        smtpPorta: form?.smtpPorta ? Number(form.smtpPorta) : null,
        backupHora: form?.backupHora ? `${form.backupHora}:00` : null,
        backupRetencaoDias: form?.backupRetencaoDias ? Number(form.backupRetencaoDias) : null,
      }),
    onSuccess: (c) => {
      setErro(null)
      setAviso('Configuração salva.')
      setForm(paraFormulario(c))
      qc.setQueryData(['configuracao'], c)
    },
    onError: (e: Error) => { setAviso(null); setErro(e.message) },
  })

  const backup = useMutation({
    mutationFn: executarBackup,
    onSuccess: (r) => {
      setAviso(r.resultado)
      qc.invalidateQueries({ queryKey: ['configuracao'] })
    },
    onError: (e: Error) => setErro(e.message),
  })

  if (!config || !form) return <p className="muted">Carregando…</p>
  const alterar = (campo: keyof Formulario, valor: string | boolean) => setForm({ ...form, [campo]: valor })

  return (
    <>
      <div className="topo-pagina">
        <h1>Configuração</h1>
        <span className="muted">Atualizada em {dataHora(config.atualizadoEm)}</span>
      </div>

      {!ehSuperAdmin && (
        <p className="card" style={{ borderColor: 'var(--warning)' }}>
          Seu papel enxerga esta tela, mas <b>só SUPER_ADMIN altera</b> — é aqui que se define para onde
          vão os e-mails da plataforma e qual conta recebe o dinheiro.
        </p>
      )}

      <section className="card secao">
        <h2>Envio de e-mail (SMTP)</h2>
        <p className="muted" style={{ marginBottom: 14 }}>
          Usado na recuperação de senha, nos avisos de cota e, adiante, no envio de NF-e ao cliente
          do lojista.
        </p>
        <div className="check">
          <input id="smtp-on" type="checkbox" checked={form.smtpHabilitado} disabled={!ehSuperAdmin}
            onChange={(e) => alterar('smtpHabilitado', e.target.checked)} />
          <label htmlFor="smtp-on">Envio de e-mail ligado</label>
        </div>
        <div className="linha">
          <div className="campo">
            <label htmlFor="host">Servidor</label>
            <input id="host" value={form.smtpHost} disabled={!ehSuperAdmin}
              onChange={(e) => alterar('smtpHost', e.target.value)} placeholder="smtp.provedor.com.br" />
          </div>
          <div className="campo">
            <label htmlFor="porta">Porta</label>
            <input id="porta" value={form.smtpPorta} disabled={!ehSuperAdmin} inputMode="numeric"
              onChange={(e) => alterar('smtpPorta', e.target.value)} />
          </div>
          <div className="campo">
            <label htmlFor="usuario">Usuário</label>
            <input id="usuario" value={form.smtpUsuario} disabled={!ehSuperAdmin}
              onChange={(e) => alterar('smtpUsuario', e.target.value)} />
          </div>
          <div className="campo">
            <label htmlFor="senha">
              Senha {config.smtpSenhaDefinida && <span className="tag tag-ok">configurada</span>}
            </label>
            <input id="senha" type="password" value={form.smtpSenha} disabled={!ehSuperAdmin}
              placeholder={config.smtpSenhaDefinida ? 'em branco mantém · LIMPAR apaga' : ''}
              onChange={(e) => alterar('smtpSenha', e.target.value)} />
          </div>
          <div className="campo">
            <label htmlFor="remetente">E-mail remetente</label>
            <input id="remetente" value={form.smtpRemetenteEmail} disabled={!ehSuperAdmin}
              onChange={(e) => alterar('smtpRemetenteEmail', e.target.value)} placeholder="nao-responda@niner.com.br" />
          </div>
          <div className="campo">
            <label htmlFor="remetente-nome">Nome do remetente</label>
            <input id="remetente-nome" value={form.smtpRemetenteNome} disabled={!ehSuperAdmin}
              onChange={(e) => alterar('smtpRemetenteNome', e.target.value)} />
          </div>
        </div>
        <div className="check">
          <input id="starttls" type="checkbox" checked={form.smtpStarttls} disabled={!ehSuperAdmin}
            onChange={(e) => alterar('smtpStarttls', e.target.checked)} />
          <label htmlFor="starttls">STARTTLS (porta 587). Na 465 o TLS é direto e isto é ignorado.</label>
        </div>
      </section>

      <section className="card secao">
        <h2>Backup automático</h2>
        <p className="muted" style={{ marginBottom: 14 }}>
          O backup roda no horário abaixo e envia o dump para o armazenamento privado. Requer o
          usuário de banco com <b>BYPASSRLS</b> configurado no servidor — sem ele o dump sairia sem
          os dados dos lojistas, e o sistema recusa executar.
        </p>
        <div className="check">
          <input id="backup-on" type="checkbox" checked={form.backupHabilitado} disabled={!ehSuperAdmin}
            onChange={(e) => alterar('backupHabilitado', e.target.checked)} />
          <label htmlFor="backup-on">Backup diário ligado</label>
        </div>
        <div className="linha">
          <div className="campo">
            <label htmlFor="hora">Horário</label>
            <input id="hora" type="time" value={form.backupHora} disabled={!ehSuperAdmin}
              onChange={(e) => alterar('backupHora', e.target.value)} />
          </div>
          <div className="campo">
            <label htmlFor="retencao">Retenção (dias)</label>
            <input id="retencao" value={form.backupRetencaoDias} disabled={!ehSuperAdmin} inputMode="numeric"
              onChange={(e) => alterar('backupRetencaoDias', e.target.value)} />
          </div>
        </div>
        <p className="muted">
          Último backup: {dataHora(config.backupUltimoEm)}{' '}
          {config.backupUltimoStatus && (
            <span className={`tag ${config.backupUltimoStatus === 'OK' ? 'tag-ok' : 'tag-perigo'}`}>
              {config.backupUltimoStatus}
            </span>
          )}
          {config.backupUltimoDetalhe && <><br />{config.backupUltimoDetalhe}</>}
        </p>
        {ehSuperAdmin && (
          <button className="btn btn-secundario" disabled={backup.isPending} onClick={() => backup.mutate()}>
            {backup.isPending ? 'Executando…' : 'Executar backup agora'}
          </button>
        )}
      </section>

      <section className="card secao">
        <h2>Cobrança (Mercado Pago)</h2>
        <p className="muted" style={{ marginBottom: 14 }}>
          Preenchido aqui, vence a variável de ambiente. Token de teste começa com <code>TEST-</code>;
          credencial de produção cobra de verdade.
        </p>
        <div className="linha">
          <div className="campo">
            <label htmlFor="mp-token">
              Access token {config.mpAccessTokenDefinido && <span className="tag tag-ok">configurado</span>}
            </label>
            <input id="mp-token" type="password" value={form.mpAccessToken} disabled={!ehSuperAdmin}
              placeholder={config.mpAccessTokenDefinido ? 'em branco mantém · LIMPAR apaga' : ''}
              onChange={(e) => alterar('mpAccessToken', e.target.value)} />
          </div>
          <div className="campo">
            <label htmlFor="mp-webhook">
              Segredo do webhook {config.mpWebhookSecretDefinido && <span className="tag tag-ok">configurado</span>}
            </label>
            <input id="mp-webhook" type="password" value={form.mpWebhookSecret} disabled={!ehSuperAdmin}
              placeholder={config.mpWebhookSecretDefinido ? 'em branco mantém · LIMPAR apaga' : ''}
              onChange={(e) => alterar('mpWebhookSecret', e.target.value)} />
          </div>
          <div className="campo">
            <label htmlFor="mp-url">URL de notificação</label>
            <input id="mp-url" value={form.mpNotificationUrl} disabled={!ehSuperAdmin}
              placeholder="https://api.niner.com.br/api/publico/webhooks/mercadopago"
              onChange={(e) => alterar('mpNotificationUrl', e.target.value)} />
          </div>
        </div>
      </section>

      {aviso && <p className="card" style={{ marginTop: 16, borderColor: 'var(--accent)' }}>{aviso}</p>}
      {erro && <p className="erro" style={{ marginTop: 16 }}>{erro}</p>}

      {ehSuperAdmin && (
        <div className="acoes" style={{ marginTop: 18 }}>
          <button className="btn btn-primario" disabled={salvar.isPending} onClick={() => salvar.mutate()}>
            {salvar.isPending ? 'Salvando…' : 'Salvar configuração'}
          </button>
        </div>
      )}
    </>
  )
}
