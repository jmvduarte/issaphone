# Teste do motor SIP: arranca o baresip com uma configuração mínima, liga-se ao
# ctrl_tcp e confirma que responde ao comando "about".
#   python testar.py <caminho do baresip>
import json, os, socket, subprocess, sys, tempfile, time

exe = sys.argv[1]
d = tempfile.mkdtemp()
porta = 47123
som = 'wasapi' if os.name == 'nt' else 'alsa'
with open(os.path.join(d, 'config'), 'w') as f:
    f.write(f"""sip_listen 127.0.0.1:5064
sip_transports udp,tcp
audio_player {som},default
audio_source {som},default
audio_alert {som},default
module g711.so
module_app account.so
module_app menu.so
module_app ctrl_tcp.so
ctrl_tcp_listen 127.0.0.1:{porta}
""")
open(os.path.join(d, 'accounts'), 'w').close()

p = subprocess.Popen([exe, '-f', d], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
s = None
for _ in range(50):
    try:
        s = socket.create_connection(('127.0.0.1', porta), timeout=2)
        break
    except OSError:
        time.sleep(0.2)
if not s:
    p.kill(); print(p.stdout.read().decode(errors='replace')); sys.exit('ctrl_tcp não respondeu')

def enviar(cmd, params=None):
    msg = {'command': cmd, 'token': cmd}
    if params: msg['params'] = params
    corpo = json.dumps(msg).encode()
    s.sendall(str(len(corpo)).encode() + b':' + corpo + b',')
    buf = b''
    fim = time.time() + 5
    while time.time() < fim:
        buf += s.recv(65536)
        while b':' in buf:
            n, resto = buf.split(b':', 1)
            n = int(n)
            if len(resto) < n + 1: break
            m = json.loads(resto[:n]); buf = resto[n + 1:]
            if m.get('response') and m.get('token') == cmd:
                return m
    raise SystemExit(f'sem resposta a {cmd}')

r = enviar('about')
print('about:', r.get('ok'), r.get('data', '')[:200].replace('\n', ' | '))
# Conta de teste (servidor inexistente): só confirma que o comando é aceite
r2 = enviar('uanew', '<sip:100@127.0.0.1;transport=udp>;auth_pass=x;regint=0')
print('uanew:', r2.get('ok'))
enviar('quit') if False else None
p.terminate()
try: p.wait(5)
except Exception: p.kill()
sys.exit(0 if r.get('ok') and r2.get('ok') else 1)
