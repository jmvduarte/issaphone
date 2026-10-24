// Service worker: guarda os ficheiros da app para abrir mais depressa e sem rede.
// Muda a VERSAO sempre que alterares ficheiros, para os browsers irem buscar os novos.
const VERSAO = 'issafone-v1';
const FICHEIROS = [
  './',
  'index.html',
  'css/app.css',
  'js/app.js',
  'vendor/jssip-3.13.9.min.js',
  'vendor/strophe-3.1.1.min.js',
  'manifest.webmanifest',
  'icons/icone-192.png',
  'icons/icone-512.png',
  'icons/favicon.png',
  'icons/apple-touch-icon.png',
];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(VERSAO).then((c) => c.addAll(FICHEIROS)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((chaves) => Promise.all(chaves.filter((k) => k !== VERSAO).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

// Rede primeiro (para apanhar versões novas), cache se não houver rede
self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET' || new URL(e.request.url).origin !== location.origin) return;
  e.respondWith(
    fetch(e.request)
      .then((r) => {
        const copia = r.clone();
        caches.open(VERSAO).then((c) => c.put(e.request, copia));
        return r;
      })
      .catch(() => caches.match(e.request))
  );
});

// Tocar numa notificação traz a app para a frente
self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  e.waitUntil(self.clients.matchAll({ type: 'window' }).then((janelas) => janelas[0]?.focus()));
});
