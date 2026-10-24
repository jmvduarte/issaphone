/*
 * IssaFone Web — chamadas e videochamadas (SIP sobre WebRTC, JsSIP) e chat com
 * grupos (XMPP sobre WebSocket, Strophe.js) ligados ao Issabel 4 / Openfire.
 * Sem build: abre o index.html.
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
  /**
   * Junta uma mensagem. Nos grupos, o servidor reenvia o histórico sempre que se
   * entra na sala: mensagens iguais do mesmo autor com menos de 3 min de diferença
   * são consideradas a mesma e ignoradas. Devolve true se for nova.
   */
  juntar(jid, texto, minha, autor = null, hora = Date.now()) {
    const t = this.tudo();
    const lista = t[jid] || [];
    if (autor && lista.some((m) => m.autor === autor && m.texto === texto && Math.abs(m.hora - hora) < 180000)) return false;
    lista.push({ texto, minha, autor, hora });
    lista.sort((a, b) => a.hora - b.hora);
    t[jid] = lista.slice(-300);
    guardar(this.chave(), t);
    return true;
  },
  apagar(jid) { const t = this.tudo(); delete t[jid]; guardar(this.chave(), t); },
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
    for (const freq of [440, 480]) {
      const o = this.ctx.createOscillator();
      const g = this.ctx.createGain();
      o.frequency.value = freq;
      g.gain.setValueAtTime(0.0001, t);
      g.gain.exponentialRampToValueAtTime(0.12, t + 0.05);
      g.gain.setValueAtTime(0.12, t + 1.2);
      g.gain.exponentialRampToValueAtTime(0.0001, t + 1.3);
      o.connect(g).connect(this.ctx.destination);
      o.start(t);
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

/* ======================= Chamadas e videochamadas (SIP / WebRTC) ======================= */

const MOTIVOS = {
  Busy: 'Ocupado', Rejected: 'Chamada recusada', Unavailable: 'Indisponível',
  'Not Found': 'A extensão não existe', Canceled: 'Cancelada', 'No Answer': 'Sem resposta',
  'User Denied Media Access': 'Sem acesso ao microfone ou à câmara', 'Connection Error': 'Erro de ligação',
  'Request Timeout': 'Sem resposta do servidor', 'Address Incomplete': 'Número incompleto',
  'Incompatible SDP': 'Áudio/vídeo incompatível (a extensão está configurada para WebRTC?)',
  'Authentication Error': 'Erro de autenticação', Bye: 'Chamada terminada', Terminated: 'Chamada terminada',
};

const Sip = {
  ua: null, sessao: null, inicio: null, relogio: null, silenciado: false, camaraDesligada: false,

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
    this.ua.on('newRTCSession', (e) => this.novaSessao(e));
    this.ua.start();
  },

  opcoes(video) {
    return {
      mediaConstraints: {
        audio: true,
        video: video ? { facingMode: 'user', width: { ideal: 1280 }, height: { ideal: 720 } } : false,
      },
      pcConfig: { iceServers: cfg.stun ? [{ urls: cfg.stun }] : [] },
    };
  },

  chamar(destino, video = false) {
    destino = destino.trim();
    if (!destino || !this.ua || this.sessao) return;
    const uri = destino.includes('@') ? `sip:${destino}` : `sip:${destino}@${cfg.servidor}`;
    try {
      this.ua.call(uri, this.opcoes(video));
    } catch (e) {
      UI.aviso(`Não foi possível ligar: ${e.message}`);
    }
  },

  novaSessao({ session: s, originator, request }) {
    if (this.sessao) { // já em chamada: responde "ocupado"
      if (originator === 'remote') s.terminate({ status_code: 486, reason_phrase: 'Busy Here' });
      return;
    }
    this.sessao = s;
    this.silenciado = false;
    this.camaraDesligada = false;
    const recebida = originator === 'remote';
    const ofereceVideo = recebida && /\bm=video\s+[1-9]/.test(request?.body || '');
    const numero = s.remote_identity.uri.user || '';
    const nome = s.remote_identity.display_name || numero;

    let ligado = false;
    const ligarMedia = (pc) => {
      if (ligado || !pc) return;
      ligado = true;
      pc.addEventListener('track', (e) => {
        const v = $('#video-remoto');
        if (v.srcObject !== e.streams[0]) v.srcObject = e.streams[0];
        v.play().catch(() => {});
        this.atualizarVideo();
        e.track.addEventListener('unmute', () => this.atualizarVideo());
        e.track.addEventListener('ended', () => this.atualizarVideo());
      });
    };
    s.on('peerconnection', (e) => ligarMedia(e.peerconnection));
    ligarMedia(s.connection);

    s.on('progress', () => UI.estadoChamada(recebida ? 'A receber chamada' : 'A tocar…'));
    s.on('accepted', () => this.emCurso());
    s.on('confirmed', () => this.emCurso());
    s.on('ended', (e) => this.fim(MOTIVOS[e.cause] || 'Chamada terminada'));
    s.on('failed', (e) => this.fim(MOTIVOS[e.cause] || `Falhou: ${e.cause}`));

    UI.abrirChamada({ nome, numero, recebida, ofereceVideo });
    if (recebida) {
      Toque.tocar();
      Avisos.mostrar((ofereceVideo ? 'Videochamada de ' : 'Chamada de ') + nome, 'IssaFone', 'chamada');
    }
  },

  /** Mostra ou esconde os vídeos conforme haja vídeo a sair ou a chegar. */
  atualizarVideo() {
    const pc = this.sessao?.connection;
    if (!pc) { UI.videos(false); return; }
    const recebe = pc.getReceivers().some((r) => r.track?.kind === 'video' && r.track.readyState === 'live' && !r.track.muted);
    const envia = pc.getSenders().find((x) => x.track?.kind === 'video' && x.track.readyState === 'live');
    const local = $('#video-local');
    if (envia && (!local.srcObject || local.srcObject.getVideoTracks()[0] !== envia.track)) {
      local.srcObject = new MediaStream([envia.track]);
    }
    local.hidden = !envia || this.camaraDesligada;
    UI.videos(Boolean(recebe || envia), Boolean(envia));
  },

  emCurso() {
    Toque.parar();
    this.atualizarVideo();
    if (this.inicio) return;
    this.inicio = Date.now();
    UI.chamadaAtiva();
    this.relogio = setInterval(() => UI.tempo(Date.now() - this.inicio), 1000);
    setTimeout(() => this.atualizarVideo(), 1500); // o vídeo remoto às vezes chega um pouco depois
  },

  atender(video = false) { Toque.parar(); this.sessao?.answer(this.opcoes(video)); },
  desligar() { try { this.sessao?.terminate(); } catch { this.fim('Chamada terminada'); } },

  alternarSilencio() {
    if (!this.sessao) return false;
    this.silenciado = !this.silenciado;
    this.silenciado ? this.sessao.mute({ audio: true }) : this.sessao.unmute({ audio: true });
    return this.silenciado;
  },

  alternarCamara() {
    if (!this.sessao) return false;
    this.camaraDesligada = !this.camaraDesligada;
    this.camaraDesligada ? this.sessao.mute({ video: true }) : this.sessao.unmute({ video: true });
    this.atualizarVideo();
    return this.camaraDesligada;
  },

  dtmf(tecla) { try { this.sessao?.sendDTMF(tecla); } catch { /* ignora */ } },

  fim(texto) {
    Toque.parar();
    clearInterval(this.relogio);
    this.relogio = null;
    this.inicio = null;
    this.sessao = null;
    $('#video-remoto').srcObject = null;
    $('#video-local').srcObject = null;
    UI.videos(false);
    UI.fecharChamada(texto);
  },

  sair() {
    try { this.sessao?.terminate(); } catch { /* ignora */ }
    try { this.ua?.stop(); } catch { /* ignora */ }
    this.ua = null;
  },
};

/* ======================= Chat e grupos (XMPP / Openfire) ======================= */

const NS = {
  ROSTER: 'jabber:iq:roster',
  MUC: 'http://jabber.org/protocol/muc',
  MUC_USER: 'http://jabber.org/protocol/muc#user',
  MUC_OWNER: 'http://jabber.org/protocol/muc#owner',
  MUC_ADMIN: 'http://jabber.org/protocol/muc#admin',
  DADOS: 'jabber:x:data',
  PRIVADO: 'jabber:iq:private',
  MARCADORES: 'storage:bookmarks',
};

const Xmpp = {
  con: null, contactos: new Map(), grupos: new Map(), manual: false, tentativas: 0,

  jid(utilizador) { return utilizador.includes('@') ? utilizador : `${utilizador}@${cfg.dominioXmpp}`; },
  get servicoGrupos() { return `conference.${cfg.dominioXmpp}`; },
  get eu() { return `${cfg.extensao}@${cfg.dominioXmpp}`; },
  get alcunha() { return cfg.extensao; },
  ehGrupo(jid) { return this.grupos.has(jid) || jid.endsWith('@' + this.servicoGrupos); },

  ligar() {
    this.manual = false;
    this.con = new Strophe.Connection(cfg.wsXmpp, { protocol: 'wss' });
    const recurso = 'web-' + Math.random().toString(36).slice(2, 7);
    this.con.connect(`${this.eu}/${recurso}`, cfg.passXmpp, (st) => this.mudouEstado(st));
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
      for (const g of this.grupos.values()) { g.dentro = false; g.ocupantes.clear(); }
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
    this.con.addHandler((iq) => this.recebeuRoster(iq), NS.ROSTER, 'iq', 'set');
    this.con.sendIQ($iq({ type: 'get' }).c('query', { xmlns: NS.ROSTER }), (r) => {
      this.lerRoster(r);
      this.con.send($pres()); // fica "online" (o servidor entrega também as mensagens e convites pendentes)
      this.carregarMarcadores();
    });
  },

  /* ---------- Contactos ---------- */

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
    if (this.ehGrupo(de)) { this.presencaGrupo(p, de); return true; }
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
    const de = Strophe.getBareJidFromJid(m.getAttribute('from') || '');
    const tipo = m.getAttribute('type') || 'normal';

    // Convite para um grupo (enviado pela sala): entra automaticamente
    const convite = m.getElementsByTagNameNS(NS.MUC_USER, 'invite')[0];
    if (convite) {
      const razao = convite.getElementsByTagName('reason')[0]?.textContent;
      this.aceitarConvite(de, razao || de.split('@')[0]);
      return true;
    }

    const corpo = m.getElementsByTagName('body')[0];
    if (!corpo) return true;
    const texto = corpo.textContent;

    if (tipo === 'groupchat') {
      const autor = Strophe.getResourceFromJid(m.getAttribute('from')) || '';
      const atraso = m.getElementsByTagName('delay')[0]?.getAttribute('stamp');
      const hora = atraso ? Date.parse(atraso) : Date.now();
      const nova = Historico.juntar(de, texto, autor === this.alcunha, autor, hora);
      if (nova) UI.novaMensagem(de, texto, autor, Boolean(atraso) || autor === this.alcunha);
      return true;
    }
    if (!['chat', 'normal'].includes(tipo)) return true;
    Historico.juntar(de, texto, false);
    if (!this.contactos.has(de)) this.contactos.set(de, { jid: de, nome: de.split('@')[0], online: true });
    UI.novaMensagem(de, texto);
    return true;
  },

  enviar(para, texto) {
    if (!this.con?.connected) { UI.aviso('O chat não está ligado.'); return false; }
    if (this.ehGrupo(para)) {
      // A sala devolve a mensagem a todos, incluindo a quem a enviou: é aí que entra no histórico
      this.con.send($msg({ to: para, type: 'groupchat' }).c('body').t(texto));
      return true;
    }
    this.con.send($msg({ to: para, type: 'chat' }).c('body').t(texto));
    Historico.juntar(para, texto, true);
    return true;
  },

  adicionar(utilizador) {
    const jid = this.jid(utilizador.trim());
    this.con.sendIQ($iq({ type: 'set' }).c('query', { xmlns: NS.ROSTER }).c('item', { jid, name: jid.split('@')[0] }));
    this.con.send($pres({ to: jid, type: 'subscribe' }));
  },

  /* ---------- Grupos (salas MUC, XEP-0045) ---------- */

  novoGrupo(jid, nome, dono = false) {
    if (!this.grupos.has(jid)) {
      this.grupos.set(jid, { jid, nome, dono, dentro: false, ocupantes: new Set(), aCriar: null });
    }
    return this.grupos.get(jid);
  },

  /** Os grupos de cada utilizador ficam guardados no servidor (marcadores, XEP-0048). */
  carregarMarcadores() {
    this.con.sendIQ(
      $iq({ type: 'get' }).c('query', { xmlns: NS.PRIVADO }).c('storage', { xmlns: NS.MARCADORES }),
      (r) => {
        r.querySelectorAll('conference').forEach((c) => {
          const g = this.novoGrupo(c.getAttribute('jid'), c.getAttribute('name') || c.getAttribute('jid').split('@')[0]);
          this.entrar(g.jid);
        });
        UI.contactos();
      },
      () => { /* sem marcadores ainda */ }
    );
  },

  gravarMarcadores() {
    const iq = $iq({ type: 'set' }).c('query', { xmlns: NS.PRIVADO }).c('storage', { xmlns: NS.MARCADORES });
    for (const g of this.grupos.values()) {
      iq.c('conference', { jid: g.jid, name: g.nome, autojoin: 'true' }).c('nick').t(this.alcunha).up().up();
    }
    this.con.sendIQ(iq);
  },

  entrar(jid, comHistorico = true) {
    const p = $pres({ to: `${jid}/${this.alcunha}` }).c('x', { xmlns: NS.MUC });
    if (comHistorico) p.c('history', { maxstanzas: '50' });
    this.con.send(p);
  },

  presencaGrupo(p, sala) {
    const g = this.grupos.get(sala);
    if (!g) return;
    const alcunha = Strophe.getResourceFromJid(p.getAttribute('from')) || '';
    const tipo = p.getAttribute('type');
    const x = p.getElementsByTagNameNS(NS.MUC_USER, 'x')[0];
    const codigos = x ? [...x.getElementsByTagName('status')].map((s) => s.getAttribute('code')) : [];
    const item = x?.getElementsByTagName('item')[0];
    const euProprio = codigos.includes('110') || alcunha === this.alcunha;

    if (tipo === 'error') {
      // Já não é membro (ou o grupo foi apagado): deixa de aparecer
      if (euProprio) this.esquecer(sala, 'Já não pertences ao grupo ' + g.nome);
      return;
    }
    if (tipo === 'unavailable') {
      if (euProprio) {
        const destruido = x?.getElementsByTagName('destroy').length;
        if (codigos.includes('321') || codigos.includes('301') || destruido) {
          this.esquecer(sala, destruido ? `O grupo ${g.nome} foi apagado` : `Foste removido do grupo ${g.nome}`);
        } else {
          g.dentro = false;
        }
      } else {
        g.ocupantes.delete(alcunha);
      }
      UI.contactos();
      UI.membrosAtualizados(sala);
      return;
    }
    if (euProprio) {
      g.dentro = true;
      g.dono = item?.getAttribute('affiliation') === 'owner';
      if (codigos.includes('201') && g.aCriar) this.configurar(g); // sala acabada de criar
    } else {
      g.ocupantes.add(alcunha);
    }
    UI.contactos();
    UI.membrosAtualizados(sala);
  },

  /** Cria uma sala permanente, só para membros, e convida os membros escolhidos. */
  criarGrupo(nome, membros) {
    const base = nome.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase()
      .replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 24) || 'grupo';
    const jid = `${base}-${Math.random().toString(36).slice(2, 6)}@${this.servicoGrupos}`;
    const g = this.novoGrupo(jid, nome, true);
    g.aCriar = { membros };
    this.entrar(jid, false);
    UI.contactos();
    return jid;
  },

  configurar(g) {
    const campo = (iq, nome, valor) => iq.c('field', { var: nome }).c('value').t(valor).up().up();
    const iq = $iq({ to: g.jid, type: 'set' }).c('query', { xmlns: NS.MUC_OWNER }).c('x', { xmlns: NS.DADOS, type: 'submit' });
    campo(iq, 'FORM_TYPE', 'http://jabber.org/protocol/muc#roomconfig');
    campo(iq, 'muc#roomconfig_roomname', g.nome);
    campo(iq, 'muc#roomconfig_persistentroom', '1');
    campo(iq, 'muc#roomconfig_membersonly', '1');
    campo(iq, 'muc#roomconfig_publicroom', '0');
    campo(iq, 'muc#roomconfig_whois', 'anyone');
    campo(iq, 'muc#roomconfig_allowinvites', '0');
    const { membros } = g.aCriar;
    g.aCriar = null;
    this.con.sendIQ(iq, () => {
      this.definirMembros(g.jid, membros, 'member');
      this.gravarMarcadores();
    }, () => UI.aviso('O servidor recusou a configuração do grupo.'));
  },

  definirMembros(sala, jids, afiliacao) {
    if (!jids.length) return;
    const iq = $iq({ to: sala, type: 'set' }).c('query', { xmlns: NS.MUC_ADMIN });
    for (const j of jids) iq.c('item', { jid: j, affiliation: afiliacao }).up();
    this.con.sendIQ(iq, () => {
      if (afiliacao === 'member') this.convidar(sala, jids);
      UI.membrosAtualizados(sala);
    }, () => UI.aviso('Não foi possível alterar os membros do grupo.'));
  },

  convidar(sala, jids) {
    const g = this.grupos.get(sala);
    const m = $msg({ to: sala }).c('x', { xmlns: NS.MUC_USER });
    for (const j of jids) m.c('invite', { to: j }).c('reason').t(g?.nome || sala).up().up();
    this.con.send(m);
  },

  aceitarConvite(sala, nome) {
    const existia = this.grupos.has(sala);
    const g = this.novoGrupo(sala, nome);
    if (!existia) {
      this.gravarMarcadores();
      UI.aviso(`Foste adicionado ao grupo ${g.nome}.`, true);
    }
    if (!g.dentro) this.entrar(sala);
    UI.contactos();
  },

  /** Lista membros e donos (só o dono do grupo pode pedir esta lista). */
  listarMembros(sala, depois) {
    const pedir = (afiliacao) => new Promise((ok) => {
      this.con.sendIQ(
        $iq({ to: sala, type: 'get' }).c('query', { xmlns: NS.MUC_ADMIN }).c('item', { affiliation: afiliacao }),
        (r) => ok([...r.getElementsByTagName('item')].map((i) => ({ jid: i.getAttribute('jid'), afiliacao }))),
        () => ok([])
      );
    });
    Promise.all([pedir('owner'), pedir('member')]).then(([donos, membros]) => depois([...donos, ...membros]));
  },

  sairGrupo(sala) {
    this.con.send($pres({ to: `${sala}/${this.alcunha}`, type: 'unavailable' }));
    this.esquecer(sala);
  },

  apagarGrupo(sala) {
    this.con.sendIQ(
      $iq({ to: sala, type: 'set' }).c('query', { xmlns: NS.MUC_OWNER }).c('destroy').c('reason').t('Grupo apagado'),
      () => this.esquecer(sala),
      () => UI.aviso('Não foi possível apagar o grupo.')
    );
  },

  esquecer(sala, aviso) {
    if (!this.grupos.has(sala)) return;
    this.grupos.delete(sala);
    this.gravarMarcadores();
    if (UI.conversaCom === sala) UI.fecharConversa();
    UI.contactos();
    if (aviso) UI.aviso(aviso, true);
  },

  sair() {
    this.manual = true;
    try { this.con?.disconnect(); } catch { /* ignora */ }
    this.con = null;
    this.contactos.clear();
    this.grupos.clear();
  },
};

/* ======================= Interface ======================= */

const UI = {
  conversaCom: null,
  porLer: new Map(),

  iniciais(nome) { return (nome || '?').replace(/@.*/, '').slice(0, 2).toUpperCase(); },
  nomeDe(jid) { return Xmpp.grupos.get(jid)?.nome || Xmpp.contactos.get(jid)?.nome || jid.split('@')[0]; },

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

  /** Avisos importantes abrem uma janela; os discretos só aparecem como notificação. */
  aviso(texto, discreto = false) {
    if (discreto) { Avisos.mostrar('IssaFone', texto, 'aviso'); return; }
    alert(texto);
  },

  botaoNotificacoes() {
    $('#btn-notificacoes').hidden = !('Notification' in window) || Notification.permission !== 'default';
  },

  /* ---- Lista: grupos e contactos ---- */
  linha({ jid, nome, sub, online, grupo }) {
    const li = document.createElement('li');
    li.className = online ? 'online' : '';
    const b = document.createElement('button');
    b.className = jid === this.conversaCom ? 'ativo' : '';
    const av = document.createElement('span');
    av.className = 'iniciais' + (grupo ? ' grupo' : '');
    av.textContent = grupo ? '#' : this.iniciais(nome);
    const info = document.createElement('span');
    info.className = 'contacto-info';
    const n = document.createElement('strong');
    n.textContent = nome;
    const s = document.createElement('small');
    s.textContent = sub;
    info.append(n, s);
    b.append(av, info);
    const nPorLer = this.porLer.get(jid);
    if (nPorLer) {
      const k = document.createElement('span');
      k.className = 'contador';
      k.textContent = nPorLer;
      b.append(k);
    }
    b.onclick = () => this.abrirConversa(jid);
    li.append(b);
    return li;
  },

  ultima(jid) {
    const u = Historico.de(jid).at(-1);
    if (!u) return null;
    return (u.minha ? 'Tu: ' : u.autor ? `${u.autor}: ` : '') + u.texto;
  },

  contactos() {
    const grupos = [...Xmpp.grupos.values()].sort((a, b) => a.nome.localeCompare(b.nome, 'pt'));
    $('#lista-grupos').replaceChildren(...grupos.map((g) => this.linha({
      jid: g.jid, nome: g.nome, grupo: true,
      sub: this.ultima(g.jid) || (g.aCriar ? 'A criar…' : `${g.ocupantes.size + (g.dentro ? 1 : 0)} ligado(s)`),
    })));
    $('#sem-grupos').hidden = grupos.length > 0;

    const lista = [...Xmpp.contactos.values()].sort((a, b) => (b.online - a.online) || a.nome.localeCompare(b.nome, 'pt'));
    $('#lista-contactos').replaceChildren(...lista.map((c) => this.linha({
      jid: c.jid, nome: c.nome, online: c.online,
      sub: this.ultima(c.jid) || (c.online ? 'Disponível' : 'Ausente'),
    })));
    $('#sem-contactos').hidden = lista.length > 0;
    this.contadorTotal();
    if (this.conversaCom) $('#conversa-nome').textContent = this.nomeDe(this.conversaCom);
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
    const grupo = Xmpp.ehGrupo(jid);
    $('#conversa-nome').textContent = this.nomeDe(jid);
    $('#btn-ligar-contacto').hidden = grupo;
    $('#btn-video-contacto').hidden = grupo;
    $('#btn-membros').hidden = !grupo;
    $('#form-mensagem').hidden = false;
    this.mensagens();
    this.contactos();
    this.vista('conversa');
    if (matchMedia('(min-width: 900px)').matches) $('#form-mensagem input').focus();
  },

  fecharConversa() {
    this.conversaCom = null;
    $('#conversa-nome').textContent = 'Escolhe um contacto';
    ['#btn-ligar-contacto', '#btn-video-contacto', '#btn-membros', '#form-mensagem'].forEach((s) => { $(s).hidden = true; });
    $('#mensagens').replaceChildren();
    $('#modal-membros').hidden = true;
    if ($('.corpo').dataset.vista === 'conversa') this.vista('contactos');
  },

  mensagens() {
    const ol = $('#mensagens');
    const fmt = new Intl.DateTimeFormat('pt-PT', { hour: '2-digit', minute: '2-digit' });
    const fmtDia = new Intl.DateTimeFormat('pt-PT', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
    const hoje = new Date().toDateString();
    ol.replaceChildren(...Historico.de(this.conversaCom).map((m) => {
      const li = document.createElement('li');
      li.className = m.minha ? 'minha' : '';
      if (m.autor && !m.minha) {
        const a = document.createElement('span');
        a.className = 'autor';
        a.textContent = m.autor;
        li.append(a);
      }
      li.append(document.createTextNode(m.texto));
      const t = document.createElement('time');
      t.textContent = (new Date(m.hora).toDateString() === hoje ? fmt : fmtDia).format(m.hora);
      li.append(t);
      return li;
    }));
    ol.scrollTop = ol.scrollHeight;
  },

  novaMensagem(de, texto, autor = null, semAviso = false) {
    const largo = matchMedia('(min-width: 900px)').matches;
    const aVer = de === this.conversaCom && !document.hidden && (largo || $('.corpo').dataset.vista === 'conversa');
    if (de === this.conversaCom) this.mensagens();
    if (!aVer && !semAviso) this.porLer.set(de, (this.porLer.get(de) || 0) + 1);
    this.contactos();
    if (!semAviso) {
      const titulo = this.nomeDe(de);
      Avisos.mostrar(titulo, autor ? `${autor}: ${texto}` : texto, 'msg-' + de, () => this.abrirConversa(de));
    }
  },

  /* ---- Novo grupo ---- */
  abrirNovoGrupo() {
    if (!Xmpp.con?.connected) { this.aviso('O chat não está ligado.'); return; }
    const f = $('#form-grupo');
    f.reset();
    $('#erro-grupo').hidden = true;
    const lista = [...Xmpp.contactos.values()].sort((a, b) => a.nome.localeCompare(b.nome, 'pt'));
    $('#escolher-membros').replaceChildren(...(lista.length ? lista.map((c) => {
      const li = document.createElement('li');
      const l = document.createElement('label');
      const cb = document.createElement('input');
      cb.type = 'checkbox';
      cb.value = c.jid;
      cb.onchange = () => { $('#erro-grupo').hidden = true; };
      l.append(cb, document.createTextNode(c.nome));
      li.append(l);
      return li;
    }) : [Object.assign(document.createElement('li'), { textContent: 'Sem contactos para adicionar.' })]));
    $('#modal-grupo').hidden = false;
    f.nome.focus();
  },

  criarGrupo(e) {
    e.preventDefault();
    const f = e.target;
    const nome = f.nome.value.trim();
    const membros = [...f.querySelectorAll('input[type=checkbox]:checked')].map((c) => c.value);
    const erro = !nome ? 'Escreve um nome para o grupo.' : !membros.length ? 'Escolhe pelo menos um membro.' : '';
    if (erro) { $('#erro-grupo').textContent = erro; $('#erro-grupo').hidden = false; return; }
    const jid = Xmpp.criarGrupo(nome, membros);
    $('#modal-grupo').hidden = true;
    this.abrirConversa(jid);
  },

  /* ---- Membros do grupo ---- */
  abrirMembros() {
    const g = Xmpp.grupos.get(this.conversaCom);
    if (!g) return;
    $('#titulo-membros').textContent = `Membros de ${g.nome}`;
    $('#btn-apagar-grupo').hidden = !g.dono;
    $('#btn-sair-grupo').hidden = g.dono;
    $('#modal-membros').hidden = false;
    this.membrosAtualizados(g.jid);
  },

  membrosAtualizados(sala) {
    if ($('#modal-membros').hidden || sala !== this.conversaCom) return;
    const g = Xmpp.grupos.get(sala);
    if (!g) return;
    const ul = $('#lista-membros');
    const nomeJid = (j) => Xmpp.contactos.get(j)?.nome || j.split('@')[0];
    const desenhar = (itens) => {
      ul.replaceChildren(...itens.map(({ jid, afiliacao, ligado }) => {
        const li = document.createElement('li');
        const n = document.createElement('span');
        n.textContent = (jid === Xmpp.eu ? 'Tu' : nomeJid(jid)) + (ligado ? ' ●' : '');
        const p = document.createElement('span');
        p.className = 'papel';
        p.textContent = afiliacao === 'owner' ? 'Dono' : 'Membro';
        li.append(n, p);
        if (g.dono && afiliacao !== 'owner') {
          const b = document.createElement('button');
          b.className = 'discreto perigo pequeno';
          b.textContent = 'Remover';
          b.onclick = () => { if (confirm(`Remover ${nomeJid(jid)} do grupo?`)) Xmpp.definirMembros(sala, [jid], 'none'); };
          li.append(b);
        }
        return li;
      }));
      // Adicionar: só o dono, e só contactos que ainda não são membros
      const f = $('#form-add-membro');
      f.hidden = !g.dono;
      if (g.dono) {
        const ja = new Set(itens.map((i) => i.jid));
        const opcoes = [...Xmpp.contactos.values()].filter((c) => !ja.has(c.jid));
        f.membro.replaceChildren(...opcoes.map((c) => new Option(c.nome, c.jid)));
        f.hidden = opcoes.length === 0;
      }
    };
    if (g.dono) {
      Xmpp.listarMembros(sala, (itens) => desenhar(itens.map((i) => ({
        ...i, ligado: i.jid === Xmpp.eu || g.ocupantes.has(i.jid.split('@')[0]),
      }))));
    } else {
      // Quem não é dono vê quem está ligado no grupo
      desenhar([{ jid: Xmpp.eu, afiliacao: 'member', ligado: true },
        ...[...g.ocupantes].map((a) => ({ jid: Xmpp.jid(a), afiliacao: 'member', ligado: true }))]);
    }
  },

  /* ---- Chamada ---- */
  abrirChamada({ nome, recebida, ofereceVideo }) {
    const el = $('#chamada');
    el.hidden = false;
    el.classList.toggle('a-tocar', recebida);
    $('#chamada-avatar').textContent = this.iniciais(nome);
    $('#chamada-remoto').textContent = nome;
    $('#chamada-estado').textContent = recebida ? (ofereceVideo ? 'Videochamada a chegar' : 'A receber chamada') : 'A chamar…';
    $('#chamada-tempo').hidden = true;
    $('#chamada-dtmf').hidden = true;
    $('#btn-atender').hidden = !recebida;
    $('#btn-atender').textContent = ofereceVideo ? 'Só áudio' : 'Atender';
    $('#btn-atender-video').hidden = !(recebida && ofereceVideo);
    ['#btn-mute', '#btn-teclas', '#btn-camara'].forEach((s) => { $(s).hidden = true; });
    $('#btn-desligar').textContent = recebida ? 'Recusar' : 'Desligar';
    $('#btn-mute').classList.remove('ligado');
    $('#btn-mute').textContent = 'Silenciar';
    $('#btn-camara').classList.remove('ligado');
    $('#btn-camara').textContent = 'Desligar câmara';
  },
  estadoChamada(t) { $('#chamada-estado').textContent = t; },
  chamadaAtiva() {
    $('#chamada').classList.remove('a-tocar');
    $('#chamada-estado').textContent = 'Em chamada';
    $('#chamada-tempo').hidden = false;
    $('#chamada-tempo').textContent = '00:00';
    $('#btn-atender').hidden = true;
    $('#btn-atender-video').hidden = true;
    $('#btn-mute').hidden = false;
    $('#btn-teclas').hidden = false;
    $('#btn-desligar').textContent = 'Desligar';
  },
  videos(mostrar, comCamara = false) {
    $('#videos').hidden = !mostrar;
    $('#chamada').classList.toggle('com-video', mostrar);
    $('#btn-camara').hidden = !(mostrar && comCamara && Sip.inicio);
  },
  tempo(ms) {
    const s = Math.floor(ms / 1000);
    $('#chamada-tempo').textContent = `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
  },
  fecharChamada(texto) {
    $('#chamada').classList.remove('a-tocar');
    $('#chamada-estado').textContent = texto;
    ['#btn-atender', '#btn-atender-video', '#btn-mute', '#btn-teclas', '#btn-camara', '#chamada-dtmf'].forEach((s) => { $(s).hidden = true; });
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
  $('#btn-videochamada').onclick = () => Sip.chamar($('#numero').value, true);
  $('#numero').addEventListener('keydown', (e) => { if (e.key === 'Enter') Sip.chamar(e.target.value); });

  // No PC: escrever números em qualquer sítio vai para o teclado
  document.addEventListener('keydown', (e) => {
    if (e.target.matches('input, select, textarea') || $('#ecra-app').hidden) return;
    if (!$('#modal-grupo').hidden || !$('#modal-membros').hidden) {
      if (e.key === 'Escape') { $('#modal-grupo').hidden = true; $('#modal-membros').hidden = true; }
      return;
    }
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

  $('#btn-ligar-contacto').onclick = () => { if (UI.conversaCom) Sip.chamar(UI.conversaCom.split('@')[0]); };
  $('#btn-video-contacto').onclick = () => { if (UI.conversaCom) Sip.chamar(UI.conversaCom.split('@')[0], true); };

  // Grupos
  $('#btn-novo-grupo').onclick = () => UI.abrirNovoGrupo();
  $('#form-grupo').onsubmit = (e) => UI.criarGrupo(e);
  $('#form-grupo').nome.oninput = () => { $('#erro-grupo').hidden = true; };
  $('#btn-membros').onclick = () => UI.abrirMembros();
  $('#form-add-membro').onsubmit = (e) => {
    e.preventDefault();
    const jid = e.target.membro.value;
    if (jid && UI.conversaCom) Xmpp.definirMembros(UI.conversaCom, [jid], 'member');
  };
  $('#btn-sair-grupo').onclick = () => {
    const g = Xmpp.grupos.get(UI.conversaCom);
    if (g && confirm(`Sair do grupo ${g.nome}?`)) { Xmpp.sairGrupo(g.jid); $('#modal-membros').hidden = true; }
  };
  $('#btn-apagar-grupo').onclick = () => {
    const g = Xmpp.grupos.get(UI.conversaCom);
    if (g && confirm(`Apagar o grupo ${g.nome} para todos os membros?`)) { Xmpp.apagarGrupo(g.jid); $('#modal-membros').hidden = true; }
  };
  document.querySelectorAll('[data-fechar]').forEach((b) => {
    b.onclick = () => { b.closest('.modal').hidden = true; };
  });
  document.querySelectorAll('.modal').forEach((m) => {
    m.addEventListener('click', (e) => { if (e.target === m) m.hidden = true; });
  });

  // Chamada
  $('#btn-atender').onclick = () => Sip.atender(false);
  $('#btn-atender-video').onclick = () => Sip.atender(true);
  $('#btn-desligar').onclick = () => Sip.desligar();
  $('#btn-mute').onclick = (e) => {
    const s = Sip.alternarSilencio();
    e.target.classList.toggle('ligado', s);
    e.target.textContent = s ? 'Ativar microfone' : 'Silenciar';
  };
  $('#btn-camara').onclick = (e) => {
    const s = Sip.alternarCamara();
    e.target.classList.toggle('ligado', s);
    e.target.textContent = s ? 'Ligar câmara' : 'Desligar câmara';
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
  UI.fecharConversa();
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
