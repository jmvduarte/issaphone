<?php
/*
 * IssaFone — notificações push das chamadas (webapp)
 * Instalado pelo configurar-issabel.sh em /var/www/html/issafone-push/
 *
 *   GET  ?acao=chave                         → chave pública VAPID
 *   POST acao=subscrever  ext, pass, sub     → guarda a subscrição do browser (devolve um token)
 *   POST acao=remover     ext, token, endpoint
 *   GET  ?acao=ultima     ext, token         → quem ligou por último (o service worker mostra o nome)
 *
 * Compatível com o PHP 5.4 do Issabel 4.
 */
header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Headers: Content-Type');
header('Cache-Control: no-store');
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') exit;

$conf = @parse_ini_file('/etc/issafone/push.conf');
if (!$conf) responder(500, array('erro' => 'Servidor sem configuração de push'));
$pasta = $conf['pasta'];

function responder($codigo, $dados) {
    http_response_code($codigo);
    echo json_encode($dados);
    exit;
}
function campo($nome) {
    static $corpo = null;
    if ($corpo === null) {
        $corpo = json_decode(file_get_contents('php://input'), true);
        if (!is_array($corpo)) $corpo = array();
    }
    if (isset($corpo[$nome])) return $corpo[$nome];
    if (isset($_REQUEST[$nome])) return $_REQUEST[$nome];
    return '';
}
function extensao() {
    $e = (string) campo('ext');
    if (!preg_match('/^[0-9]{2,8}$/', $e)) responder(400, array('erro' => 'Extensão inválida'));
    return $e;
}
function ficheiro($e) { global $pasta; return "$pasta/$e.json"; }
function ler($e) {
    $f = ficheiro($e);
    $d = is_file($f) ? json_decode(file_get_contents($f), true) : null;
    return is_array($d) ? $d : array('token' => '', 'subs' => array());
}
function gravar($e, $d) {
    $f = ficheiro($e);
    file_put_contents("$f.tmp", json_encode($d), LOCK_EX);
    @chmod("$f.tmp", 0660);
    rename("$f.tmp", $f);
}
function tokenValido($e) {
    $d = ler($e);
    $t = (string) campo('token');
    return $t !== '' && $d['token'] !== '' && $t === $d['token'];
}

$acao = campo('acao');

if ($acao === 'chave') responder(200, array('chave' => $conf['chave_publica']));

if ($acao === 'subscrever') {
    $e = extensao();
    $pass = (string) campo('pass');
    $sub = campo('sub');
    if (!is_array($sub) || empty($sub['endpoint']) || !preg_match('#^https://#', $sub['endpoint']))
        responder(400, array('erro' => 'Subscrição inválida'));

    // A palavra-passe tem de ser a da extensão (a mesma do telefone)
    try {
        $db = new PDO('mysql:host=127.0.0.1;dbname=asterisk;charset=utf8', $conf['db_user'], $conf['db_pass']);
        $q = $db->prepare("SELECT data FROM sip WHERE id=? AND keyword='secret'");
        $q->execute(array($e));
        $secret = $q->fetchColumn();
    } catch (Exception $ex) {
        responder(500, array('erro' => 'Sem acesso à base de dados'));
    }
    if ($secret === false || $secret !== $pass) { sleep(1); responder(403, array('erro' => 'Extensão ou palavra-passe errada')); }

    $d = ler($e);
    if ($d['token'] === '') $d['token'] = bin2hex(openssl_random_pseudo_bytes(16));
    // Substitui a subscrição do mesmo browser e guarda no máximo 5 aparelhos por extensão
    $subs = array();
    foreach ($d['subs'] as $s) if ($s['endpoint'] !== $sub['endpoint']) $subs[] = $s;
    $subs[] = array('endpoint' => $sub['endpoint'], 'criada' => time());
    $d['subs'] = array_slice($subs, -5);
    gravar($e, $d);
    responder(200, array('token' => $d['token']));
}

if ($acao === 'remover') {
    $e = extensao();
    if (!tokenValido($e)) responder(403, array('erro' => 'Token inválido'));
    $d = ler($e);
    $fim = (string) campo('endpoint');
    $subs = array();
    foreach ($d['subs'] as $s) if ($s['endpoint'] !== $fim) $subs[] = $s;
    $d['subs'] = $subs;
    gravar($e, $d);
    responder(200, array('ok' => true));
}

if ($acao === 'ultima') {
    $e = extensao();
    if (!tokenValido($e)) responder(403, array('erro' => 'Token inválido'));
    $f = "$pasta/ultima-$e.json";
    $u = is_file($f) ? json_decode(file_get_contents($f), true) : null;
    // Só interessa se for recente (chamada a tocar agora)
    if (!is_array($u) || time() - $u['quando'] > 60) responder(200, array());
    responder(200, $u);
}

responder(400, array('erro' => 'Ação desconhecida'));
