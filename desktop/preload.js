// Ponte entre a webapp e a app de computador (window.issaDesktop).
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('issaDesktop', {
  /** A webapp chama isto quando chega uma chamada: a janela vem para a frente. */
  chamada: (nome) => ipcRenderer.send('chamada', String(nome || '')),
  /** Página de configuração: guarda o servidor e abre a webapp. */
  definirServidor: (servidor) => ipcRenderer.invoke('definir-servidor', servidor),
  tentarDeNovo: () => ipcRenderer.invoke('tentar-de-novo'),
  plataforma: process.platform,
});
