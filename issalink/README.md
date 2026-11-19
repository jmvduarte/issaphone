# IssaLink

**Chamadas e chat para a tua central Issabel 4**, no Android, no computador (Windows e Linux) e no browser (PC e iPhone).

- Chamadas de voz e vídeo, cifradas (SIP sobre TLS + SRTP; WebRTC na webapp)
- Chat interno com grupos, anexos, vistos (✓✓) e histórico
- Contactos automáticos: cada extensão criada no Issabel aparece sozinha na lista de todos
- Transferir chamadas, juntar várias extensões numa conferência, alta-voz e Bluetooth
- Notificações de chamadas no iPhone (webapp instalada no ecrã principal)

> O IssaLink é a versão genérica do **IssaFone**, um projeto académico do curso CTeSP de Redes e Sistemas Informáticos do IPCA. Este repositório é atualizado automaticamente a partir de [jmvduarte/issaphone](https://github.com/jmvduarte/issaphone), onde está o código.

---

## Descarregar

| Plataforma | Onde |
|---|---|
| **Android 8+** | [IssaLink-arm64-v8a.apk](https://github.com/jmvduarte/issalink/releases/latest/download/IssaLink-arm64-v8a.apk) (telemóveis atuais) · [IssaLink.apk](https://github.com/jmvduarte/issalink/releases/latest/download/IssaLink.apk) (universal) |
| **Windows 10/11** | `IssaLink-win-x64.exe` na Release **desktop** mais recente, em [Releases](https://github.com/jmvduarte/issalink/releases) |
| **Linux** | `IssaLink-linux-x86_64.AppImage` (qualquer distribuição) ou `IssaLink-linux-amd64.deb` (Ubuntu/Debian), na mesma Release |
| **PC e iPhone (browser)** | `https://O_TEU_SERVIDOR/issalink/` (instalada pelo script do servidor) · ou [jmvduarte.github.io/issalink](https://jmvduarte.github.io/issalink/), que pede o servidor |

Avisos ao instalar (normais para apps fora das lojas):
- **Android:** autoriza "instalar apps de origem desconhecida". Se o Play Protect avisar, escolhe instalar mesmo assim.
- **Windows:** o instalador não está assinado. Em "O Windows protegeu o computador", carrega em *Mais informações → Executar mesmo assim*.
- **AppImage:** dá permissão de execução (`chmod +x IssaLink-linux-x86_64.AppImage`) e abre com duplo clique.

---

## Usar

### Só chamadas, sem mexer no Issabel

A app Android funciona como um softphone normal (tipo Zoiper) em qualquer Issabel: põe o endereço do servidor (domínio ou IP; porta própria como `servidor:porta`), a extensão e a palavra-passe. As chamadas vão por UDP, sem cifra. Chat, webapp, app de computador e notificações do iPhone precisam do servidor preparado.

### Tudo (chamadas cifradas, chat, webapp, computador)

Prepara o servidor uma vez com o **[guia passo a passo](GUIA-SERVIDOR.md)** (45 a 60 minutos). Resumo:

1. Domínio a apontar para o teu IP público e portas abertas no router.
2. Openfire (servidor de chat) instalado.
3. O script de configuração, que trata do resto:

```bash
curl -fsSL https://raw.githubusercontent.com/jmvduarte/issalink/main/servidor/configurar-issabel.sh -o configurar-issabel.sh
DOMINIO=O_TEU_DOMINIO bash configurar-issabel.sh
```

| Extensões | Para |
|---|---|
| 100–150 (ou qualquer fora de 200–250) | app Android |
| 200–250 | webapp e app de computador |

---

## Problemas comuns

Estão no fim do [guia](GUIA-SERVIDOR.md#9-problemas-comuns): palavra-passe recusada, IP bloqueado pelo fail2ban, chamadas sem som, redes que só deixam passar a porta 443.
