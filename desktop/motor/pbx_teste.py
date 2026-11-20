# Mini PBX de teste (usado no GitHub para testar o motor SIP): aceita o REGISTER (401 + 200), responde 180/200 a uma chamada feita
# e depois liga para o cliente (INVITE) duas vezes para testar recebida e ocupado.
import socket, sys, time, threading, uuid
IP = sys.argv[1] if len(sys.argv) > 1 else '127.0.0.1'
s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); s.bind(('0.0.0.0',5070)); s.settimeout(1)
contato=None; log=[]
SDP=f"v=0\r\no=- 1 1 IN IP4 {IP}\r\ns=-\r\nc=IN IP4 {IP}\r\nt=0 0\r\nm=audio 40000 RTP/AVP 8\r\na=rtpmap:8 PCMA/8000\r\n"
def cab(t):
    h={}
    for l in t.split('\r\n')[1:]:
        if ':' in l: k,v=l.split(':',1); h.setdefault(k.strip().lower(),v.strip())
    return h
def resp(code, h, a, extra='', corpo=''):
    r=f"SIP/2.0 {code}\r\nVia: {h['via']}\r\nFrom: {h['from']}\r\nTo: {h['to']}{'' if 'tag=' in h['to'] else ';tag=pbx'}\r\nCall-ID: {h['call-id']}\r\nCSeq: {h['cseq']}\r\n{extra}Content-Length: {len(corpo)}\r\n\r\n{corpo}"
    s.sendto(r.encode(),a)
def convidar(a):
    cid=uuid.uuid4().hex
    m=(f"INVITE sip:10002@{a[0]}:{a[1]} SIP/2.0\r\nVia: SIP/2.0/UDP 127.0.0.1:5070;branch=z9hG4bK{cid[:8]};rport\r\n"
       f"From: \"Reception\" <sip:200@127.0.0.1>;tag=t{cid[:6]}\r\nTo: <sip:10002@127.0.0.1>\r\nCall-ID: {cid}\r\nCSeq: 1 INVITE\r\n"
       f"Contact: <sip:200@{IP}:5070>\r\nMax-Forwards: 70\r\nContent-Type: application/sdp\r\nContent-Length: {len(SDP)}\r\n\r\n{SDP}")
    s.sendto(m.encode(),a)
fim=time.time()+40; enviado=0
while time.time()<fim:
    try: d,a=s.recvfrom(65535)
    except socket.timeout:
        if contato and enviado==1 and time.time()>t_conv: convidar(contato); enviado=2; t_conv=time.time()+4
        elif contato and enviado==2 and time.time()>t_conv: convidar(contato); enviado=3
        continue
    t=d.decode(errors='replace'); linha=t.split('\r\n')[0]; h=cab(t); print('PBX <<<', linha, flush=True)
    if linha.startswith('REGISTER'):
        if 'authorization' not in h: resp('401 Unauthorized',h,a,'WWW-Authenticate: Digest realm="asterisk",nonce="abc"\r\n')
        else: resp('200 OK',h,a,f"Contact: {h['contact']};expires=60\r\n"); contato=a
    elif linha.startswith('INVITE'):
        resp('100 Trying',h,a); resp('180 Ringing',h,a); time.sleep(0.5)
        resp('200 OK',h,a,f'Contact: <sip:x@{IP}:5070>\r\nContent-Type: application/sdp\r\n',SDP)
    elif linha.startswith('BYE'):
        resp('200 OK',h,a)
        if enviado==0: enviado=1; t_conv=time.time()+1
    elif linha.startswith('SIP/2.0'):
        if 'INVITE' in h.get('cseq','') and linha.split()[1] in ('200',):
            # ACK à resposta 200 da chamada recebida
            pass
