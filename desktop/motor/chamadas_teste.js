// Teste de chamadas do motor SIP (GitHub): registo, chamada feita, chamada recebida e ocupado.
//   node chamadas_teste.js <baresip> <ip>   (com pbx_teste.py a correr no mesmo IP, porta 5070)
const path = require('path');
const { Motor } = require(path.join(__dirname, '..', 'motor.js'));
const [exe, ip] = process.argv.slice(2);
const m = new Motor(exe, path.join(require('os').tmpdir(), 'motor-chamadas'));
const visto = new Set();
let recebidas = 0;
m.on('evento', async (e) => {
  console.log('evento', e.class, e.type, e.peeruri || '', e.param || '');
  visto.add(e.type);
  if (e.type === 'REGISTER_OK' && !m.ligou) { m.ligou = true; await m.comando('dial', m.uri('300')); }
  if (e.type === 'CALL_ESTABLISHED' && e.direction === 'outgoing') { visto.add('FEITA_OK'); setTimeout(() => m.comando('hangup', e.id), 800); }
  if (e.type === 'CALL_INCOMING') {
    recebidas++;
    if (recebidas === 1) { if ((await m.comando('accept', e.id)).ok) visto.add('ATENDIDA_OK'); }
    else if ((await m.comando('hangup', `${e.id} scode=486 reason=Busy`)).ok) visto.add('OCUPADO_OK');
  }
});
(async () => {
  await m.iniciar();
  await m.entrar({ servidor: `${ip}:5070`, extensao: '10002', palavraPasse: 'segredo', transporte: 'udp' });
  setTimeout(async () => {
    const falta = ['REGISTER_OK', 'FEITA_OK', 'ATENDIDA_OK', 'OCUPADO_OK'].filter((x) => !visto.has(x));
    console.log(falta.length ? `FALHA: falta ${falta.join(', ')}` : 'OK: registo, chamada feita, recebida e ocupado');
    await m.parar();
    process.exit(falta.length ? 1 : 0);
  }, 16000);
})();
