// Ponte entre a webapp e a app de computador (window.issaDesktop).
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('issaDesktop', {
  /** A webapp chama isto quando chega uma chamada: a janela vem para a frente. */
  chamada: (nome) => ipcRenderer.send('chamada', String(nome || '')),
  /** Página de configuração: guarda o servidor e abre a webapp. */
  definirServidor: (servidor) => ipcRenderer.invoke('definir-servidor', servidor),
  tentarDeNovo: () => ipcRenderer.invoke('tentar-de-novo'),
  /** Modo "Issabel normal" (motor SIP próprio, sem preparar o servidor). */
  entrarSip: (dados) => ipcRenderer.invoke('entrar-sip', dados),
  sairSip: () => ipcRenderer.invoke('sair-sip'),
  motor: (comando, params) => ipcRenderer.invoke('motor', comando, params),
  aoEvento: (fn) => ipcRenderer.on('motor-evento', (_e, ev) => fn(ev)),
  estadoMotor: () => ipcRenderer.invoke('motor-estado'),
  registoMotor: () => ipcRenderer.invoke('motor-log'),
  /** Janela do telefone (sem moldura): 'minimizar' | 'fechar' | 'registo'. */
  janela: (acao) => ipcRenderer.send('janela', String(acao)),
  plataforma: process.platform,
});
