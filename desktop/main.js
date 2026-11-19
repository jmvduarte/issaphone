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

const { app, BrowserWindow, Tray, Menu, nativeImage, session, shell, ipcMain, net, dialog } = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');

const MARCA = require('./marca.json');
const TESTE = (process.argv.find((a) => a.startsWith('--teste=')) || '').slice(8);
const ESCONDIDO = process.argv.includes('--escondido');

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
  janela.loadFile(path.join(__dirname, 'paginas', 'inicio.html'), {
    query: { modo, nome: MARCA.nome, servidor: servidor(), ...extra },
  });
}

async function carregar() {
  const s = servidor();
  if (!s) return paginaLocal('configurar');
  const url = await urlWebapp(s);
  if (!url) return paginaLocal('erro', { motivo: `Não foi possível abrir a webapp em https://${s}/` });
  janela.loadURL(url);
}

function criarJanela() {
  janela = new BrowserWindow({
    width: 1100, height: 760, minWidth: 360, minHeight: 560,
    title: MARCA.nome,
    icon: path.join(__dirname, 'recursos', 'icone.png'),
    autoHideMenuBar: true,
    show: !ESCONDIDO && !TESTE,
    backgroundColor: '#0d1117',
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

  carregar();
}

/* ------------------------- Bandeja do sistema ------------------------- */

function menuBandeja() {
  return Menu.buildFromTemplate([
    { label: `Abrir ${MARCA.nome}`, click: mostrar },
    { type: 'separator' },
    {
      label: 'Iniciar com o computador', type: 'checkbox', checked: arranqueAtivo(),
      click: (item) => { definirArranque(item.checked); bandeja.setContextMenu(menuBandeja()); },
    },
    { label: 'Mudar de servidor…', click: () => { mostrar(); paginaLocal('configurar'); } },
    { label: 'Recarregar', click: () => { mostrar(); carregar(); } },
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
  if (!url) return { ok: false, erro: `Não encontrei a webapp em https://${s}/ (o servidor foi preparado com o script do ${MARCA.nome}?)` };
  gravarConf({ servidor: s });
  janela.loadURL(url);
  return { ok: true };
});
ipcMain.handle('tentar-de-novo', () => carregar());

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

    if (!TESTE) criarBandeja();
    criarJanela();
  });

  app.on('before-quit', () => { aSair = true; });
  app.on('window-all-closed', (e) => e.preventDefault()); // continua na bandeja
}

process.on('uncaughtException', (err) => {
  if (TESTE) { fs.writeFileSync(TESTE, `ERRO ${err.stack}\n`); app.exit(1); }
  dialog.showErrorBox(`${MARCA.nome}: erro`, String(err?.stack || err));
});
