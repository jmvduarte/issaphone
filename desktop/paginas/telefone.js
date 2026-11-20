// Modo "Issabel normal": telefone SIP simples (sem chat), controlado pelo motor baresip.
const D = window.issaDesktop;
const $ = (id) => document.getElementById(id);

const q = new URLSearchParams(location.search);
const NOME_APP = q.get('nome') || 'IssaLink';
const EXT = q.get('extensao') || '';
document.title = NOME_APP;
$('titulo').textContent = NOME_APP;

/* ------------------------- Estado do registo ------------------------- */
function estado(texto, tipo = '') {
  const p = $('estado');
  p.className = 'pilula ' + tipo;
  p.querySelector('span').textContent = texto;
}

/* ------------------------- Teclado ------------------------- */
const TECLAS = [['1', ''], ['2', 'ABC'], ['3', 'DEF'], ['4', 'GHI'], ['5', 'JKL'], ['6', 'MNO'],
  ['7', 'PQRS'], ['8', 'TUV'], ['9', 'WXYZ'], ['*', ''], ['0', '+'], ['#', '']];
for (const [t, l] of TECLAS) {
  const b = document.createElement('button');
  b.className = 'tecla';
  b.innerHTML = `${t}<small>${l}</small>`;
  b.addEventListener('click', () => { $('numero').value += t; $('numero').focus(); });
  $('grelha').append(b);
  const d = document.createElement('button');
  d.textContent = t;
  d.addEventListener('click', () => D.motor('sndcode', t));
  $('dtmf').append(d);
}
$('apagar').addEventListener('click', () => { $('numero').value = $('numero').value.slice(0, -1); });
$('numero').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') ligar();
  if (e.key === 'Escape') $('numero').value = '';
});
$('ligar').addEventListener('click', ligar);

/* ------------------------- Recentes ------------------------- */
function lerRecentes() { try { return JSON.parse(localStorage.getItem('recentes') || '[]'); } catch { return []; } }
function juntarRecente(r) {
  const l = [r, ...lerRecentes()].slice(0, 60);
  try { localStorage.setItem('recentes', JSON.stringify(l)); } catch { /* sem armazenamento */ }
  mostrarRecentes();
}
function iniciais(t) {
  const p = String(t || '?').trim().split(/\s+/);
  return ((p[0]?.[0] || '?') + (p.length > 1 ? p[p.length - 1][0] : '')).toUpperCase();
}
function mostrarRecentes() {
  const l = lerRecentes();
  const caixa = $('recentes');
  caixa.innerHTML = '';
  if (!l.length) { caixa.innerHTML = '<div class="vazio">Ainda sem chamadas</div>'; return; }
  const fmt = new Intl.DateTimeFormat('pt-PT', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
  for (const r of l) {
    const el = document.createElement('div');
    el.className = 'recente';
    const seta = r.tipo === 'feita' ? '↗' : r.tipo === 'perdida' ? '↙' : '↙';
    el.innerHTML = `<div class="avatar"></div><div class="info"><b></b><span><span class="seta ${r.tipo === 'perdida' ? 'perdida' : ''}">${seta}</span> ${fmt.format(r.hora)}</span></div>`;
    el.querySelector('.avatar').textContent = iniciais(r.nome || r.numero);
    el.querySelector('b').textContent = r.nome && r.nome !== r.numero ? `${r.nome} (${r.numero})` : r.numero;
    el.title = 'Ligar';
    el.addEventListener('click', () => { $('numero').value = r.numero; ligar(); });
    caixa.append(el);
  }
}
mostrarRecentes();

/* ------------------------- Toque (sem ficheiros de som) ------------------------- */
const Toque = {
  ctx: null, timer: null,
  tocar() {
    this.parar();
    try {
      this.ctx = new AudioContext();
      const bip = () => {
        const t = this.ctx.currentTime;
        for (const [ini, f] of [[0, 440], [0.4, 480]]) {
          const o = this.ctx.createOscillator(), g = this.ctx.createGain();
          o.frequency.value = f; g.gain.setValueAtTime(0.0001, t + ini);
          g.gain.exponentialRampToValueAtTime(0.25, t + ini + 0.03);
          g.gain.exponentialRampToValueAtTime(0.0001, t + ini + 0.38);
          o.connect(g).connect(this.ctx.destination); o.start(t + ini); o.stop(t + ini + 0.4);
        }
      };
      bip(); this.timer = setInterval(bip, 2500);
    } catch { /* sem áudio */ }
  },
  parar() { clearInterval(this.timer); this.timer = null; this.ctx?.close().catch(() => {}); this.ctx = null; },
};

/* ------------------------- Chamadas ------------------------- */
let atual = null; // { id, numero, nome, tipo, inicio, atendida, mudo, espera }
let relogio = null;

function numeroDe(uri) { return String(uri || '').replace(/^sips?:/, '').split('@')[0]; }

function mostrarChamada(modo) {
  $('chamada').hidden = !modo;
  if (!modo) return;
  $('cAvatar').textContent = iniciais(atual.nome || atual.numero);
  $('cNome').textContent = atual.nome || atual.numero;
  $('cNumero').textContent = atual.nome && atual.nome !== atual.numero ? atual.numero : '';
  $('aTocar').hidden = modo !== 'recebida';
  $('emCurso').hidden = modo !== 'curso';
  $('soDesligar').hidden = modo === 'recebida';
  $('silenciar').classList.toggle('ativo', !!atual.mudo);
  $('espera').classList.toggle('ativo', !!atual.espera);
}

function duracao() {
  const s = Math.floor((Date.now() - atual.inicio) / 1000);
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
}

async function ligar() {
  const n = $('numero').value.trim();
  if (!n || atual) return;
  atual = { id: null, numero: n, nome: '', tipo: 'feita' };
  $('estadoChamada').textContent = 'A ligar…';
  mostrarChamada('saida');
  const r = await D.motor('dial', n);
  if (!r.ok) { $('estadoChamada').textContent = 'Não foi possível ligar'; setTimeout(terminar, 2000); }
}

function terminar(motivo) {
  Toque.parar();
  clearInterval(relogio); relogio = null;
  if (atual) {
    juntarRecente({ numero: atual.numero, nome: atual.nome, hora: Date.now(),
      tipo: atual.tipo === 'recebida' && !atual.atendida ? 'perdida' : atual.tipo });
  }
  if (motivo) {
    $('estadoChamada').textContent = motivo;
    setTimeout(() => { atual = null; mostrarChamada(null); }, 1800);
  } else { atual = null; mostrarChamada(null); }
}

$('atender').addEventListener('click', async () => {
  if (!atual) return;
  Toque.parar();
  $('estadoChamada').textContent = 'A atender…';
  await D.motor('accept', atual.id);
});
$('recusar').addEventListener('click', () => atual && D.motor('hangup', `${atual.id} scode=486 reason=Busy`));
$('desligar').addEventListener('click', () => atual && (atual.id ? D.motor('hangup', atual.id) : terminar()));
$('silenciar').addEventListener('click', async () => {
  if (!atual) return;
  atual.mudo = !atual.mudo;
  await D.motor('mute', atual.mudo ? 'yes' : 'no');
  mostrarChamada('curso');
});
$('espera').addEventListener('click', async () => {
  if (!atual) return;
  atual.espera = !atual.espera;
  await D.motor(atual.espera ? 'hold' : 'resume', atual.id);
  $('estadoChamada').textContent = atual.espera ? 'Em espera' : duracao();
  mostrarChamada('curso');
});
$('mostrarDtmf').addEventListener('click', () => { $('dtmf').hidden = !$('dtmf').hidden; });
$('transferir').addEventListener('click', () => { $('destinoTransf').value = ''; $('dlgTransferir').showModal(); });
$('cancTransf').addEventListener('click', () => $('dlgTransferir').close());
$('okTransf').addEventListener('click', async () => {
  const destino = $('destinoTransf').value.trim();
  $('dlgTransferir').close();
  if (!destino || !atual) return;
  await D.motor('callfind', atual.id);
  const r = await D.motor('transfer', destino);
  $('estadoChamada').textContent = r.ok ? `A transferir para ${destino}…` : 'Não foi possível transferir';
});

/* ------------------------- Eventos do motor ------------------------- */
D.aoEvento(async (ev) => {
  if (ev.class === 'register') {
    if (ev.type === 'REGISTER_OK') estado(`Registado · extensão ${EXT}`, 'ok');
    else if (ev.type === 'REGISTER_FAIL') estado(`Falhou: ${ev.param || 'sem resposta'}`, 'erro');
    else if (ev.type === 'REGISTERING') estado('A registar…');
    return;
  }
  if (ev.class === 'motor' && ev.type === 'PAROU') { estado('O motor SIP parou; a reiniciar…', 'erro'); return; }
  if (ev.class !== 'call') return;

  const numero = numeroDe(ev.peeruri);
  switch (ev.type) {
    case 'CALL_INCOMING':
      // Já em chamada: quem liga ouve "ocupado"
      if (atual) { D.motor('hangup', `${ev.id} scode=486 reason=Busy`); return; }
      atual = { id: ev.id, numero, nome: ev.peerdisplayname || '', tipo: 'recebida' };
      $('estadoChamada').textContent = 'Chamada recebida';
      mostrarChamada('recebida');
      Toque.tocar();
      D.chamada(atual.nome || numero);
      break;
    case 'CALL_OUTGOING':
      if (atual && !atual.id) atual.id = ev.id;
      break;
    case 'CALL_PROGRESS':
    case 'CALL_RINGING':
      if (atual?.tipo === 'feita') $('estadoChamada').textContent = 'A tocar…';
      break;
    case 'CALL_ESTABLISHED':
      if (!atual) return;
      Toque.parar();
      atual.id = ev.id; atual.atendida = true; atual.inicio = Date.now();
      mostrarChamada('curso');
      $('estadoChamada').textContent = '00:00';
      clearInterval(relogio);
      relogio = setInterval(() => { if (atual && !atual.espera) $('estadoChamada').textContent = duracao(); }, 1000);
      break;
    case 'CALL_CLOSED': {
      if (!atual || (atual.id && ev.id && atual.id !== ev.id)) return;
      const p = String(ev.param || '');
      let motivo = 'Chamada terminada';
      if (/486|busy/i.test(p)) motivo = 'Ocupado';
      else if (/404/.test(p)) motivo = 'Número não existe';
      else if (/480|408|unavailable|timeout/i.test(p)) motivo = 'Não disponível';
      else if (/603|decline/i.test(p)) motivo = 'Chamada recusada';
      else if (/transfer/i.test(p)) motivo = 'Chamada transferida';
      terminar(motivo);
      break;
    }
    default: break;
  }
});

$('sair').addEventListener('click', () => D.sairSip());
estado(`A registar a extensão ${EXT}…`);
