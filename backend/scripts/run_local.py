"""Run a disposable local backend demo; stop it with Ctrl+C. Requires Docker and Java."""
import base64
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

BACKEND = Path(__file__).resolve().parents[1]
NAME = "ai-mtt-local-" + uuid.uuid4().hex[:12]


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True).strip()


def free_port():
    with socket.socket() as sock:
        try:
            sock.bind(("127.0.0.1", 8080))
        except OSError:
            sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def main():
    created = False
    server = None
    try:
        docker("info", "--format", "{{.ServerVersion}}")
        password = secrets.token_hex(24)
        docker("run", "--detach", "--rm", "--name", NAME,
               "--label", "ai-mtt.purpose=local-demo", "-e", "POSTGRES_DB=ai_mtt_test",
               "-e", "POSTGRES_PASSWORD=" + password, "-p", "127.0.0.1::5432", "postgres:16.13-alpine")
        created = True
        for _ in range(60):
            ready = subprocess.run(["docker", "exec", NAME, "pg_isready", "-U", "postgres", "-d", "ai_mtt_test"],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if ready.returncode == 0:
                break
            time.sleep(0.5)
        else:
            raise RuntimeError("PostgreSQL chưa sẵn sàng sau 30 giây.")
        db_port = docker("port", NAME, "5432").rsplit(":", 1)[1]
        db_url = "jdbc:postgresql://127.0.0.1:" + db_port + "/ai_mtt_test"
        api_port = str(free_port())
        env = os.environ.copy()
        env.update(TEST_DATABASE_URL=db_url, TEST_DATABASE_USER="postgres", TEST_DATABASE_PASSWORD=password,
                   SMOKE_DATABASE_URL=db_url, SMOKE_DATABASE_USER="postgres", SMOKE_DATABASE_PASSWORD=password,
                   SMOKE_PORT=api_port, DATABASE_URL=db_url, DATABASE_USER="postgres", DATABASE_PASSWORD=password,
                   JWT_SIGNING_KEY=base64.b64encode(secrets.token_bytes(32)).decode(),
                   PORT=api_port, SERVER_ADDRESS="127.0.0.1",
                   EXTENSION_ORIGIN_ALLOWLIST=os.environ.get("EXTENSION_ORIGIN_ALLOWLIST", ""))
        maven = [str(BACKEND / ("mvnw.cmd" if os.name == "nt" else "mvnw")), "-B", "verify"]
        cache = Path("/private/tmp/ai-mtt-m2")
        wrapper_cache = Path("/private/tmp/ai-mtt-maven-home")
        if cache.is_dir() and wrapper_cache.is_dir():
            maven.insert(1, "-Dmaven.repo.local=" + str(cache))
            env["MAVEN_USER_HOME"] = str(wrapper_cache)
        subprocess.run(maven, cwd=BACKEND, env=env, check=True)
        subprocess.run([sys.executable, str(BACKEND / "scripts/http_smoke.py")], cwd=BACKEND, env=env, check=True)
        server = subprocess.Popen(["java", "-jar", str(BACKEND / "target/meeting-to-task-0.1.0-SNAPSHOT.jar")],
                                  cwd=BACKEND, env=env)
        health_url = "http://127.0.0.1:" + api_port + "/actuator/health"
        for _ in range(100):
            if server.poll() is not None:
                raise RuntimeError("Backend dừng trước khi sẵn sàng.")
            try:
                with urllib.request.urlopen(health_url, timeout=2) as response:
                    if json.load(response).get("status") == "UP":
                        break
            except (OSError, urllib.error.URLError):
                pass
            time.sleep(0.25)
        else:
            raise RuntimeError("Backend chưa sẵn sàng sau 25 giây.")
        print("\nBackend đang chạy: " + health_url, flush=True)
        print("Đã verify và HTTP smoke. Ctrl+C để dừng backend/DB test; dữ liệu demo là tạm.", flush=True)
        server.wait()
    except KeyboardInterrupt:
        print("\nĐang dừng phiên chạy thử...", flush=True)
    finally:
        if server is not None and server.poll() is None:
            server.terminate()
            try:
                server.wait(timeout=10)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait()
        if created:
            subprocess.run(["docker", "stop", NAME], stdout=subprocess.DEVNULL, check=False)


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print("Chạy thử chưa hoàn tất: " + str(error), file=sys.stderr)
        raise SystemExit(1)
