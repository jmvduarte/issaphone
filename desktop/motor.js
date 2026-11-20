// Motor SIP do modo "Issabel normal": arranca o baresip (incluído na app) e controla-o
// pelo ctrl_tcp (JSON em netstrings, só em 127.0.0.1). Assim a app faz chamadas SIP
// normais (UDP/TCP), como o Zoiper, num Issabel sem nenhuma preparação.
const { spawn } = require('child_process');
const net = require('net');
const dgram = require('dgram');
const fs = require('fs');
const path = require('path');
const { EventEmitter } = require('events');

/** Porta livre no PC (UDP e TCP), para o SIP não colidir com outros softphones. */
function portaLivre(tipo = 'tcp') {
  return new Promise((resolve, reject) => {
    if (tipo === 'udp') {
      const s = dgram.createSocket('udp4');
      s.once('error', reject);
      s.bind(0, '0.0.0.0', () => { const p = s.address().port; s.close(() => resolve(p)); });
    } else {
      const s = net.createServer();
      s.once('error', reject);
      s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => resolve(p)); });
    }
  });
}

/** Linux: há PulseAudio (ou PipeWire com pipewire-pulse) na sessão? Senão usa-se o ALSA. */
function temPulse() {
  if (process.env.PULSE_SERVER) return true;
  const uid = typeof process.getuid === 'function' ? process.getuid() : 0;
  const base = process.env.XDG_RUNTIME_DIR || `/run/user/${uid}`;
  return fs.existsSync(path.join(base, 'pulse', 'native'));
}

class Motor extends EventEmitter {
  /** @param {string} exe caminho do baresip; @param {string} pasta pasta de configuração */
  constructor(exe, pasta) {
    super();
    this.exe = exe;
    this.pasta = pasta;
    this.proc = null;
    this.sock = null;
    this.buf = Buffer.alloc(0);
    this.pendentes = new Map();
    this.seq = 0;
    this.registo = '';
    this.aParar = false;
  }

  existe() { return fs.existsSync(this.exe); }

  /** Últimas linhas do registo do motor. */
  ultimoLog(n = 1200) {
    try { const t = fs.readFileSync(this.ficheiroLog, 'utf8'); return t.slice(-n); } catch { return ''; }
  }

  async iniciar() {
    if (this.proc) return;
    this.aParar = false;
    fs.mkdirSync(this.pasta, { recursive: true });
    this.portaCtrl = await portaLivre('tcp');
    let portaSip = await portaLivre('udp');
    if (portaSip < 1024) portaSip = 5064;
    const som = process.platform === 'win32' ? 'wasapi' : (temPulse() ? 'pulse' : 'alsa');
    fs.writeFileSync(path.join(this.pasta, 'config'), [
      `sip_listen 0.0.0.0:${portaSip}`,
      'sip_transports udp,tcp',
      'call_local_timeout 120',
      'call_max_calls 4',
      `audio_player ${som},default`,
      `audio_source ${som},default`,
      `audio_alert ${som},default`,
      'rtp_ports 10000-20000',
      'module g711.so',
      'module auconv.so',
      'module auresamp.so',
      `module ${som}.so`,
      'module_app account.so',
      'module_app menu.so',
      'module_app ctrl_tcp.so',
      `ctrl_tcp_listen 127.0.0.1:${this.portaCtrl}`,
      '',
    ].join('\n'));
    // A conta é criada depois com "uanew": a palavra-passe nunca fica escrita no disco
    fs.writeFileSync(path.join(this.pasta, 'accounts'), '');

    // Registo do motor (para diagnóstico): userData/sip/motor.log
    this.ficheiroLog = path.join(this.pasta, 'motor.log');
    fs.writeFileSync(this.ficheiroLog, `${new Date().toISOString()} ${this.exe} (som: ${som})\n`);
    this.proc = spawn(this.exe, ['-f', this.pasta], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    const registar = (d) => {
      try { fs.appendFileSync(this.ficheiroLog, d); } catch { /* sem disco */ }
      this.emit('log', d.toString());
    };
    this.proc.stdout.on('data', registar);
    this.proc.stderr.on('data', registar);
    this.proc.on('exit', (codigo) => {
      this.proc = null;
      this.sock?.destroy();
      this.sock = null;
      this.emit('evento', { class: 'motor', type: 'PAROU', param: String(codigo) });
      // Reinicia sozinho se parou sem ser pedido
      if (!this.aParar) setTimeout(() => this.iniciar().then(() => this.emit('reiniciado')).catch(() => {}), 3000);
    });
    await this.ligarControlo();
  }

  ligarControlo() {
    return new Promise((resolve, reject) => {
      let tentativas = 0;
      const tentar = () => {
        const s = net.connect(this.portaCtrl, '127.0.0.1');
        s.once('connect', () => { this.sock = s; s.on('data', (d) => this.receber(d)); resolve(); });
        s.once('error', () => {
          s.destroy();
          if (++tentativas > 50 || !this.proc) return reject(new Error('O motor SIP não respondeu'));
          setTimeout(tentar, 200);
        });
      };
      tentar();
    });
  }

  receber(d) {
    this.buf = Buffer.concat([this.buf, d]);
    for (;;) {
      const dois = this.buf.indexOf(0x3a);
      if (dois < 0) return;
      const n = parseInt(this.buf.subarray(0, dois).toString(), 10);
      if (this.buf.length < dois + 1 + n + 1) return;
      let msg;
      try { msg = JSON.parse(this.buf.subarray(dois + 1, dois + 1 + n).toString('utf8')); } catch { msg = null; }
      this.buf = this.buf.subarray(dois + 1 + n + 1);
      if (!msg) continue;
      if (msg.response && this.pendentes.has(msg.token)) {
        this.pendentes.get(msg.token)(msg);
        this.pendentes.delete(msg.token);
      } else if (msg.event) {
        if (msg.class === 'register') this.registo = msg.type;
        this.emit('evento', msg);
      }
    }
  }

  /** Envia um comando ao baresip (ex.: "dial", "sip:101@pbx"). Devolve {ok, data}. */
  comando(command, params) {
    return new Promise((resolve) => {
      if (!this.sock) return resolve({ ok: false, data: 'motor parado' });
      const token = `t${++this.seq}`;
      const corpo = Buffer.from(JSON.stringify(params != null ? { command, params: String(params), token } : { command, token }), 'utf8');
      this.pendentes.set(token, resolve);
      this.sock.write(Buffer.concat([Buffer.from(`${corpo.length}:`), corpo, Buffer.from(',')]));
      setTimeout(() => { if (this.pendentes.delete(token)) resolve({ ok: false, data: 'sem resposta' }); }, 8000);
    });
  }

  /** Cria a conta SIP e regista-a no Issabel. */
  async entrar({ servidor, extensao, palavraPasse, transporte = 'udp', nome = '' }) {
    await this.comando('uadel', this.aor).catch(() => {});
    const [host, porta] = String(servidor).split(':');
    this.aor = `sip:${extensao}@${host}`;
    this.dominio = host;
    const destino = `${host}${porta ? `:${porta}` : ''}`;
    const nomeTxt = nome ? `"${nome.replace(/"/g, '')}" ` : '';
    const pass = String(palavraPasse).replace(/;/g, '%3B');
    const linha = `${nomeTxt}<sip:${extensao}@${destino};transport=${transporte}>` +
      `;auth_pass=${pass};regint=60;answermode=manual;audio_codecs=pcma,pcmu;dtmfmode=rtpevent;video_codecs=;mwi=no`;
    return this.comando('uanew', linha);
  }

  async sair() {
    if (this.aor) await this.comando('uadel', this.aor);
    this.aor = null;
  }

  /** Número ou extensão → URI SIP no servidor da conta. */
  uri(numero) {
    const n = String(numero).trim();
    return n.startsWith('sip:') ? n : `sip:${n.replace(/[^0-9*#+a-zA-Z._-]/g, '')}@${this.dominio}`;
  }

  async parar() {
    this.aParar = true;
    if (!this.proc) return;
    await this.comando('quit').catch(() => {});
    setTimeout(() => this.proc?.kill(), 1500);
  }
}

module.exports = { Motor };
