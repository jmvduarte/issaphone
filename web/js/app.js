/*
 * IssaFone Web — chamadas (SIP sobre WebRTC, JsSIP) e chat (XMPP sobre WebSocket,
 * Strophe.js) ligados ao Issabel 4 / Openfire. Sem build: abre o index.html.
 */
'use strict';

const { Strophe, $msg, $iq, $pres } = window.strophe;
const $ = (s) => document.querySelector(s);

/* ======================= Configuração ======================= */

const CHAVE_CONFIG = 'issafone.config';
let cfg = null;

// Servidor pré-configurado. Se a webapp estiver alojada no próprio Issabel
// (https://dominio/issafone/), usa o domínio da página.
const SERVIDOR_PROJETO = 'rsi-a36357.ddns.net';
const SERVIDOR_PADRAO = /(^localhost$|^127\.|github\.io$)/.test(location.hostname)
  ? SERVIDOR_PROJETO : location.hostname;

const guardar = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* modo privado */ } };
const ler = (k) => { try { return JSON.parse(localStorage.getItem(k)); } catch { return null; } };

function completarConfig(c) {
  const host = ((c.servidor || '').trim() || SERVIDOR_PADRAO).replace(/^https?:\/\//, '').replace(/\/.*$/, '');
  return {
    ...c,
    servidor: host,
    extensao: c.extensao.trim(),
    dominioXmpp: (c.dominioXmpp || '').trim() || host,
    passXmpp: c.passXmpp || c.passSip,
    wsSip: (c.wsSip || '').trim() || `wss://${host}:8089/ws`,
    wsXmpp: (c.wsXmpp || '').trim() || `wss://${host}:7443/ws/`,
    stun: (c.stun || '').trim(),
  };
}

/* ======================= Histórico do chat ======================= */

const Historico = {
  chave: () => `issafone.msgs.${cfg.extensao}@${cfg.dominioXmpp}`,
  tudo() { return ler(this.chave()) || {}; },
  de(jid) { return this.tudo()[jid] || []; },
  juntar(jid, texto, minha) {
    const t = this.tudo();
    const lista = t[jid] || [];
    lista.push({ texto, minha, hora: Date.now() });
    t[jid] = lista.slice(-300);
    guardar(this.chave(), t);
  },
};

/* ======================= Áudio: toque de chamada ======================= */

const Toque = {
  ctx: null, intervalo: null,
  desbloquear() {
    // Os browsers só deixam tocar som depois de um gesto do utilizador
    try { this.ctx ??= new (window.AudioContext || window.webkitAudioContext)(); this.ctx.resume(); } catch { /* sem áudio */ }
  },
  bip() {
    if (!this.ctx) return;
    const t = this.ctx.currentTime;
    for (const [inicio, freq] of [[0, 440], [0, 480]]) {
      const o = this.ctx.createOscillator();
      const g = this.ctx.createGain();
      o.frequency.value = freq;
      g.gain.setValueAtTime(0.0001, t + inicio);
      g.gain.exponentialRampToValueAtTime(0.12, t + inicio + 0.05);
      g.gain.setValueAtTime(0.12, t + 1.2);
      g.gain.exponentialRampToValueAtTime(0.0001, t + 1.3);
      o.connect(g).connect(this.ctx.destination);
      o.start(t + inicio);
      o.stop(t + 1.35);
    }
  },
  tocar() { this.parar(); this.bip(); this.intervalo = setInterval(() => this.bip(), 3000); },
  parar() { clearInterval(this.intervalo); this.intervalo = null; },
};

/* ======================= Notificações do sistema ======================= */

const Avisos = {
  pode: () => 'Notification' in window && Notification.permission === 'granted',
  async pedir() {
    if (!('Notification' in window)) return;
    await Notification.requestPermission();
    UI.botaoNotificacoes();
  },
  mostrar(titulo, corpo, etiqueta, aoClicar) {
    if (!this.pode() || !document.hidden) return;
    try {
      const n = new Notification(titulo, { body: corpo, tag: etiqueta, icon: 'icons/icone-192.png', renotify: true });
      n.onclick = () => { window.focus(); aoClicar?.(); n.close(); };
    } catch { /* iOS só permite notificações na app instalada */ }
  },
};

/* ======================= Chamadas (SIP / WebRTC) ======================= */

const MOTIVOS = {
  Busy: 'Ocupado', Rejected: 'Chamada recusada', Unavailable: 'Indisponível',
  'Not Found': 'A extensão não existe', Canceled: 'Cancelada', 'No Answer': 'Sem resposta',
  'User Denied Media Access': 'Sem acesso ao microfone', 'Connection Error': 'Erro de ligação',
  'Request Timeout': 'Sem resposta do servidor', 'Address Incomplete': 'Número incompleto',
  'Incompatible SDP': 'Áudio incompatível (a extensão está configurada para WebRTC?)',
  'Authentication Error': 'Erro de autenticação', 'Bye': 'Chamada terminada', Terminated: 'Chamada terminada',
};

const Sip = {
  ua: null, sessao: null, inicio: null, relogio: null, silenciado: false,

  ligar() {
    JsSIP.debug.disable('JsSIP:*');
    const socket = new JsSIP.WebSocketInterface(cfg.wsSip);
    this.ua = new JsSIP.UA({
      sockets: [socket],
      uri: `sip:${cfg.extensao}@${cfg.servidor}`,
      password: cfg.passSip,
      register: true,
      session_timers: false,
      user_agent: 'IssaFone Web',
    });
    this.ua.on('connecting', () => UI.estado('sip', 'aviso', 'a ligar…'));
    this.ua.on('connected', () => UI.estado('sip', 'aviso', 'a registar…'));
    this.ua.on('disconnected', () => UI.estado('sip', 'erro', 'sem ligação (WSS 8089 e certificado?)'));
    this.ua.on('registered', () => UI.estado('sip', 'ok', 'registado'));
    this.ua.on('unregistered', () => UI.estado('sip', '', 'desligado'));
    this.ua.on('registrationFailed', (e) => UI.estado('sip', 'erro', `falhou (${MOTIVOS[e.cause] || e.cause})`));
    this.ua.on('newRTCSession', (e) => this.novaSessao(e.session, e.originator));
    this.ua.start();
  },

  opcoes() {
    return {
      mediaConstraints: { audio: true, video: false },
      pcConfig: { iceServers: cfg.stun ? [{ urls: cfg.stun }] : [] },
    };
  },

  chamar(destino) {
    destino = destino.trim();
    if (!destino || !this.ua || this.sessao) return;
    const uri = destino.includes('@') ? `sip:${destino}` : `sip:${destino}@${cfg.servidor}`;
    try {
      this.ua.call(uri, this.opcoes());
    } catch (e) {
      UI.aviso(`Não foi possível ligar: ${e.message}`);
    }
  },

  novaSessao(s, origem) {
    if (this.sessao) { // já em chamada: responde "ocupado"
      if (origem === 'remote') s.terminate({ status_code: 486, reason_phrase: 'Busy Here' });
      return;
    }
    this.sessao = s;
    this.silenciado = false;
    const recebida = origem === 'remote';
    const numero = s.remote_identity.uri.user || '';
    const nome = s.remote_identity.display_name || numero;

    let audioLigado = false;
    const ligarAudio = (pc) => {
      if (audioLigado || !pc) return;
      audioLigado = true;
      pc.addEventListener('track', (e) => {
        const a = $('#audio-remoto');
        a.srcObject = e.streams[0];
        a.play().catch(() => {});
      });
    };
    s.on('peerconnection', (e) => ligarAudio(e.peerconnection));
    ligarAudio(s.connection);

    s.on('progress', () => UI.estadoChamada(recebida ? 'A receber chamada' : 'A tocar…'));
    s.on('accepted', () => this.emCurso());
    s.on('confirmed', () => this.emCurso());
    s.on('ended', (e) => this.fim(MOTIVOS[e.cause] || 'Chamada terminada'));
    s.on('failed', (e) => this.fim(MOTIVOS[e.cause] || `Falhou: ${e.cause}`));

    UI.abrirChamada({ nome, numero, recebida });
    if (recebida) {
      Toque.tocar();
      Avisos.mostrar('Chamada de ' + nome, 'IssaFone', 'chamada');
    }
  },

  emCurso() {
    Toque.parar();
    if (this.inicio) return;
    this.inicio = Date.now();
    UI.chamadaAtiva();
    this.relogio = setInterval(() => UI.tempo(Date.now() - this.inicio), 1000);
  },

  atender() { Toque.parar(); this.sessao?.answer(this.opcoes()); },
  desligar() { try { this.sessao?.terminate(); } catch { this.fim('Chamada terminada'); } },

  alternarSilencio() {
    if (!this.sessao) return false;
    this.silenciado = !this.silenciado;
    this.silenciado ? this.sessao.mute({ audio: true }) : this.sessao.unmute({ audio: true });
    return this.silenciado;
  },

  dtmf(tecla) { try { this.sessao?.sendDTMF(tecla); } catch { /* ignora */ } },

  fim(texto) {
    Toque.parar();
    clearInterval(this.relogio);
    this.relogio = null;
    this.inicio = null;
    this.sessao = null;
    $('#audio-remoto').srcObject = null;
    UI.fecharChamada(texto);
  },

  sair() {
    try { this.sessao?.terminate(); } catch { /* ignora */ }
    try { this.ua?.stop(); } catch { /* ignora */ }
    this.ua = null;
  },
};

/* ======================= Chat (XMPP / Openfire) ======================= */

const Xmpp = {
  con: null, contactos: new Map(), manual: false, tentativas: 0,

  jid(utilizador) { return utilizador.includes('@') ? utilizador : `${utilizador}@${cfg.dominioXmpp}`; },

  ligar() {
    this.manual = false;
    this.con = new Strophe.Connection(cfg.wsXmpp, { protocol: 'wss' });
    const recurso = 'web-' + Math.random().toString(36).slice(2, 7);
    this.con.connect(`${cfg.extensao}@${cfg.dominioXmpp}/${recurso}`, cfg.passXmpp, (st) => this.mudouEstado(st));
  },

  mudouEstado(st) {
    const S = Strophe.Status;
    if (st === S.CONNECTING) UI.estado('xmpp', 'aviso', 'a ligar…');
    else if (st === S.AUTHENTICATING) UI.estado('xmpp', 'aviso', 'a autenticar…');
    else if (st === S.AUTHFAIL) { this.manual = true; UI.estado('xmpp', 'erro', 'utilizador ou palavra-passe errados'); }
    else if (st === S.CONNFAIL) UI.estado('xmpp', 'erro', 'sem ligação (WSS 7443 e certificado?)');
    else if (st === S.CONNECTED) { this.tentativas = 0; UI.estado('xmpp', 'ok', 'ligado'); this.aposLigar(); }
    else if (st === S.DISCONNECTED) {
      for (const c of this.contactos.values()) c.online = false;
      UI.contactos();
      if (this.manual) { UI.estado('xmpp', '', 'desligado'); return; }
      const espera = Math.min(60, 5 * 2 ** this.tentativas++);
      UI.estado('xmpp', 'erro', `desligado — nova tentativa em ${espera}s`);
      setTimeout(() => { if (!this.manual && cfg) this.ligar(); }, espera * 1000);
    }
  },

  aposLigar() {
    this.con.addHandler((m) => this.recebeuMensagem(m), null, 'message');
    this.con.addHandler((p) => this.recebeuPresenca(p), null, 'presence');
    this.con.addHandler((iq) => this.recebeuRoster(iq), 'jabber:iq:roster', 'iq', 'set');
    this.con.sendIQ($iq({ type: 'get' }).c('query', { xmlns: 'jabber:iq:roster' }), (r) => {
      this.lerRoster(r);
      this.con.send($pres()); // fica "online"
    });
  },

  lerRoster(iq) {
    iq.querySelectorAll('item').forEach((it) => {
      const jid = it.getAttribute('jid');
      if (it.getAttribute('subscription') === 'remove') { this.contactos.delete(jid); return; }
      const antes = this.contactos.get(jid);
      this.contactos.set(jid, { jid, nome: it.getAttribute('name') || jid.split('@')[0], online: antes?.online || false });
    });
    UI.contactos();
  },

  recebeuRoster(iq) {
    this.lerRoster(iq);
    this.con.send($iq({ type: 'result', id: iq.getAttribute('id') }));
    return true;
  },

  recebeuPresenca(p) {
    const de = Strophe.getBareJidFromJid(p.getAttribute('from') || '');
    const tipo = p.getAttribute('type');
    if (tipo === 'subscribe') { // aceita pedidos de colegas e pede o mesmo de volta
      this.con.send($pres({ to: de, type: 'subscribed' }));
      if (!this.contactos.has(de)) this.adicionar(de);
      return true;
    }
    const c = this.contactos.get(de);
    if (c && (tipo === null || tipo === 'unavailable')) {
      c.online = tipo === null;
      UI.contactos();
    }
    return true;
  },

  recebeuMensagem(m) {
    const corpo = m.querySelector('body');
    const tipo = m.getAttribute('type') || 'normal';
    if (!corpo || !['chat', 'normal'].includes(tipo)) return true;
    const de = Strophe.getBareJidFromJid(m.getAttribute('from'));
    Historico.juntar(de, corpo.textContent, false);
    if (!this.contactos.has(de)) this.contactos.set(de, { jid: de, nome: de.split('@')[0], online: true });
    UI.novaMensagem(de, corpo.textContent);
    return true;
  },

  enviar(para, texto) {
    if (!this.con?.connected) { UI.aviso('O chat não está ligado.'); return false; }
    this.con.send($msg({ to: para, type: 'chat' }).c('body').t(texto));
    Historico.juntar(para, texto, true);
    return true;
  },

  adicionar(utilizador) {
    const jid = this.jid(utilizador.trim());
    this.con.sendIQ($iq({ type: 'set' }).c('query', { xmlns: 'jabber:iq:roster' }).c('item', { jid, name: jid.split('@')[0] }));
    this.con.send($pres({ to: jid, type: 'subscribe' }));
  },

  sair() {
    this.manual = true;
    try { this.con?.disconnect(); } catch { /* ignora */ }
    this.con = null;
    this.contactos.clear();
  },
};

/* ======================= Interface ======================= */

const UI = {
  conversaCom: null,
  porLer: new Map(),

  iniciais(nome) { return (nome || '?').replace(/@.*/, '').slice(0, 2).toUpperCase(); },

  vista(v) {
    $('.corpo').dataset.vista = v;
    document.querySelectorAll('.separadores button').forEach((b) =>
      b.classList.toggle('ativo', b.dataset.vista === v || (v === 'conversa' && b.dataset.vista === 'contactos')));
  },

  estado(qual, tipo, texto) {
    const el = $(qual === 'sip' ? '#estado-sip' : '#estado-xmpp');
    el.className = 'estado ' + tipo;
    el.lastChild.textContent = (qual === 'sip' ? 'Telefone: ' : 'Chat: ') + texto;
  },

  aviso(texto) { alert(texto); },

  botaoNotificacoes() {
    $('#btn-notificacoes').hidden = !('Notification' in window) || Notification.permission !== 'default';
  },

  /* ---- Contactos ---- */
  contactos() {
    const ul = $('#lista-contactos');
    const lista = [...Xmpp.contactos.values()].sort((a, b) => (b.online - a.online) || a.nome.localeCompare(b.nome, 'pt'));
    ul.replaceChildren(...lista.map((c) => {
      const li = document.createElement('li');
      li.className = c.online ? 'online' : '';
      const b = document.createElement('button');
      b.className = c.jid === this.conversaCom ? 'ativo' : '';
      const av = document.createElement('span');
      av.className = 'iniciais';
      av.textContent = this.iniciais(c.nome);
      const info = document.createElement('span');
      info.className = 'contacto-info';
      const n = document.createElement('strong');
      n.textContent = c.nome;
      const sub = document.createElement('small');
      const ultima = Historico.de(c.jid).at(-1);
      sub.textContent = ultima ? (ultima.minha ? 'Tu: ' : '') + ultima.texto : (c.online ? 'Disponível' : 'Ausente');
      info.append(n, sub);
      b.append(av, info);
      const nPorLer = this.porLer.get(c.jid);
      if (nPorLer) {
        const k = document.createElement('span');
        k.className = 'contador';
        k.textContent = nPorLer;
        b.append(k);
      }
      b.onclick = () => this.abrirConversa(c.jid);
      li.append(b);
      return li;
    }));
    $('#sem-contactos').hidden = lista.length > 0;
    this.contadorTotal();
  },

  contadorTotal() {
    const total = [...this.porLer.values()].reduce((a, b) => a + b, 0);
    $('#por-ler').hidden = total === 0;
    $('#por-ler').textContent = total;
    document.title = total ? `(${total}) IssaFone` : 'IssaFone';
  },

  /* ---- Conversa ---- */
  abrirConversa(jid) {
    this.conversaCom = jid;
    this.porLer.delete(jid);
    const c = Xmpp.contactos.get(jid);
    $('#conversa-nome').textContent = c?.nome || jid.split('@')[0];
    $('#btn-ligar-contacto').hidden = false;
    $('#form-mensagem').hidden = false;
    this.mensagens();
    this.contactos();
    this.vista('conversa');
    if (matchMedia('(min-width: 900px)').matches) $('#form-mensagem input').focus();
  },

  mensagens() {
    const ol = $('#mensagens');
    const fmt = new Intl.DateTimeFormat('pt-PT', { hour: '2-digit', minute: '2-digit' });
    ol.replaceChildren(...Historico.de(this.conversaCom).map((m) => {
      const li = document.createElement('li');
      li.className = m.minha ? 'minha' : '';
      li.textContent = m.texto;
      const t = document.createElement('time');
      t.textContent = fmt.format(m.hora);
      li.append(t);
      return li;
    }));
    ol.scrollTop = ol.scrollHeight;
  },

  novaMensagem(de, texto) {
    const largo = matchMedia('(min-width: 900px)').matches;
    const aVer = de === this.conversaCom && !document.hidden && (largo || $('.corpo').dataset.vista === 'conversa');
    if (de === this.conversaCom) this.mensagens();
    if (!aVer) this.porLer.set(de, (this.porLer.get(de) || 0) + 1);
    this.contactos();
    const nome = Xmpp.contactos.get(de)?.nome || de.split('@')[0];
    Avisos.mostrar(nome, texto, 'msg-' + de, () => this.abrirConversa(de));
  },

  /* ---- Chamada ---- */
  abrirChamada({ nome, numero, recebida }) {
    const el = $('#chamada');
    el.hidden = false;
    el.classList.toggle('a-tocar', recebida);
    $('#chamada-avatar').textContent = this.iniciais(nome);
    $('#chamada-remoto').textContent = nome;
    $('#chamada-estado').textContent = recebida ? 'A receber chamada' : 'A chamar…';
    $('#chamada-tempo').hidden = true;
    $('#chamada-dtmf').hidden = true;
    $('#btn-atender').hidden = !recebida;
    $('#btn-mute').hidden = true;
    $('#btn-teclas').hidden = true;
    $('#btn-desligar').textContent = recebida ? 'Recusar' : 'Desligar';
    $('#btn-mute').classList.remove('ligado');
  },
  estadoChamada(t) { $('#chamada-estado').textContent = t; },
  chamadaAtiva() {
    $('#chamada').classList.remove('a-tocar');
    $('#chamada-estado').textContent = 'Em chamada';
    $('#chamada-tempo').hidden = false;
    $('#chamada-tempo').textContent = '00:00';
    $('#btn-atender').hidden = true;
    $('#btn-mute').hidden = false;
    $('#btn-teclas').hidden = false;
    $('#btn-desligar').textContent = 'Desligar';
  },
  tempo(ms) {
    const s = Math.floor(ms / 1000);
    $('#chamada-tempo').textContent = `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
  },
  fecharChamada(texto) {
    $('#chamada').classList.remove('a-tocar');
    $('#chamada-estado').textContent = texto;
    ['#btn-atender', '#btn-mute', '#btn-teclas', '#chamada-dtmf'].forEach((s) => { $(s).hidden = true; });
    setTimeout(() => { if (!Sip.sessao) $('#chamada').hidden = true; }, 1500);
  },
};

/* ======================= Ligações dos botões ======================= */

const LETRAS = { 2: 'ABC', 3: 'DEF', 4: 'GHI', 5: 'JKL', 6: 'MNO', 7: 'PQRS', 8: 'TUV', 9: 'WXYZ', 0: '+' };

function montarTeclados() {
  const teclas = '123456789*0#'.split('');
  $('#teclas').replaceChildren(...teclas.map((t) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.innerHTML = `${t}<small>${LETRAS[t] || '&nbsp;'}</small>`;
    b.onclick = () => { $('#numero').value += t; };
    return b;
  }));
  $('#chamada-dtmf').replaceChildren(...teclas.map((t) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = t;
    b.onclick = () => Sip.dtmf(t);
    return b;
  }));
}

function ligarEventos() {
  $('#btn-apagar').onclick = () => { const n = $('#numero'); n.value = n.value.slice(0, -1); };
  $('#btn-ligar').onclick = () => Sip.chamar($('#numero').value);
  $('#numero').addEventListener('keydown', (e) => { if (e.key === 'Enter') Sip.chamar(e.target.value); });

  // No PC: escrever números em qualquer sítio vai para o teclado
  document.addEventListener('keydown', (e) => {
    if (e.target.matches('input') || $('#ecra-app').hidden) return;
    if (/^[0-9*#]$/.test(e.key)) { Sip.sessao ? Sip.dtmf(e.key) : ($('#numero').value += e.key); }
    else if (e.key === 'Backspace' && !Sip.sessao) $('#btn-apagar').click();
    else if (e.key === 'Enter' && !Sip.sessao) $('#btn-ligar').click();
  });

  document.querySelectorAll('.separadores button').forEach((b) => {
    b.onclick = () => UI.vista(b.dataset.vista === 'contactos' && UI.conversaCom && $('.corpo').dataset.vista === 'teclado' ? 'conversa' : b.dataset.vista);
  });
  $('#btn-voltar').onclick = () => UI.vista('contactos');

  $('#form-contacto').onsubmit = (e) => {
    e.preventDefault();
    const v = e.target.novo.value.trim();
    if (!v) return;
    if (!Xmpp.con?.connected) { UI.aviso('O chat não está ligado.'); return; }
    Xmpp.adicionar(v);
    e.target.reset();
  };

  $('#form-mensagem').onsubmit = (e) => {
    e.preventDefault();
    const texto = e.target.texto.value.trim();
    if (!texto || !UI.conversaCom) return;
    if (Xmpp.enviar(UI.conversaCom, texto)) {
      e.target.reset();
      UI.mensagens();
      UI.contactos();
    }
  };

  $('#btn-ligar-contacto').onclick = () => {
    if (UI.conversaCom) Sip.chamar(UI.conversaCom.split('@')[0]);
  };

  $('#btn-atender').onclick = () => Sip.atender();
  $('#btn-desligar').onclick = () => Sip.desligar();
  $('#btn-mute').onclick = (e) => {
    const s = Sip.alternarSilencio();
    e.target.classList.toggle('ligado', s);
    e.target.textContent = s ? 'Ativar microfone' : 'Silenciar';
  };
  $('#btn-teclas').onclick = () => { $('#chamada-dtmf').hidden = !$('#chamada-dtmf').hidden; };

  $('#btn-notificacoes').onclick = () => Avisos.pedir();
  $('#btn-sair').onclick = () => sair();

  $('#form-login').onsubmit = (e) => {
    e.preventDefault();
    const dados = Object.fromEntries(new FormData(e.target));
    Toque.desbloquear();
    entrar(completarConfig(dados));
  };

  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && UI.conversaCom && $('.corpo').dataset.vista !== 'teclado') {
      UI.porLer.delete(UI.conversaCom);
      UI.contactos();
    }
  });

  // Avisa antes de fechar a página a meio de uma chamada
  window.addEventListener('beforeunload', (e) => { if (Sip.sessao) { e.preventDefault(); e.returnValue = ''; } });
}

/* ======================= Sessão ======================= */

function verificarRequisitos() {
  if (!window.isSecureContext) return 'Esta página tem de ser aberta em HTTPS (os browsers só dão acesso ao microfone em ligações seguras).';
  if (!window.RTCPeerConnection || !navigator.mediaDevices?.getUserMedia) return 'Este browser não suporta chamadas WebRTC.';
  return null;
}

function mostrarLogin(c) {
  $('#ecra-app').hidden = true;
  $('#ecra-login').hidden = false;
  const f = $('#form-login');
  f.elements.servidor.value = SERVIDOR_PADRAO;
  if (c) for (const [k, v] of Object.entries(c)) if (f.elements[k] && typeof v === 'string') f.elements[k].value = v;
  const erro = verificarRequisitos();
  $('#erro-login').hidden = !erro;
  $('#erro-login').textContent = erro || '';
}

function entrar(c) {
  const erro = verificarRequisitos();
  if (erro) { $('#erro-login').textContent = erro; $('#erro-login').hidden = false; return; }
  cfg = c;
  guardar(CHAVE_CONFIG, { ...c, sessao: true });
  $('#ecra-login').hidden = true;
  $('#ecra-app').hidden = false;
  UI.porLer.clear();
  UI.conversaCom = null;
  UI.vista('teclado');
  UI.botaoNotificacoes();
  Sip.ligar();
  Xmpp.ligar();
}

function sair() {
  if (Sip.sessao && !confirm('Estás em chamada. Sair mesmo assim?')) return;
  Sip.sair();
  Xmpp.sair();
  const c = ler(CHAVE_CONFIG);
  if (c) guardar(CHAVE_CONFIG, { ...c, sessao: false });
  cfg = null;
  mostrarLogin(c);
}

/* ======================= Arranque ======================= */

montarTeclados();
ligarEventos();
const guardada = ler(CHAVE_CONFIG);
if (guardada?.sessao && !verificarRequisitos()) entrar(completarConfig(guardada));
else mostrarLogin(guardada);

if ('serviceWorker' in navigator && window.isSecureContext) {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}
