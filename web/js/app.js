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
// Marca (js/marca.js): IssaFone usa o servidor do projeto quando não está alojada no Issabel;
// IssaLink pede o servidor (no GitHub Pages) ou usa o domínio da página (no Issabel de cada um).
const MARCA = window.MARCA || { nome: 'IssaFone', pedirServidor: false };
const SERVIDOR_PROJETO = 'rsi-a36357.ddns.net';
const GENERICA = Boolean(MARCA.pedirServidor) || new URLSearchParams(location.search).has('generica');
const NOME_APP = MARCA.nome || 'IssaFone';
const SERVIDOR_PADRAO = GENERICA ? ''
  : (/(^localhost$|^127\.|github\.io$)/.test(location.hostname) && NOME_APP === 'IssaFone') ? SERVIDOR_PROJETO
  : location.hostname;

const guardar = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* modo privado */ } };
const ler = (k) => { try { return JSON.parse(localStorage.getItem(k)); } catch { return null; } };

function completarConfig(c) {
  // Guarda-se só o que a pessoa escreveu; os valores por omissão calculam-se sempre de novo.
  // (Antes guardava-se a palavra-passe do chat calculada, e ao mudar a da extensão o chat ficava com a antiga.)
  const original = { ...c };
  delete original.original;
  delete original.sessao;
  const host = ((c.servidor || '').trim() || SERVIDOR_PADRAO).replace(/^https?:\/\//, '').replace(/\/.*$/, '');
  return {
    ...c,
    servidor: host,
    extensao: c.extensao.trim(),
    dominioXmpp: (c.dominioXmpp || '').trim() || host,
    passXmpp: c.passXmpp || c.passSip,
    // Primeiro pela 443 (passa em redes de empresa/escola, que bloqueiam 8089/7443);
    // se o servidor ainda não tiver o reencaminhamento, a 443 falha logo e usa-se a porta direta.
    urlsSip: (c.wsSip || '').trim() ? [c.wsSip.trim()] : [`wss://${host}/issafone-ws/sip`, `wss://${host}:8089/ws`],
    urlsXmpp: (c.wsXmpp || '').trim() ? [c.wsXmpp.trim()] : [`wss://${host}/issafone-ws/xmpp`, `wss://${host}:7443/ws/`],
    stun: (c.stun || '').trim(),
    original,
  };
}

/** Configurações guardadas por versões antigas incluíam valores calculados: descarta-os. */
function lerConfigGuardada() {
  const c = ler(CHAVE_CONFIG);
  if (!c || c.v === 2) return c;
  for (const k of ['passXmpp', 'dominioXmpp', 'wsSip', 'wsXmpp']) delete c[k];
  c.v = 2;
  guardar(CHAVE_CONFIG, c);
  return c;
}

/* ======================= Anexos do chat (HTTP File Upload, XEP-0363) ======================= */

// O ficheiro vai para o Openfire e a mensagem leva só o endereço; quem recebe mostra a imagem ou o ficheiro.
const Anexos = {
  de(texto) {
    const t = (texto || '').trim();
    if (!/^https?:\/\/\S+\/httpfileupload\/\S+$/.test(t)) return null;
    let nome = t.split('/').pop().split('?')[0];
    try { nome = decodeURIComponent(nome); } catch { /* fica como está */ }
    const ext = (nome.split('.').pop() || '').toLowerCase();
    return { url: t, nome, ext, imagem: ['jpg', 'jpeg', 'png', 'gif', 'webp', 'heic', 'bmp'].includes(ext) };
  },
  resumo(texto) {
    const a = this.de(texto);
    return a ? (a.imagem ? '📷 Fotografia' : `📎 ${a.nome}`) : texto;
  },
  elemento(a) {
    const link = document.createElement('a');
    link.href = a.url; link.target = '_blank'; link.rel = 'noopener';
    if (a.imagem) {
      link.className = 'anexo-imagem';
      const img = document.createElement('img');
      img.src = a.url; img.alt = a.nome; img.loading = 'lazy';
      img.onload = () => { const ol = $('#mensagens'); if (ol.scrollHeight - ol.scrollTop - ol.clientHeight < 400) ol.scrollTop = ol.scrollHeight; };
      link.append(img);
    } else {
      link.className = 'anexo-ficheiro';
      const ic = document.createElement('span'); ic.className = 'anexo-icone'; ic.textContent = (a.ext || 'ficheiro').slice(0, 4).toUpperCase();
      const n = document.createElement('span'); n.className = 'anexo-nome'; n.textContent = a.nome;
      const d = document.createElement('small'); d.textContent = 'Toca para abrir';
      const info = document.createElement('span'); info.append(n, d);
      link.append(ic, info);
    }
    return link;
  },
};

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

/* ======================= Registo de chamadas ======================= */

const Registo = {
  chave: () => `issafone.chamadas.${cfg.extensao}@${cfg.dominioXmpp}`,
  chaveVistas: () => `issafone.chamadas-vistas.${cfg.extensao}`,
  tudo() { return ler(this.chave()) || []; },
  /** tipo: 'feita' | 'recebida' | 'perdida' | 'recusada' */
  juntar(e) {
    const lista = [e, ...this.tudo()].slice(0, 200);
    guardar(this.chave(), lista);
  },
  limpar() { guardar(this.chave(), []); },
  /** Chamadas perdidas desde a última vez que se abriu o registo. */
  perdidasPorVer() {
    const vistas = ler(this.chaveVistas()) || 0;
    return this.tudo().filter((c) => c.tipo === 'perdida' && c.hora > vistas).length;
  },
  marcarVistas() { guardar(this.chaveVistas(), Date.now()); },
};

/* ======================= Tema (automático / claro / escuro) ======================= */

const Tema = {
  modos: ['auto', 'claro', 'escuro'],
  simbolos: { auto: '◐', claro: '☀', escuro: '☾' },
  nomes: { auto: 'Automático', claro: 'Claro', escuro: 'Escuro' },
  atual() { try { return localStorage.getItem('issafone.tema') || 'auto'; } catch { return 'auto'; } },
  aplicar(modo) {
    if (modo === 'auto') delete document.documentElement.dataset.tema;
    else document.documentElement.dataset.tema = modo;
    try { localStorage.setItem('issafone.tema', modo); } catch { /* sem armazenamento */ }
    const b = $('#btn-tema');
    if (b) {
      b.innerHTML = `${this.simbolos[modo]}<span class="rotulo"> ${this.nomes[modo]}</span>`;
      b.title = `Tema: ${this.nomes[modo]} (carrega para mudar)`;
    }
  },
  seguinte() { this.aplicar(this.modos[(this.modos.indexOf(this.atual()) + 1) % 3]); },
};

/* ======================= Áudio: toque de chamada ======================= */

const Toque = {
  ctx: null, intervalo: null,
  desbloquear() {
    // Os browsers só deixam tocar som depois de um gesto do utilizador
    try { this.ctx ??= new (window.AudioContext || window.webkitAudioContext)(); this.ctx.resume(); } catch { /* sem áudio */ }
  },
  /**
   * iPhone: com um AudioContext ativo durante a chamada, o iOS manda o som para o auscultador
   * (muito baixo) e pode silenciar o microfone — só funcionava com auriculares. Fecha-o antes
   * de abrir o microfone e diz ao iOS que é uma chamada (altifalante + microfone).
   */
  libertar() {
    this.parar();
    try { this.ctx?.close(); } catch { /* ignora */ }
    this.ctx = null;
    try { if (navigator.audioSession) navigator.audioSession.type = 'play-and-record'; } catch { /* ignora */ }
  },
  fimChamada() {
    try { if (navigator.audioSession) navigator.audioSession.type = 'auto'; } catch { /* ignora */ }
  },
  bip() {
    if (!this.ctx) this.desbloquear();
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

/* ======================= Notificações push (chamadas com a página fechada) ======================= */

const ehTelemovel = /iPhone|iPad|iPod|Android/i.test(navigator.userAgent)
  || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
const ehIOS = /iPhone|iPad|iPod/i.test(navigator.userAgent)
  || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
const instalada = () => matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;

const Push = {
  CHAVE_CACHE: 'issafone-push',
  urlDados: () => new URL('push-dados', location.href).href,
  suportado: () => window.isSecureContext && 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window,
  base: () => `https://${cfg.servidor}/issafone-push/`,

  /** Pede ao browser uma subscrição push e regista-a no servidor (só com a permissão já dada). */
  async ativar() {
    if (!cfg || !this.suportado() || Notification.permission !== 'granted') return;
    try {
      const reg = await navigator.serviceWorker.ready;
      const r = await fetch(this.base() + '?acao=chave', { cache: 'no-store' });
      if (!r.ok) return;                       // servidor sem push instalado
      const { chave } = await r.json();
      const opcoes = { userVisibleOnly: true, applicationServerKey: b64urlBytes(chave) };
      let sub = await reg.pushManager.getSubscription();
      try { sub = sub || await reg.pushManager.subscribe(opcoes); } catch {
        // Subscrição antiga com outra chave do servidor: troca-a
        await sub?.unsubscribe(); sub = await reg.pushManager.subscribe(opcoes);
      }
      const resp = await fetch(this.base(), {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ acao: 'subscrever', ext: cfg.extensao, pass: cfg.passSip, sub: sub.toJSON() }),
      });
      if (!resp.ok) return;
      const { token } = await resp.json();
      const c = await caches.open(this.CHAVE_CACHE);
      await c.put(this.urlDados(), new Response(JSON.stringify({ base: this.base(), ext: cfg.extensao, token, endpoint: sub.endpoint })));
    } catch (e) { console.warn('IssaFone push:', e); }
  },

  /** Ao sair: este aparelho deixa de receber chamadas desta extensão. */
  async desativar() {
    try {
      const c = await caches.open(this.CHAVE_CACHE);
      const r = await c.match(this.urlDados());
      if (r) {
        const d = await r.json();
        fetch(d.base, {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ acao: 'remover', ext: d.ext, token: d.token, endpoint: d.endpoint }),
        }).catch(() => {});
        await c.delete(this.urlDados());
      }
      const reg = await navigator.serviceWorker?.getRegistration();
      await (await reg?.pushManager.getSubscription())?.unsubscribe();
    } catch { /* ignora */ }
  },

  /** No iPhone, o push só existe com a webapp no ecrã principal: explica uma vez. */
  dica() {
    let texto = '';
    if (ehIOS && !instalada()) texto = 'Para receberes chamadas com a página fechada: Partilhar → Adicionar ao ecrã principal, abre o IssaFone a partir daí e ativa as notificações.';
    else if (ehTelemovel && this.suportado() && Notification.permission === 'default') texto = 'Toca no 🔔 no topo para ativar as notificações e receberes chamadas com a página fechada.';
    const el = $('#dica');
    if (!el) return;
    let vista = false;
    try { vista = localStorage.getItem('issafone.dica') === texto; } catch { /* ignora */ }
    el.hidden = !texto || vista;
    $('#dica-texto').textContent = texto;
    $('#dica-fechar').onclick = () => { el.hidden = true; try { localStorage.setItem('issafone.dica', texto); } catch { /* ignora */ } };
  },
};

function b64urlBytes(s) {
  const b = atob(s.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - s.length % 4) % 4));
  return Uint8Array.from(b, (c) => c.charCodeAt(0));
}

/* ======================= Notificações do sistema ======================= */

const Avisos = {
  pode: () => 'Notification' in window && Notification.permission === 'granted',
  async pedir() {
    if (!('Notification' in window)) return;
    await Notification.requestPermission();
    UI.botaoNotificacoes();
    Push.dica();
    Push.ativar();
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
    // O JsSIP usa o de maior peso e, se falhar, passa ao seguinte
    const sockets = cfg.urlsSip.map((url, i) => ({ socket: new JsSIP.WebSocketInterface(url), weight: cfg.urlsSip.length - i }));
    this.ua = new JsSIP.UA({
      sockets,
      uri: `sip:${cfg.extensao}@${cfg.servidor}`,
      password: cfg.passSip,
      register: true,
      session_timers: false,
      user_agent: 'IssaFone Web',
    });
    this.ua.on('connecting', () => UI.estado('sip', 'aviso', 'a ligar…'));
    this.ua.on('connected', () => UI.estado('sip', 'aviso', 'a registar…'));
    this.ua.on('disconnected', () => UI.estado('sip', 'erro', 'sem ligação ao servidor (portas 443/8089)'));
    this.ua.on('registered', () => {
      UI.estado('sip', 'ok', 'registado');
      if (!this.pushFeito) { this.pushFeito = true; Push.ativar(); }
    });
    this.ua.on('unregistered', () => UI.estado('sip', '', 'desligado'));
    this.ua.on('registrationFailed', (e) => {
      // No registo, "Rejected" é o 403 do Asterisk: palavra-passe errada ou extensão sem WebRTC
      const motivo = e.cause === 'Rejected' || e.cause === 'Authentication Error'
        ? 'recusado: palavra-passe errada ou extensão não é web (200–250)'
        : `falhou (${MOTIVOS[e.cause] || e.cause})`;
      UI.estado('sip', 'erro', motivo);
    });
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
    this.proximaVideo = video;
    Toque.libertar();
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
    const nome = s.remote_identity.display_name || UI.nomeExt(numero);
    // Para o registo de chamadas
    this.info = { numero, nome, recebida, video: recebida ? ofereceVideo : Boolean(this.proximaVideo), atendida: false, recusei: false };
    this.proximaVideo = false;

    let ligado = false;
    const ligarMedia = (pc) => {
      if (ligado || !pc) return;
      ligado = true;
      pc.addEventListener('track', (e) => {
        // O som vai por um <audio> próprio (o <video> pode estar escondido e o iOS não o toca)
        const a = $('#audio-remoto');
        if (a.srcObject !== e.streams[0]) a.srcObject = e.streams[0];
        a.play().catch(() => {});
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
      Avisos.mostrar((ofereceVideo ? 'Videochamada de ' : 'Chamada de ') + nome, NOME_APP, 'chamada');
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
    if (this.info) this.info.atendida = true;
    this.atualizarVideo();
    if (this.inicio) return;
    this.inicio = Date.now();
    UI.chamadaAtiva();
    this.relogio = setInterval(() => UI.tempo(Date.now() - this.inicio), 1000);
    setTimeout(() => this.atualizarVideo(), 1500); // o vídeo remoto às vezes chega um pouco depois
  },

  atender(video = false) {
    Toque.libertar();
    // Se falhar, mostra o motivo em vez de ficar parado (ex.: a chamada já tinha sido cancelada)
    try { this.sessao?.answer(this.opcoes(video)); } catch (e) {
      UI.estadoChamada(`Não foi possível atender: ${e.message}`);
      setTimeout(() => this.fim(`Não foi possível atender: ${e.message}`), 3000);
    }
  },
  desligar() {
    if (this.info?.recebida && !this.info.atendida) this.info.recusei = true;
    try { this.sessao?.terminate(); } catch { this.fim('Chamada terminada'); }
  },

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
    const i = this.info;
    if (i && cfg) {
      const tipo = !i.recebida ? 'feita' : i.atendida ? 'recebida' : i.recusei ? 'recusada' : 'perdida';
      Registo.juntar({
        numero: i.numero, nome: i.nome, tipo, video: i.video, hora: Date.now(),
        duracao: this.inicio ? Math.round((Date.now() - this.inicio) / 1000) : 0,
      });
      if (tipo === 'perdida') Avisos.mostrar('Chamada perdida', i.nome, 'perdida', () => UI.vista('chamadas'));
      this.info = null;
      UI.chamadas();
    }
    clearInterval(this.relogio);
    this.relogio = null;
    this.inicio = null;
    this.sessao = null;
    $('#video-remoto').srcObject = null;
    $('#audio-remoto').srcObject = null;
    Toque.fimChamada();
    $('#video-local').srcObject = null;
    UI.videos(false);
    UI.fecharChamada(texto);
  },

  /**
   * Telemóvel: ao sair da página, anula o registo. Assim o Asterisk sabe logo que tem de
   * mandar a notificação push, em vez de tentar tocar numa página suspensa.
   */
  adormecer() {
    if (!ehTelemovel || !this.ua || this.sessao) return;
    if (!Push.suportado() || Notification.permission !== 'granted') return;
    try { if (this.ua.isRegistered()) this.ua.unregister(); } catch { /* ignora */ }
  },

  /** Ao voltar à página: se a ligação morreu enquanto estava em segundo plano, liga já (sem esperar pelo próximo intervalo). */
  reavivar() {
    if (!this.ua || this.sessao) return;
    if (this.ua.isConnected()) { this.ua.register(); return; }
    const antigo = this.ua;
    antigo.removeAllListeners();
    try { antigo.stop(); } catch { /* ignora */ }
    this.ligar();
  },

  sair() {
    try { this.sessao?.terminate(); } catch { /* ignora */ }
    try { this.ua?.stop(); } catch { /* ignora */ }
    this.ua = null;
    this.pushFeito = false;
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
  con: null, contactos: new Map(), grupos: new Map(), manual: false, tentativas: 0, url: 0, jaLigou: false,

  jid(utilizador) { return utilizador.includes('@') ? utilizador : `${utilizador}@${cfg.dominioXmpp}`; },
  get servicoGrupos() { return `conference.${cfg.dominioXmpp}`; },
  get eu() { return `${cfg.extensao}@${cfg.dominioXmpp}`; },
  get alcunha() { return cfg.extensao; },
  ehGrupo(jid) { return this.grupos.has(jid) || jid.endsWith('@' + this.servicoGrupos); },

  /** Ao voltar à página: se o chat caiu em segundo plano, liga já em vez de esperar pela próxima tentativa. */
  reavivar() {
    if (this.manual || !cfg) return;
    if (this.con?.connected && !this.con.disconnecting) return;
    this.tentativas = 0;
    if (!this.jaLigou) this.url = 0;
    this.ligar();
  },

  ligar() {
    clearTimeout(this.temporizador);
    this.manual = false;
    this.falhouAuth = false;
    const con = new Strophe.Connection(cfg.urlsXmpp[this.url % cfg.urlsXmpp.length], { protocol: 'wss' });
    this.con = con;
    let terminou = false;
    const recurso = 'web-' + Math.random().toString(36).slice(2, 7);
    con.connect(`${this.eu}/${recurso}`, cfg.passXmpp, (st) => {
      // Ignora avisos de ligações antigas e o DISCONNECTED repetido (senão abriam-se várias tentativas)
      if (con !== this.con || terminou) return;
      if (st === Strophe.Status.DISCONNECTED) terminou = true;
      this.mudouEstado(st);
    });
  },

  mudouEstado(st) {
    const S = Strophe.Status;
    if (st === S.CONNECTING) UI.estado('xmpp', 'aviso', 'a ligar…');
    else if (st === S.AUTHENTICATING) UI.estado('xmpp', 'aviso', 'a autenticar…');
    else if (st === S.AUTHFAIL) { this.manual = true; this.falhouAuth = true; UI.estado('xmpp', 'erro', 'palavra-passe errada'); }
    else if (st === S.CONNFAIL) UI.estado('xmpp', 'erro', 'sem ligação ao servidor (portas 443/7443)');
    else if (st === S.CONNECTED) { this.tentativas = 0; this.jaLigou = true; UI.estado('xmpp', 'ok', 'ligado'); this.aposLigar(); }
    else if (st === S.DISCONNECTED) {
      for (const c of this.contactos.values()) c.online = false;
      for (const g of this.grupos.values()) { g.dentro = false; g.ocupantes.clear(); }
      UI.contactos();
      if (this.manual) {
        // Não apagar a mensagem de palavra-passe errada
        if (this.falhouAuth) this.falhouAuth = false; else UI.estado('xmpp', '', 'desligado');
        return;
      }
      // Nunca chegou a ligar por este endereço: experimenta logo o seguinte (443 ↔ 7443)
      if (!this.jaLigou && cfg.urlsXmpp.length > 1 && this.url % cfg.urlsXmpp.length === 0) {
        this.url++;
        this.temporizador = setTimeout(() => { if (!this.manual && cfg) this.ligar(); }, 500);
        return;
      }
      if (!this.jaLigou) this.url = 0;
      const espera = Math.min(60, 5 * 2 ** this.tentativas++);
      UI.estado('xmpp', 'erro', `desligado — nova tentativa em ${espera}s`);
      this.temporizador = setTimeout(() => { if (!this.manual && cfg) this.ligar(); }, espera * 1000);
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
      if (jid === this.eu) return; // a própria extensão não aparece nos contactos
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

  /**
   * Envia um ficheiro: pede ao Openfire um "lugar" (XEP-0363), carrega-o por HTTP PUT
   * e manda o endereço como mensagem. [progresso] recebe valores de 0 a 1.
   */
  enviarFicheiro(para, ficheiro, progresso) {
    return new Promise((resolve, reject) => {
      if (!this.con?.connected) { reject(new Error('O chat não está ligado.')); return; }
      const nome = ficheiro.name.replace(/[^A-Za-z0-9._-]+/g, '_').slice(-80) || 'anexo';
      const pedido = $iq({ type: 'get', to: `httpfileupload.${cfg.dominioXmpp}` })
        .c('request', { xmlns: 'urn:xmpp:http:upload:0', filename: nome, size: ficheiro.size, 'content-type': ficheiro.type || 'application/octet-stream' });
      this.con.sendIQ(pedido, (iq) => {
        const put = iq.querySelector('put'); const get = iq.querySelector('get');
        if (!put || !get) { reject(new Error('Resposta inválida do servidor.')); return; }
        const xhr = new XMLHttpRequest();
        xhr.open('PUT', put.getAttribute('url'));
        xhr.setRequestHeader('Content-Type', ficheiro.type || 'application/octet-stream');
        put.querySelectorAll('header').forEach((h) => {
          const n = h.getAttribute('name');
          if (['Authorization', 'Cookie', 'Expires'].includes(n)) xhr.setRequestHeader(n, h.textContent);
        });
        xhr.upload.onprogress = (e) => { if (e.lengthComputable) progresso?.(e.loaded / e.total); };
        xhr.onload = () => {
          if (xhr.status >= 200 && xhr.status < 300) { this.enviar(para, get.getAttribute('url')); resolve(); }
          else reject(new Error(`O servidor recusou o ficheiro (HTTP ${xhr.status}).`));
        };
        xhr.onerror = () => reject(new Error('Falha de rede ao enviar o ficheiro.'));
        xhr.send(ficheiro);
      }, (erro) => {
        const texto = erro?.querySelector?.('text')?.textContent;
        reject(new Error(texto || (erro ? 'O servidor não aceitou o ficheiro (é demasiado grande?).'
          : 'O servidor ainda não aceita anexos (falta o plugin HTTP File Upload no Openfire).')));
      }, 20000);
    });
  },

  /** Remove um contacto da lista (os do grupo partilhado não podem ser removidos). */
  remover(jid) {
    this.con.sendIQ(
      $iq({ type: 'set' }).c('query', { xmlns: NS.ROSTER }).c('item', { jid, subscription: 'remove' }),
      () => { this.contactos.delete(jid); Historico.apagar(jid); UI.fecharConversa(); UI.contactos(); },
      () => UI.aviso('Este contacto vem do grupo partilhado do Openfire e não pode ser removido daqui.')
    );
  },

  adicionar(utilizador) {
    const jid = this.jid(utilizador.trim());
    this.con.sendIQ($iq({ type: 'set' }).c('query', { xmlns: NS.ROSTER }).c('item', { jid }));
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
    this.jaLigou = false; this.url = 0; this.tentativas = 0;
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

  iniciais(nome) {
    const p = (nome || '?').replace(/@.*/, '').trim().split(/\s+/);
    return (p.length > 1 && /\D/.test(p[0]) ? p[0][0] + p.at(-1)[0] : p[0].slice(0, 2)).toUpperCase();
  },
  nomeDe(jid) { return Xmpp.grupos.get(jid)?.nome || Xmpp.contactos.get(jid)?.nome || jid.split('@')[0]; },
  /** Nome da extensão (o "Display Name" do Issabel), ou o número se não houver. */
  nomeExt(numero) { return Xmpp.contactos.get(Xmpp.jid(numero))?.nome || numero; },

  /** v: 'teclado' | 'chamadas' | 'contactos' | 'conversa' */
  vista(v) {
    if (v === 'chamadas' || v === 'agenda') this.lista(v);
    else if (v === 'contactos') this.lista('conversas');
    $('.corpo').dataset.vista = v === 'chamadas' || v === 'agenda' ? 'contactos' : v;
    document.querySelectorAll('.separadores button').forEach((b) =>
      b.classList.toggle('ativo', b.dataset.vista === v || (v === 'conversa' && b.dataset.vista === 'contactos')));
  },

  /** Painel da esquerda: lista de conversas ou registo de chamadas. */
  lista(qual) {
    $('.contactos').dataset.lista = qual;
    document.querySelectorAll('.abas-lista button').forEach((b) => b.classList.toggle('ativo', b.dataset.lista === qual));
    if (qual === 'chamadas') { Registo.marcarVistas(); this.chamadas(); }
    if (qual === 'agenda') this.agenda();
  },

  /** Agenda: todas as extensões, com pesquisa e botões para ligar, vídeo e mensagem. */
  agenda() {
    const filtro = ($('#pesquisa-agenda').value || '').trim().toLowerCase()
      .normalize('NFD').replace(/[\u0300-\u036f]/g, '');
    const lista = [...Xmpp.contactos.values()]
      .map((c) => ({ ...c, num: c.jid.split('@')[0] }))
      .filter((c) => !filtro || (c.nome + ' ' + c.num).toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').includes(filtro))
      .sort((a, b) => a.nome.localeCompare(b.nome, 'pt'));
    const botao = (txt, titulo, fn) => {
      const b = document.createElement('button');
      b.className = 'rapido';
      b.textContent = txt;
      b.title = titulo;
      b.setAttribute('aria-label', titulo);
      b.onclick = fn;
      return b;
    };
    $('#agenda').replaceChildren(...lista.map((c) => {
      const li = document.createElement('li');
      li.className = c.online ? 'online' : '';
      const linha = document.createElement('button');
      linha.className = 'contacto-linha';
      linha.title = `Ligar a ${c.nome}`;
      const av = document.createElement('span');
      av.className = 'iniciais';
      av.textContent = this.iniciais(c.nome);
      const info = document.createElement('span');
      info.className = 'contacto-info';
      const n = document.createElement('strong');
      n.textContent = c.nome;
      const sub = document.createElement('small');
      sub.textContent = `${c.num} · ${c.online ? 'Disponível' : 'Ausente'}`;
      info.append(n, sub);
      linha.append(av, info);
      linha.onclick = () => Sip.chamar(c.num, false);
      li.append(linha,
        botao('💬', `Mensagem para ${c.nome}`, () => this.abrirConversa(c.jid)),
        botao('📞', `Ligar a ${c.nome}`, () => Sip.chamar(c.num, false)),
        botao('🎥', `Videochamada para ${c.nome}`, () => Sip.chamar(c.num, true)));
      return li;
    }));
    $('#sem-agenda').hidden = lista.length > 0;
    $('#sem-agenda').textContent = filtro ? 'Nenhum contacto encontrado.' : 'Sem contactos.';
  },

  chamadas() {
    if (!cfg) return;
    const lista = Registo.tudo();
    const hoje = new Date().toDateString();
    const ontem = new Date(Date.now() - 864e5).toDateString();
    const fmtH = new Intl.DateTimeFormat('pt-PT', { hour: '2-digit', minute: '2-digit' });
    const fmtD = new Intl.DateTimeFormat('pt-PT', { day: '2-digit', month: '2-digit' });
    const quando = (h) => {
      const d = new Date(h).toDateString();
      return (d === hoje ? 'hoje' : d === ontem ? 'ontem' : fmtD.format(h)) + ' ' + fmtH.format(h);
    };
    const dur = (s) => (s >= 3600 ? `${Math.floor(s / 3600)}h ` : '') + `${Math.floor((s % 3600) / 60)}:${String(s % 60).padStart(2, '0')}`;
    const TIPOS = {
      feita: ['↗', 'azul', 'Feita'], recebida: ['↙', 'verde', 'Recebida'],
      perdida: ['↙', 'vermelha', 'Perdida'], recusada: ['↙', 'vermelha', 'Recusada'],
    };
    $('#registo').replaceChildren(...lista.map((c) => {
      const [seta, cor, rotulo] = TIPOS[c.tipo] || TIPOS.feita;
      const nome = this.nomeExt(c.numero) !== c.numero ? this.nomeExt(c.numero) : (c.nome || c.numero);
      const li = document.createElement('li');
      li.className = c.tipo;
      const b = document.createElement('button');
      b.className = 'chamada-linha';
      b.title = 'Ligar';
      const s1 = document.createElement('span');
      s1.className = `seta cor-${cor}`;
      s1.textContent = seta;
      const info = document.createElement('span');
      info.className = 'contacto-info';
      const n = document.createElement('strong');
      n.textContent = nome;
      const sub = document.createElement('small');
      sub.textContent = [rotulo + (c.video ? ' · vídeo' : ''), nome !== c.numero ? c.numero : '', quando(c.hora),
        c.duracao ? dur(c.duracao) : ''].filter(Boolean).join(' · ');
      info.append(n, sub);
      b.append(s1, info);
      b.onclick = () => Sip.chamar(c.numero, false);
      const v = document.createElement('button');
      v.className = 'rapido';
      v.textContent = '🎥';
      v.title = 'Videochamada';
      v.setAttribute('aria-label', `Videochamada para ${nome}`);
      v.onclick = () => Sip.chamar(c.numero, true);
      li.append(b, v);
      return li;
    }));
    $('#sem-chamadas').hidden = lista.length > 0;
    const perdidas = $('.contactos').dataset.lista === 'chamadas' ? 0 : Registo.perdidasPorVer();
    document.querySelectorAll('.contador.perdidas').forEach((k) => { k.hidden = perdidas === 0; k.textContent = perdidas; });
  },

  estado(qual, tipo, texto) {
    const el = $(qual === 'sip' ? '#estado-sip' : '#estado-xmpp');
    el.className = 'estado ' + tipo;
    el.lastChild.textContent = (qual === 'sip' ? 'Telefone: ' : 'Chat: ') + texto;
  },

  /** Avisos importantes abrem uma janela; os discretos só aparecem como notificação. */
  aviso(texto, discreto = false) {
    if (discreto) { Avisos.mostrar(NOME_APP, texto, 'aviso'); return; }
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
    return (u.minha ? 'Tu: ' : u.autor ? `${this.nomeExt(u.autor)}: ` : '') + Anexos.resumo(u.texto);
  },

  contactos() {
    const grupos = [...Xmpp.grupos.values()].sort((a, b) => a.nome.localeCompare(b.nome, 'pt'));
    $('#lista-grupos').replaceChildren(...grupos.map((g) => this.linha({
      jid: g.jid, nome: g.nome, grupo: true,
      sub: this.ultima(g.jid) || (g.aCriar ? 'A criar…' : `${g.ocupantes.size + (g.dentro ? 1 : 0)} ligado(s)`),
    })));
    $('#sem-grupos').hidden = grupos.length > 0;

    const lista = [...Xmpp.contactos.values()].sort((a, b) => (b.online - a.online) || a.nome.localeCompare(b.nome, 'pt'));
    $('#lista-contactos').replaceChildren(...lista.map((c) => {
      const num = c.jid.split('@')[0];
      const estado = this.ultima(c.jid) || (c.online ? 'Disponível' : 'Ausente');
      return this.linha({ jid: c.jid, nome: c.nome, online: c.online, sub: c.nome !== num ? `${num} · ${estado}` : estado });
    }));
    $('#sem-contactos').hidden = lista.length > 0;
    if ($('.contactos').dataset.lista === 'agenda') this.agenda();
    this.contadorTotal();
    if (this.conversaCom) $('#conversa-nome').textContent = this.tituloConversa(this.conversaCom);
  },

  tituloConversa(jid) {
    const nome = this.nomeDe(jid);
    const num = jid.split('@')[0];
    return Xmpp.ehGrupo(jid) || nome === num ? nome : `${nome} (${num})`;
  },

  contadorTotal() {
    const total = [...this.porLer.values()].reduce((a, b) => a + b, 0);
    $('#por-ler').hidden = total === 0;
    $('#por-ler').textContent = total;
    document.title = total ? `(${total}) ${NOME_APP}` : NOME_APP;
  },

  /* ---- Conversa ---- */
  abrirConversa(jid) {
    this.conversaCom = jid;
    this.porLer.delete(jid);
    const grupo = Xmpp.ehGrupo(jid);
    $('#conversa-nome').textContent = this.tituloConversa(jid);
    $('#btn-ligar-contacto').hidden = grupo;
    $('#btn-video-contacto').hidden = grupo;
    $('#btn-membros').hidden = !grupo;
    $('#btn-remover-contacto').hidden = grupo;
    $('#form-mensagem').hidden = false;
    this.mensagens();
    this.contactos();
    this.vista('conversa');
    if (matchMedia('(min-width: 900px)').matches) $('#form-mensagem input').focus();
  },

  fecharConversa() {
    this.conversaCom = null;
    $('#conversa-nome').textContent = 'Escolhe um contacto';
    ['#btn-ligar-contacto', '#btn-video-contacto', '#btn-membros', '#btn-remover-contacto', '#form-mensagem'].forEach((s) => { $(s).hidden = true; });
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
        a.textContent = this.nomeExt(m.autor);
        li.append(a);
      }
      const anexo = Anexos.de(m.texto);
      if (anexo) { li.classList.add('com-anexo'); li.append(Anexos.elemento(anexo)); }
      else li.append(document.createTextNode(m.texto));
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
      Avisos.mostrar(titulo, autor ? `${this.nomeExt(autor)}: ${Anexos.resumo(texto)}` : Anexos.resumo(texto), 'msg-' + de, () => this.abrirConversa(de));
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
    if (b.dataset.vista === 'chamadas' || b.dataset.vista === 'agenda') b.onclick = () => UI.vista(b.dataset.vista);
  });
  $('#btn-voltar').onclick = () => UI.vista('contactos');
  document.querySelectorAll('.abas-lista button').forEach((b) => { b.onclick = () => UI.lista(b.dataset.lista); });
  $('#pesquisa-agenda').oninput = () => UI.agenda();
  $('#btn-limpar-chamadas').onclick = () => {
    if (confirm('Apagar o registo de chamadas deste browser?')) { Registo.limpar(); UI.chamadas(); }
  };
  $('#btn-tema').onclick = () => Tema.seguinte();

  $('#form-contacto').onsubmit = (e) => {
    e.preventDefault();
    const v = e.target.novo.value.trim();
    if (!v) return;
    if (!Xmpp.con?.connected) { UI.aviso('O chat não está ligado.'); return; }
    Xmpp.adicionar(v);
    e.target.reset();
  };

  // Anexos
  $('#btn-anexo').onclick = () => $('#ficheiro-anexo').click();
  $('#ficheiro-anexo').onchange = async (e) => {
    const f = e.target.files?.[0];
    e.target.value = '';
    if (!f || !UI.conversaCom) return;
    if (f.size > 50 * 1024 * 1024) { UI.aviso('O ficheiro é demasiado grande (máximo 50 MB).'); return; }
    const barra = $('#progresso-anexo');
    barra.hidden = false; barra.value = 0;
    $('#btn-anexo').disabled = true;
    try {
      await Xmpp.enviarFicheiro(UI.conversaCom, f, (p) => { barra.value = p; });
      UI.mensagens(); UI.contactos();
    } catch (erro) {
      UI.aviso(`Não foi possível enviar o anexo: ${erro.message}`);
    } finally {
      barra.hidden = true; $('#btn-anexo').disabled = false;
    }
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

  $('#btn-remover-contacto').onclick = () => {
    const jid = UI.conversaCom;
    if (jid && confirm(`Remover ${UI.nomeDe(jid)} dos contactos? A conversa também é apagada.`)) Xmpp.remover(jid);
  };

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

  // Os telemóveis suspendem a página em segundo plano e as ligações caem: ao voltar, religa logo
  const reavivar = () => { if (document.hidden || !cfg) return; Sip.reavivar(); Xmpp.reavivar(); };
  document.addEventListener('visibilitychange', () => { if (document.hidden && cfg) Sip.adormecer(); });
  window.addEventListener('pagehide', () => { if (cfg) Sip.adormecer(); });
  document.addEventListener('visibilitychange', reavivar);
  window.addEventListener('online', reavivar);
  window.addEventListener('pageshow', reavivar);

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

/** Versão genérica: o campo do servidor passa para o topo do formulário e é obrigatório. */
function prepararGenerica() {
  document.title = NOME_APP;
  $('.marca h1').textContent = NOME_APP;
  $('.topo .titulo strong').textContent = NOME_APP;
  if (NOME_APP !== 'IssaFone') $('.marca .sub').textContent = 'Chamadas e chat para a tua central Issabel';
  if (!GENERICA) return;
  const rotulo = $('#form-login input[name="servidor"]').closest('label');
  rotulo.firstChild.textContent = 'Servidor';
  const campo = rotulo.querySelector('input');
  campo.required = true; campo.placeholder = 'pbx.empresa.pt';
  $('#form-login input[name="extensao"]').closest('label').before(rotulo);
  $('#form-login input[name="extensao"]').placeholder = '200';
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
  guardar(CHAVE_CONFIG, { ...c.original, v: 2, sessao: true });
  $('#ecra-login').hidden = true;
  $('#ecra-app').hidden = false;
  UI.porLer.clear();
  UI.fecharConversa();
  UI.vista('teclado');
  UI.lista('conversas');
  UI.chamadas();
  UI.botaoNotificacoes();
  Push.dica();
  Sip.ligar();
  Xmpp.ligar();
}

function sair() {
  if (Sip.sessao && !confirm('Estás em chamada. Sair mesmo assim?')) return;
  Push.desativar();
  Sip.sair();
  Xmpp.sair();
  const c = lerConfigGuardada();
  if (c) guardar(CHAVE_CONFIG, { ...c, sessao: false });
  cfg = null;
  mostrarLogin(c);
}

/* ======================= Arranque ======================= */

prepararGenerica();
montarTeclados();
ligarEventos();
Tema.aplicar(Tema.atual());
const guardada = lerConfigGuardada();
if (guardada?.sessao && !verificarRequisitos()) entrar(completarConfig(guardada));
else mostrarLogin(guardada);

if ('serviceWorker' in navigator && window.isSecureContext) {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}
