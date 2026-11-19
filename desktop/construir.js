// Gera os instaladores da app de computador para uma marca.
//   MARCA=issafone|issalink  VERSAO=1.0.N  node construir.js
// Windows: instalador .exe (NSIS). Linux: AppImage e .deb.
const fs = require('fs');
const builder = require('electron-builder');

const marca = process.env.MARCA || 'issafone';
const MARCAS = {
  // IssaFone: já vem ligado ao servidor do projeto
  issafone: { nome: 'IssaFone', appId: 'pt.ipca.issafone.desktop', servidor: 'rsi-a36357.ddns.net', pasta: 'issafone' },
  // IssaLink: pede o endereço do servidor na primeira vez
  issalink: { nome: 'IssaLink', appId: 'pt.ipca.issalink.desktop', servidor: '', pasta: 'issalink' },
};
const M = MARCAS[marca];
if (!M) throw new Error(`Marca desconhecida: ${marca}`);
fs.writeFileSync('marca.json', JSON.stringify(M, null, 2));

const plataformas = process.platform === 'win32' ? builder.Platform.WINDOWS.createTarget()
  : builder.Platform.LINUX.createTarget();

builder.build({
  targets: plataformas,
  publish: 'never',
  config: {
    appId: M.appId,
    productName: M.nome,
    copyright: 'Jorge Duarte · IPCA',
    artifactName: `${M.nome}-\${os}-\${arch}.\${ext}`,
    extraMetadata: {
      name: `${marca}-desktop`,
      productName: M.nome,
      version: process.env.VERSAO || '1.0.0',
      description: `${M.nome}: chamadas e chat para Issabel`,
      author: { name: 'Jorge Duarte', email: 'issafone@users.noreply.github.com' },
    },
    files: ['main.js', 'preload.js', 'marca.json', 'paginas/**', 'recursos/**'],
    directories: { output: `dist/${marca}` },
    win: {
      target: [{ target: 'nsis', arch: ['x64'] }],
      icon: 'recursos/icone.png',
    },
    nsis: {
      oneClick: false,
      perMachine: false,
      allowToChangeInstallationDirectory: true,
      createDesktopShortcut: true,
      createStartMenuShortcut: true,
      shortcutName: M.nome,
      runAfterFinish: true,
    },
    linux: {
      target: ['AppImage', 'deb'],
      icon: 'recursos/icone.png',
      category: 'Network;Telephony',
      executableName: marca,
      synopsis: 'Chamadas e chat para Issabel',
      maintainer: 'Jorge Duarte <issafone@users.noreply.github.com>',
    },
    deb: { packageName: marca },
  },
}).then((ficheiros) => console.log('Gerado:\n' + ficheiros.join('\n')))
  .catch((e) => { console.error(e); process.exit(1); });
