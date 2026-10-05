import base64, json, os, secrets, subprocess, time, urllib.request, urllib.error, uuid
from pathlib import Path
project = Path(__file__).resolve().parents[1]
if not os.environ.get('SMOKE_DATABASE_URL'):
    raise SystemExit('Set SMOKE_DATABASE_URL to a dedicated disposable PostgreSQL DB first.')
port = os.environ.get('SMOKE_PORT', '18080')
env = os.environ.copy()
env.update(DATABASE_URL=os.environ['SMOKE_DATABASE_URL'], DATABASE_USER=os.environ.get('SMOKE_DATABASE_USER', 'postgres'), DATABASE_PASSWORD=os.environ.get('SMOKE_DATABASE_PASSWORD', ''), JWT_SIGNING_KEY=base64.b64encode(secrets.token_bytes(32)).decode(), PORT=port)
log = open(project / 'target/http-smoke-server.log', 'wb')
app = subprocess.Popen(['java', '-jar', str(project / 'target/meeting-to-task-0.1.0-SNAPSHOT.jar')], env=env, stdout=log, stderr=subprocess.STDOUT)
def request(path, method='GET', data=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token: headers['Authorization'] = 'Bearer ' + token
    body = None if data is None else json.dumps(data).encode()
    req = urllib.request.Request('http://127.0.0.1:' + port + path, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=5) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, json.loads(raw) if raw else None
try:
    ready = False
    for _ in range(100):
        if app.poll() is not None: raise RuntimeError('Server exited before ready; inspect smoke log')
        try:
            status, health = request('/actuator/health')
            if status == 200 and health['status'] == 'UP': ready = True; break
        except (OSError, urllib.error.URLError): pass
        time.sleep(0.25)
    assert ready, 'Server startup timed out'
    credentials = {'email': str(uuid.uuid4()) + '@example.test', 'password': 'synthetic-smoke-password'}
    assert request('/api/v1/auth/register', 'POST', credentials)[0] == 201
    status, login = request('/api/v1/auth/login', 'POST', credentials)
    assert status == 200
    token = login['accessToken']
    status, meeting = request('/api/v1/meetings', 'POST', {'transcriptText': 'Nam: Mai sửa login nhé.\nMai: Chưa chốt hạn.', 'timezone': 'Asia/Ho_Chi_Minh'}, token)
    assert status == 201 and meeting['segmentCount'] == 2 and meeting['analysisStatus'] == 'NOT_STARTED'
    status, transcript = request('/api/v1/meetings/' + meeting['meetingId'] + '/transcript', token=token)
    assert status == 200 and transcript['segments'][0]['sourceText'] == 'Nam: Mai sửa login nhé.'
    assert request('/api/v1/auth/logout', 'POST', token=token)[0] == 204
    assert request('/api/v1/auth/me', token=token)[0] == 401
    print('HTTP smoke PASS: packaged JAR + configured PostgreSQL; health, register, login, paste preview, source, logout/revoke')
finally:
    app.terminate()
    try: app.wait(timeout=10)
    except subprocess.TimeoutExpired: app.kill(); app.wait()
    log.close()
