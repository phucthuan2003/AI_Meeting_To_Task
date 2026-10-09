import base64, json, os, secrets, subprocess, time, urllib.request, urllib.error, uuid
from pathlib import Path
project = Path(__file__).resolve().parents[1]
if not os.environ.get('SMOKE_DATABASE_URL'):
    raise SystemExit('Set SMOKE_DATABASE_URL to a dedicated disposable PostgreSQL DB first.')
port = os.environ.get('SMOKE_PORT', '18080')
env = os.environ.copy()
env.update(DATABASE_URL=os.environ['SMOKE_DATABASE_URL'], DATABASE_USER=os.environ.get('SMOKE_DATABASE_USER', 'postgres'), DATABASE_PASSWORD=os.environ.get('SMOKE_DATABASE_PASSWORD', ''), JWT_SIGNING_KEY=base64.b64encode(secrets.token_bytes(32)).decode(), PORT=port, SERVER_ADDRESS='127.0.0.1', ANALYSIS_WORKER_ENABLED='false', OPENAI_API_KEY='', GEMINI_API_KEY='')
log = open(project / 'target/http-smoke-server.log', 'wb')
app = subprocess.Popen(['java', '-jar', str(project / 'target/meeting-to-task-0.1.0-SNAPSHOT.jar')], env=env, stdout=log, stderr=subprocess.STDOUT)
def request(path, method='GET', data=None, token=None, key=None):
    headers = {'Content-Type': 'application/json'}
    if token: headers['Authorization'] = 'Bearer ' + token
    if key: headers['Idempotency-Key'] = key
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
    status, policies = request('/api/v1/analysis-policies', token=token)
    assert status == 200 and [p['providerId'] for p in policies] == ['openai', 'gemini']
    assert all(p['providerReady'] is False for p in policies)
    meeting_path = '/api/v1/meetings/' + meeting['meetingId']
    input_body = {'expectedInputVersion': meeting['inputVersion'], 'providerId': 'gemini', 'processingPolicyId': 'foundation-v1'}
    key = str(uuid.uuid4())
    status, job = request(meeting_path + '/analysis-jobs', 'POST', input_body, token, key)
    assert status == 202 and job['status'] == 'QUEUED' and job['providerId'] == 'gemini'
    status, repeated = request(meeting_path + '/analysis-jobs', 'POST', input_body, token, key)
    assert status == 202 and repeated['jobId'] == job['jobId']
    status, blocked = request(meeting_path + '/input', 'PATCH', {'expectedVersion': meeting['inputVersion'], 'transcriptText': 'Do not overwrite running input.'}, token)
    assert status == 409 and blocked['code'] == 'RESOURCE_BUSY'
    for _ in range(2):
        status, cancelled = request('/api/v1/jobs/' + job['jobId'] + '/cancel', 'POST', token=token)
        assert status == 202 and cancelled['status'] == 'CANCELLED'
    status, recovery = request(meeting_path + '/analysis-jobs', 'POST', input_body, token, str(uuid.uuid4()))
    assert status == 202 and recovery['status'] == 'QUEUED'

    # A real JVM restart retains the DB queue and JWT key; no panel process owns the job.
    app.terminate()
    try: app.wait(timeout=10)
    except subprocess.TimeoutExpired: app.kill(); app.wait()
    env['ANALYSIS_WORKER_ENABLED'] = 'true'
    app = subprocess.Popen(['java', '-jar', str(project / 'target/meeting-to-task-0.1.0-SNAPSHOT.jar')], env=env, stdout=log, stderr=subprocess.STDOUT)
    for _ in range(100):
        if app.poll() is not None: raise RuntimeError('Restarted server exited; inspect smoke log')
        try:
            status, health = request('/actuator/health')
            if status == 200 and health['status'] == 'UP': break
        except (OSError, urllib.error.URLError): pass
        time.sleep(0.25)
    else: raise RuntimeError('Restarted server startup timed out')
    for _ in range(100):
        status, result = request('/api/v1/jobs/' + recovery['jobId'], token=token)
        assert status == 200
        if result['status'] == 'FAILED': break
        time.sleep(0.25)
    else: raise RuntimeError('Persisted queue did not finish preparation after restart')
    assert result['error']['code'] == 'PROVIDER_NOT_CONFIGURED' and result['error']['retryable'] is False
    assert result['preparedSegments'] == 2 and result['completedChunks'] == 0
    assert result['providerId'] == 'gemini'
    status, restored = request(meeting_path, token=token)
    assert status == 200 and restored['currentAnalysisJobId'] == recovery['jobId']
    assert request('/api/v1/auth/logout', 'POST', token=token)[0] == 204
    assert request('/api/v1/auth/me', token=token)[0] == 401
    print('HTTP smoke PASS: auth/input/source, idempotent enqueue, input lock, cancel, JVM restart/DB queue, explicit provider-unavailable failure, logout/revoke')
finally:
    app.terminate()
    try: app.wait(timeout=10)
    except subprocess.TimeoutExpired: app.kill(); app.wait()
    log.close()
