// IssaFone / IssaLink para computador (Windows e Linux).
//
// É uma janela própria que abre a webapp instalada no servidor Issabel
// (https://<servidor>/issafone/ ou /issalink/). Assim a app de computador fica
// sempre igual à webapp, sem precisar de ser reinstalada a cada alteração.
//
// O que a app de computador acrescenta à webapp no browser:
//   - fica a correr na bandeja do sistema (fechar a janela não a desliga), por isso
//     as chamadas chegam mesmo com a janela fechada;
//   - arranca com o computador (pode desligar-se no menu da bandeja);
//   - quando chega uma chamada, a janela vem para a frente;
//   - microfone, câmara e notificações autorizados sem perguntar.
//
// Modo "Issabel normal" (servidor sem o script): a app usa um motor SIP próprio
// (baresip, em motor.js) e mostra um telefone simples (paginas/telefone.html):
// chamadas UDP/TCP como o Zoiper, sem chat.

const { app, BrowserWindow, Tray, Menu, nativeImage, session, shell, ipcMain, net, dialog, safeStorage, Notification } = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');

const MARCA = require('./marca.json');
const { Motor } = require('./motor');
const TESTE = (process.argv.find((a) => a.startsWith('--teste=')) || '').slice(8);
const ESCONDIDO = process.argv.includes('--escondido');
const TESTE_SIP = process.argv.includes('--teste-sip');

// AppImage em distribuições recentes (Ubuntu 24.04+) não arranca com a sandbox do Chromium
if (process.env.APPIMAGE) app.commandLine.appendSwitch('no-sandbox');
app.setAppUserModelId(MARCA.appId); // notificações do Windows com o nome e ícone certos

/* ------------------------- Configuração guardada ------------------------- */

const FICH_CONF = path.join(app.getPath('userData'), 'config.json');
function lerConf() {
  try { return JSON.parse(fs.readFileSync(FICH_CONF, 'utf8')); } catch { return {}; }
}
function gravarConf(alteracoes) {
  const c = { ...lerConf(), ...alteracoes };
  fs.mkdirSync(path.dirname(FICH_CONF), { recursive: true });
  fs.writeFileSync(FICH_CONF, JSON.stringify(c, null, 2));
  return c;
}
const servidor = () => (lerConf().servidor || MARCA.servidor || '').trim()
  .replace(/^https?:\/\//, '').replace(/\/.*$/, '');

/* ------------------------- Modo "Issabel normal" (motor SIP) ------------------------- */

const EXE_MOTOR = process.platform === 'win32' ? 'baresip.exe' : 'baresip';
const caminhoMotor = () => (app.isPackaged
  ? path.join(process.resourcesPath, 'motor', EXE_MOTOR)
  : path.join(__dirname, 'motor', 'bin', process.platform === 'win32' ? 'win' : 'linux', EXE_MOTOR));
let motor = null;

// A palavra-passe fica cifrada com a chave da sessão do sistema (DPAPI no Windows, keyring no Linux)
function cifrar(t) {
  if (safeStorage.isEncryptionAvailable()) return { c: safeStorage.encryptString(t).toString('base64') };
  return { p: Buffer.from(t, 'utf8').toString('base64') };
}
function decifrar(o) {
  try {
    if (o?.c) return safeStorage.decryptString(Buffer.from(o.c, 'base64'));
    if (o?.p) return Buffer.from(o.p, 'base64').toString('utf8');
  } catch { /* chave mudou */ }
  return '';
}

async function arrancarMotor() {
  if (!motor) {
    motor = new Motor(caminhoMotor(), path.join(app.getPath('userData'), 'sip'));
    motor.on('evento', (ev) => {
      janela?.webContents.send('motor-evento', ev);
      if (ev.type === 'CALL_INCOMING') {
        mostrar();
        janela?.flashFrame(true);
      }
    });
    motor.on('reiniciado', () => entrarComConta().catch(() => {}));
  }
  if (!motor.existe()) throw new Error(`Falta o motor SIP (${caminhoMotor()})`);
  await motor.iniciar();
}

async function entrarComConta() {
  const c = lerConf();
  if (!c.sip) return { ok: false };
  return motor.entrar({ ...c.sip, palavraPasse: decifrar(c.sip.passe) });
}

async function abrirTelefone() {
  const c = lerConf();
  try {
    await arrancarMotor();
    await entrarComConta();
  } catch (e) {
    return paginaLocal('erro', { motivo: String(e.message || e) });
  }
  trocarJanela('telefone');
  janela.loadFile(path.join(__dirname, 'paginas', 'telefone.html'), {
    query: { nome: MARCA.nome, extensao: c.sip.extensao },
  });
}

/* ------------------------- Arrancar com o computador ------------------------- */

const AUTOSTART_LINUX = path.join(os.homedir(), '.config', 'autostart', `${MARCA.appId}.desktop`);

function arranqueAtivo() {
  if (process.platform === 'linux') return fs.existsSync(AUTOSTART_LINUX);
  return app.getLoginItemSettings({ args: ['--escondido'] }).openAtLogin;
}
function definirArranque(ativo) {
  if (process.platform === 'linux') {
    if (!ativo) { fs.rmSync(AUTOSTART_LINUX, { force: true }); return; }
    const exe = process.env.APPIMAGE || process.execPath;
    fs.mkdirSync(path.dirname(AUTOSTART_LINUX), { recursive: true });
    fs.writeFileSync(AUTOSTART_LINUX, [
      '[Desktop Entry]', 'Type=Application', `Name=${MARCA.nome}`,
      `Exec="${exe}" --escondido`, 'X-GNOME-Autostart-enabled=true', 'Terminal=false', '',
    ].join('\n'));
  } else {
    app.setLoginItemSettings({ openAtLogin: ativo, args: ['--escondido'] });
  }
}

/* ------------------------- Janela ------------------------- */

let janela = null;
let bandeja = null;
let aSair = false;

function mostrar() {
  if (!janela) return;
  if (janela.isMinimized()) janela.restore();
  janela.show();
  janela.focus();
}

/** Escolhe a pasta da webapp no servidor: a da marca, ou a outra se essa não existir. */
async function urlWebapp(s) {
  const pastas = [MARCA.pasta, MARCA.pasta === 'issafone' ? 'issalink' : 'issafone'];
  for (const p of pastas) {
    try {
      const r = await net.fetch(`https://${s}/${p}/`, { method: 'HEAD' });
      if (r.ok) return `https://${s}/${p}/`;
    } catch { /* tenta a seguinte */ }
  }
  return null;
}

function paginaLocal(modo, extra = {}) {
  const sip = lerConf().sip || {};
  trocarJanela('normal');
  janela.loadFile(path.join(__dirname, 'paginas', 'inicio.html'), {
    query: { modo, nome: MARCA.nome, servidor: servidor(), extensao: sip.extensao || '',
      transporte: sip.transporte || 'udp', servidorSip: sip.servidor || '', ...extra },
  });
}

async function carregar() {
  if (lerConf().modo === 'sip') return abrirTelefone();
  const s = servidor();
  if (!s) return paginaLocal('configurar');
  const url = await urlWebapp(s);
  if (!url) return paginaLocal('erro', { motivo: `Não foi possível abrir a webapp em https://${s}/` });
  trocarJanela('normal');
  janela.loadURL(url);
}

// Duas formas de janela: "normal" (webapp e configuração) e "telefone" (modo só chamadas:
// sem moldura, transparente à volta, com a forma de um telemóvel desenhada na página).
let estiloJanela = null;
const ESTILOS = {
  normal: { width: 1100, height: 760, minWidth: 360, minHeight: 560, backgroundColor: '#0d1117' },
  telefone: { width: 324, height: 648, resizable: false, maximizable: false, fullscreenable: false,
    frame: false, transparent: true, backgroundColor: '#00000000', hasShadow: false },
};

/** Troca a janela pela do outro estilo (a moldura não se muda numa janela já aberta). */
function trocarJanela(estilo) {
  if (!janela || estiloJanela === estilo) return;
  const velha = janela;
  criarJanela(estilo, { carregar: false, mostrar: velha.isVisible() });
  velha.removeAllListeners('close');
  velha.destroy();
}

function criarJanela(estilo = lerConf().modo === 'sip' ? 'telefone' : 'normal',
  { carregar: carregarJa = true, mostrar: visivel = !ESCONDIDO && !TESTE } = {}) {
  estiloJanela = estilo;
  janela = new BrowserWindow({
    ...ESTILOS[estilo],
    title: MARCA.nome,
    icon: path.join(__dirname, 'recursos', 'icone.png'),
    autoHideMenuBar: true,
    show: visivel,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      // Sem isto o Chromium abranda a página escondida e as chamadas chegam tarde ou caem
      backgroundThrottling: false,
    },
  });
  janela.setMenu(null);

  // Fechar a janela só a esconde: a app continua na bandeja a receber chamadas
  janela.on('close', (e) => {
    if (aSair) return;
    e.preventDefault();
    janela.hide();
    if (!lerConf().avisoBandeja) {
      gravarConf({ avisoBandeja: true });
      bandeja?.displayBalloon?.({
        title: MARCA.nome,
        content: 'Continua a correr aqui para receberes chamadas. Para sair, usa o menu deste ícone.',
      });
    }
  });

  // Links para fora da webapp abrem no browser
  janela.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });

  janela.webContents.on('did-fail-load', (_e, codigo, descricao, url, principal) => {
    if (!principal || codigo === -3) return; // -3 = carregamento cancelado (normal)
    if (TESTE) { fs.writeFileSync(TESTE, `FALHA ${codigo} ${descricao} ${url}\n`); app.exit(1); }
    paginaLocal('erro', { motivo: `${descricao} (${codigo})` });
  });

  janela.webContents.on('did-finish-load', () => {
    if (!TESTE) return;
    const url = janela.webContents.getURL();
    // No teste espera pela webapp (ou pela página de configuração, se não houver servidor)
    if (url.startsWith('https://') || url.includes('modo=configurar')) {
      setTimeout(async () => {
        const titulo = await janela.webContents.executeJavaScript('document.title').catch(() => '?');
        fs.writeFileSync(TESTE, `OK ${url} ${titulo}\n`);
        app.exit(0);
      }, 3000);
    }
  });

  if (carregarJa) carregar();
}

/* ------------------------- Aviso de versão nova ------------------------- */
// Ao abrir e de 6 em 6 horas vê no GitHub se há uma versão mais recente (releases
// "desktop-N"). Se houver, mostra uma notificação e um item na bandeja para a
// descarregar. Não instala nada sozinha.

let novaVersao = null; // { n, versao, url, pagina }
let avisada = 0;

function numeroVersao(v) { return parseInt(String(v).split('.').pop(), 10) || 0; }

function ficheiroParaEstePC(assets) {
  const meus = assets.filter((a) => a.name.startsWith(`${MARCA.nome}-`));
  if (process.platform === 'win32') return meus.find((a) => a.name.endsWith('.exe'));
  if (process.env.APPIMAGE) return meus.find((a) => a.name.endsWith('.AppImage'));
  return meus.find((a) => a.name.endsWith('.deb'));
}

async function procurarAtualizacao(manual = false) {
  const atual = numeroVersao(app.getVersion());
  try {
    const r = await net.fetch(`https://api.github.com/repos/${MARCA.repo}/releases?per_page=30`,
      { headers: { Accept: 'application/vnd.github+json', 'User-Agent': MARCA.nome } });
    if (!r.ok) throw new Error(`GitHub respondeu ${r.status}`);
    const lista = await r.json();
    let melhor = null;
    for (const rel of lista) {
      const m = /^desktop-(\d+)$/.exec(rel.tag_name || '');
      if (!m || rel.draft || rel.prerelease) continue;
      const n = parseInt(m[1], 10);
      const f = ficheiroParaEstePC(rel.assets || []);
      if (f && (!melhor || n > melhor.n)) melhor = { n, versao: `1.0.${n}`, url: f.browser_download_url, pagina: rel.html_url };
    }
    novaVersao = melhor && melhor.n > atual ? melhor : null;
    bandeja?.setContextMenu(menuBandeja());
    if (novaVersao && avisada !== novaVersao.n) {
      avisada = novaVersao.n;
      if (Notification.isSupported()) {
        const n = new Notification({
          title: `${MARCA.nome}: versão ${novaVersao.versao} disponível`,
          body: `Tens a ${app.getVersion()}. Carrega aqui para descarregar a nova.`,
          icon: path.join(__dirname, 'recursos', 'icone.png'),
        });
        n.on('click', () => shell.openExternal(novaVersao.url));
        n.show();
      }
    }
    if (manual) {
      if (novaVersao) {
        const { response } = await dialog.showMessageBox({ type: 'info', buttons: ['Descarregar', 'Agora não'], defaultId: 0,
          message: `Há uma versão nova: ${novaVersao.versao}`, detail: `Tens a ${app.getVersion()}. Depois de descarregar, instala por cima (as contas ficam).` });
        if (response === 0) shell.openExternal(novaVersao.url);
      } else {
        dialog.showMessageBox({ type: 'info', message: `O ${MARCA.nome} está atualizado`, detail: `Versão ${app.getVersion()}.` });
      }
    }
  } catch (e) {
    if (manual) dialog.showMessageBox({ type: 'warning', message: 'Não foi possível verificar se há versão nova', detail: String(e?.message || e) });
  }
}

/* ------------------------- Bandeja do sistema ------------------------- */

function menuBandeja() {
  return Menu.buildFromTemplate([
    { label: `Abrir ${MARCA.nome}`, click: mostrar },
    ...(novaVersao ? [{ label: `⬇ Descarregar a versão ${novaVersao.versao}`, click: () => shell.openExternal(novaVersao.url) }] : []),
    { type: 'separator' },
    {
      label: 'Iniciar com o computador', type: 'checkbox', checked: arranqueAtivo(),
      click: (item) => { definirArranque(item.checked); bandeja.setContextMenu(menuBandeja()); },
    },
    { label: 'Mudar de servidor…', click: () => { mostrar(); paginaLocal('configurar'); } },
    { label: 'Recarregar', click: () => { mostrar(); carregar(); } },
    { label: 'Ver registo do motor SIP', click: abrirRegistoMotor },
    { label: `Procurar atualizações (versão ${app.getVersion()})`, click: () => procurarAtualizacao(true) },
    { type: 'separator' },
    { label: 'Sair', click: () => { aSair = true; app.quit(); } },
  ]);
}

function criarBandeja() {
  const img = nativeImage.createFromPath(path.join(__dirname, 'recursos', 'bandeja.png'));
  bandeja = new Tray(img);
  bandeja.setToolTip(MARCA.nome);
  bandeja.setContextMenu(menuBandeja());
  bandeja.on('click', mostrar);
}

/* ------------------------- Ligação à webapp (preload) ------------------------- */

// A webapp avisa quando chega uma chamada: a janela vem para a frente
ipcMain.on('chamada', () => {
  mostrar();
  janela?.flashFrame(true);
  janela?.setAlwaysOnTop(true);
  setTimeout(() => janela?.setAlwaysOnTop(false), 1500);
});

// Página local de configuração: guardar o servidor e abrir a webapp
ipcMain.handle('definir-servidor', async (_e, s) => {
  s = String(s || '').trim().replace(/^https?:\/\//, '').replace(/\/.*$/, '');
  if (!s) return { ok: false, erro: 'Indica o endereço do servidor.' };
  const url = await urlWebapp(s);
  if (!url) return { ok: false, erro: `Este servidor não tem a webapp do ${MARCA.nome} (falta o script). Para usar só chamadas, carrega em "Entrar só com chamadas".` };
  gravarConf({ servidor: s });
  janela.loadURL(url);
  return { ok: true };
});
ipcMain.handle('tentar-de-novo', () => carregar());

// Botões da janela do telefone (não tem moldura do sistema)
ipcMain.on('janela', (_e, acao) => {
  if (acao === 'minimizar') janela?.minimize();
  else if (acao === 'fechar') janela?.close(); // só esconde: continua na bandeja
  else if (acao === 'registo') abrirRegistoMotor();
});
function abrirRegistoMotor() {
  const f = path.join(app.getPath('userData'), 'sip', 'motor.log');
  if (fs.existsSync(f)) shell.openPath(f); else dialog.showMessageBox({ message: 'Ainda não há registo do motor SIP.' });
}

// Modo "Issabel normal": guardar a conta, arrancar o motor e abrir o telefone
ipcMain.handle('entrar-sip', async (_e, d) => {
  const servidorSip = String(d?.servidor || '').trim().replace(/^sips?:/, '').replace(/^https?:\/\//, '').replace(/\/.*$/, '');
  const extensao = String(d?.extensao || '').trim();
  const palavraPasse = String(d?.palavraPasse || '');
  const transporte = ['udp', 'tcp'].includes(d?.transporte) ? d.transporte : 'udp';
  if (!servidorSip || !extensao || !palavraPasse) return { ok: false, erro: 'Preenche o servidor, a extensão e a palavra-passe.' };
  gravarConf({ modo: 'sip', servidor: servidorSip.split(':')[0],
    sip: { servidor: servidorSip, extensao, transporte, passe: cifrar(palavraPasse) } });
  await abrirTelefone();
  return { ok: true };
});

ipcMain.handle('sair-sip', async () => {
  await motor?.sair().catch(() => {});
  const c = lerConf();
  gravarConf({ modo: null, sip: c.sip ? { ...c.sip, passe: null } : null });
  paginaLocal('configurar', { sip: '1' });
});

// Estado atual do registo (o ecrã do telefone pede-o ao abrir, para não perder a resposta)
ipcMain.handle('motor-estado', () => motor?.ultimoRegisto || null);
ipcMain.handle('motor-log', () => motor?.ultimoLog(4000) || '');

// Comandos do telefone para o motor (números passam a URI SIP do servidor da conta)
ipcMain.handle('motor', async (_e, comando, params) => {
  if (!motor) return { ok: false, data: 'motor parado' };
  if (['dial', 'transfer'].includes(comando)) params = motor.uri(params);
  return motor.comando(String(comando), params);
});

/* ------------------------- Arranque ------------------------- */

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', mostrar);

  app.whenReady().then(() => {
    // Microfone, câmara, notificações e área de transferência sem perguntar (só a nossa janela os usa)
    const permitidas = ['media', 'notifications', 'clipboard-sanitized-write', 'clipboard-read', 'fullscreen'];
    session.defaultSession.setPermissionRequestHandler((_wc, p, cb) => cb(permitidas.includes(p)));
    session.defaultSession.setPermissionCheckHandler((_wc, p) => permitidas.includes(p));

    // Na primeira vez fica a arrancar com o computador (para não perder chamadas)
    if (!TESTE && !lerConf().arranqueDefinido) {
      definirArranque(true);
      gravarConf({ arranqueDefinido: true });
    }

    if (TESTE && TESTE_SIP) return testarMotor();
    if (!TESTE) criarBandeja();
    criarJanela();
    if (!TESTE && MARCA.repo && app.isPackaged) {
      setTimeout(() => procurarAtualizacao(), 20000);
      setInterval(() => procurarAtualizacao(), 6 * 3600 * 1000);
    }
  });

  app.on('before-quit', () => { aSair = true; motor?.parar(); });
  app.on('window-all-closed', (e) => e.preventDefault()); // continua na bandeja
}

/** Teste automático (CI): o motor SIP incluído arranca, cria uma conta e responde. */
async function testarMotor() {
  try {
    await arrancarMotor();
    const a = await motor.comando('about');
    const b = await motor.entrar({ servidor: '127.0.0.1', extensao: '100', palavraPasse: 'teste', transporte: 'udp' });
    fs.writeFileSync(TESTE, `${a.ok && b.ok ? 'OK' : 'FALHA'} motor ${caminhoMotor()} about=${a.ok} conta=${b.ok}\n`);
    await motor.parar();
    app.exit(a.ok && b.ok ? 0 : 1);
  } catch (e) {
    const log = (motor?.ultimoLog() || '').replace(/\x1b\[[0-9;]*m/g, '').replace(/\s+/g, ' ');
    fs.writeFileSync(TESTE, `FALHA motor ${e.message} | registo: ${log}\n`);
    app.exit(1);
  }
}

process.on('uncaughtException', (err) => {
  if (TESTE) { fs.writeFileSync(TESTE, `ERRO ${err.stack}\n`); app.exit(1); }
  dialog.showErrorBox(`${MARCA.nome}: erro`, String(err?.stack || err));
});
