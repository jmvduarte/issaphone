# Configuração do servidor Issabel

`configurar-issabel.sh` prepara um Issabel 4 (CentOS 7) com Asterisk 16 e Openfire 4.9 para o IssaFone. Está configurado para o domínio `rsi-a36357.ddns.net`.

## Antes de correr

1. O Openfire tem de estar instalado e o assistente da porta 9090 concluído, com o *XMPP Domain* igual ao domínio.
2. No router, redireciona para o IP do Issabel:
   - TCP 80, 443, 5222, 7443 e 8089;
   - UDP 5060 e 10000-20000.

   A porta 80 é obrigatória para o Let's Encrypt.
3. Cria no Issabel as extensões que quiseres. As da webapp devem ficar no intervalo 201–210. As do Android/iPhone podem ser quaisquer outras.

## Correr (como root)

```bash
curl -fsSL https://raw.githubusercontent.com/jmvduarte/issaphone/main/servidor/configurar-issabel.sh -o configurar-issabel.sh
bash configurar-issabel.sh
```

Para usar outras extensões web: `EXT_WEB=301-305 bash configurar-issabel.sh`.

Só é preciso correr o script uma vez. Podes voltar a corrê-lo sem problema, por exemplo para mudar o intervalo de extensões web.

## Criar extensões depois

Não é preciso voltar a correr o script:

- **Android/iPhone:** cria a extensão no Issabel e carrega em *Aplicar*. A conta de chat existe logo, com a mesma palavra-passe, e a extensão aparece sozinha nos contactos de toda a gente. O serviço recarrega o Openfire e as apps voltam a ligar-se em segundos.
- **Web (201–210):** igual. O serviço `issafone-webrtc` deteta a alteração e aplica as opções WebRTC à extensão nova, normalmente em poucos segundos.

Para veres o que o serviço fez: `journalctl -t issafone`.

## O que faz

| Passo | Resultado |
|-------|-----------|
| Let's Encrypt | Certificado para o domínio, renovado automaticamente. Em cada renovação, é copiado para o Asterisk e o Openfire |
| Apache | HTTPS com o certificado válido |
| Asterisk | WebSocket seguro na porta 8089 (`http.conf`) |
| WebSockets pela 443 | O Apache reencaminha `/issafone-ws/sip` (→ 8089) e `/issafone-ws/xmpp` (→ 7443). A webapp tenta primeiro a 443 e só depois as portas diretas, para funcionar em redes de empresa/escola que bloqueiam 8089 e 7443 |
| Extensões web | Opções WebRTC (DTLS, ICE, AVPF, rtcp-mux) em `sip_custom_post.conf`, aplicadas só às extensões que existem, e um serviço que as atualiza sozinho quando crias extensões |
| Openfire | Certificado válido. As contas de chat passam a ser as próprias extensões do Issabel, com a mesma palavra-passe (o Openfire autentica na tabela `asterisk.sip`), e todas pertencem ao grupo `IssaFone` |
| Notificações push | Quando ligam para uma extensão web (201–210) que não está registada, o Asterisk envia uma notificação push aos browsers dessa extensão e fica até 30 s a tocar, à espera que a webapp abra. Chaves VAPID em `/etc/issafone/`, subscrições em `/var/lib/issafone/push/`, envio por `issafone-push EXT NUMERO` |
| Firewall | Abre as portas necessárias. A 9090 fica aberta só na rede local |
| Webapp | Instalada em `https://rsi-a36357.ddns.net/issafone/` e atualizada sozinha a partir do GitHub (de 15 em 15 min; à mão: `issafone-atualizar-web`) |

Antes de alterar os ficheiros originais, o script guarda uma cópia de cada um com o sufixo `.antes-issafone`.

## Depois de correr

Se o resumo indicar que o Openfire "ainda usa os fornecedores da base de dados", é porque o assistente de instalação guardou o método de autenticação na base de dados interna, e essa tem prioridade sobre o `openfire.xml`. Resolve-se no painel do Openfire, em **Servidor → Gestão do Servidor → Propriedades do Sistema**:

| Propriedade | Valor |
|---|---|
| `provider.auth.className` | `org.jivesoftware.openfire.auth.HybridAuthProvider` |
| `provider.user.className` (se existir) | `org.jivesoftware.openfire.user.HybridUserProvider` |
| `provider.group.className` (se existir) | `org.jivesoftware.openfire.group.JDBCGroupProvider` |

Depois corre `service openfire restart`. Para confirmar, vê se as extensões aparecem em *Utilizadores/Grupos → Utilizadores*.


Só falta um passo manual: no Openfire, em **Utilizadores/Grupos → Grupos → IssaFone**, ativa **Partilha da lista de contactos**. A partir daí, cada extensão vê todas as outras na lista de contactos, sem ter de as adicionar.

A conta de administrador do Openfire, criada no assistente, continua a funcionar, porque a autenticação é híbrida: primeiro as extensões, depois as contas locais.
