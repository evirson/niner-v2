/**
 * Medição própria de audiência do Niner (ADR-017) — first-party, sem rastreador de terceiro.
 *
 * O que ele faz, e por que cada pedaço existe:
 *  · `visitante_id` em cookie PRÓPRIO (1 ano): é o fio que liga o primeiro pageview anônimo ao
 *    signup dias depois. Sem ele não há como responder "a campanha X trouxe quantas contas?".
 *  · UTM da PRIMEIRA visita guardada em localStorage: o cadastro quase nunca acontece no mesmo
 *    acesso do anúncio — atribuir pelo último toque creditaria "direto" a campanha paga.
 *  · Cliques com `data-evento` (WhatsApp, Instagram, início de signup, FAQ, navegação) e
 *    profundidade de leitura viram evento.
 *  · `sendBeacon` com fila e envio em lote; **falha em silêncio**. Medição jamais pode quebrar,
 *    atrasar ou bloquear a página — inclusive quando a API está fora do ar.
 *
 * Não coleta: e-mail, telefone, nada digitado em formulário. Dado pessoal só entra pelo cadastro,
 * onde existe consentimento registrado (`plataforma.lead`).
 */
(function () {
  'use strict';

  var API = window.NINER_API_BASE;
  if (!API) return;

  var COOKIE = 'niner_vid';
  var CHAVE_ORIGEM = 'ninerOrigem';
  var CHAVE_SESSAO = 'ninerSessao';

  // ---- identidade anônima ------------------------------------------------------------------
  function lerCookie(nome) {
    var m = document.cookie.match('(^|;)\\s*' + nome + '\\s*=\\s*([^;]+)');
    return m ? decodeURIComponent(m[2]) : null;
  }
  function gravarCookie(nome, valor, dias) {
    var d = new Date();
    d.setTime(d.getTime() + dias * 86400000);
    document.cookie =
      nome + '=' + encodeURIComponent(valor) + ';expires=' + d.toUTCString() + ';path=/;SameSite=Lax';
  }
  function uuid() {
    if (window.crypto && crypto.randomUUID) return crypto.randomUUID();
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
      var r = (Math.random() * 16) | 0;
      return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
    });
  }

  var visitanteId = lerCookie(COOKIE);
  if (!visitanteId) {
    visitanteId = uuid();
    gravarCookie(COOKIE, visitanteId, 365);
  }
  // Expõe para o formulário de cadastro fechar o funil (lead → tenant).
  window.NINER_VISITANTE_ID = visitanteId;

  var sessaoId;
  try {
    sessaoId = sessionStorage.getItem(CHAVE_SESSAO);
    if (!sessaoId) {
      sessaoId = uuid();
      sessionStorage.setItem(CHAVE_SESSAO, sessaoId);
    }
  } catch (e) {
    sessaoId = uuid();
  }

  // ---- origem (primeiro toque) --------------------------------------------------------------
  function origem() {
    try {
      var salva = localStorage.getItem(CHAVE_ORIGEM);
      if (salva) return JSON.parse(salva);
    } catch (e) {}

    var q = new URLSearchParams(location.search);
    var nova = {
      utmSource: q.get('utm_source'),
      utmMedium: q.get('utm_medium'),
      utmCampaign: q.get('utm_campaign'),
      utmContent: q.get('utm_content'),
      utmTerm: q.get('utm_term'),
      referrer: document.referrer && document.referrer.indexOf(location.host) === -1 ? document.referrer : null,
      paginaEntrada: location.pathname,
    };
    try {
      localStorage.setItem(CHAVE_ORIGEM, JSON.stringify(nova));
    } catch (e) {}
    return nova;
  }
  var org = origem();
  window.NINER_ORIGEM = org;

  // ---- fila e envio -------------------------------------------------------------------------
  var fila = [];
  var agendado = null;

  function enviar() {
    agendado = null;
    if (!fila.length) return;
    var lote = { visitanteId: visitanteId, sessaoId: sessaoId, origem: org, eventos: fila.splice(0, fila.length) };
    var corpo = JSON.stringify(lote);
    try {
      // sendBeacon sobrevive à navegação (o clique que sai da página é justamente o que interessa).
      if (navigator.sendBeacon) {
        navigator.sendBeacon(API + '/api/publico/eventos', new Blob([corpo], { type: 'application/json' }));
        return;
      }
      fetch(API + '/api/publico/eventos', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: corpo,
        keepalive: true,
      }).catch(function () {});
    } catch (e) {
      /* medição nunca quebra a página */
    }
  }

  function registrar(tipo, rotulo, extra) {
    fila.push({
      tipo: tipo,
      rotulo: rotulo || null,
      caminho: location.pathname,
      valor: extra && extra.valor != null ? extra.valor : null,
    });
    if (!agendado) agendado = setTimeout(enviar, 800);
  }
  window.ninerEvento = registrar;

  // ---- pageview -----------------------------------------------------------------------------
  registrar('PAGEVIEW', document.title);

  // ---- cliques marcados no HTML (data-evento/data-rotulo) -----------------------------------
  document.addEventListener(
    'click',
    function (ev) {
      var alvo = ev.target && ev.target.closest ? ev.target.closest('[data-evento]') : null;
      if (!alvo) return;
      registrar(alvo.getAttribute('data-evento'), alvo.getAttribute('data-rotulo'));
      if (alvo.tagName === 'A' && alvo.href) enviar();   // vai sair da página: manda agora
    },
    true,
  );

  // FAQ abre por <details>, não por clique em link — o toggle é o sinal real de interesse.
  document.querySelectorAll('details[data-evento]').forEach(function (d) {
    d.addEventListener('toggle', function () {
      if (d.open) registrar(d.getAttribute('data-evento'), d.getAttribute('data-rotulo'));
    });
  });

  // ---- profundidade de leitura (25/50/75/90%) -----------------------------------------------
  var marcos = [25, 50, 75, 90];
  var atingidos = {};
  var travado = false;
  window.addEventListener(
    'scroll',
    function () {
      if (travado) return;
      travado = true;
      requestAnimationFrame(function () {
        travado = false;
        var altura = document.documentElement.scrollHeight - window.innerHeight;
        if (altura <= 0) return;
        var pct = Math.round((window.scrollY / altura) * 100);
        marcos.forEach(function (m) {
          if (pct >= m && !atingidos[m]) {
            atingidos[m] = true;
            registrar('SCROLL', String(m));
          }
        });
      });
    },
    { passive: true },
  );

  // ---- não deixar evento na fila ao sair -----------------------------------------------------
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'hidden') enviar();
  });
  window.addEventListener('pagehide', enviar);
})();
