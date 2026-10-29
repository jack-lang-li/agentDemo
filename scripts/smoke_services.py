"""Start the two packaged services on loopback/ephemeral ports and stop them afterwards.

Only local health/validation requests are sent. Upstream endpoints and credentials
are explicitly replaced, so this script never requests a real model or Dify.
"""
from pathlib import Path
import json
import os
import re
import subprocess
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
LOGS = ROOT / 'logs'


def request(port, path, body=None):
    data = None if body is None else json.dumps(body).encode('utf-8')
    req = urllib.request.Request(f'http://127.0.0.1:{port}{path}', data=data,
                                 headers={'Content-Type': 'application/json'})
    # Loopback smoke tests must not inherit a corporate HTTP proxy.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=3) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def main():
    LOGS.mkdir(exist_ok=True)
    children = []
    results = []
    try:
        for module in ('business-service', 'llm-service'):
            jar = ROOT / module / 'target' / f'{module}-0.0.1-SNAPSHOT.jar'
            if not jar.is_file():
                raise FileNotFoundError(f'Run mvn clean verify first: {jar}')
            stdout = LOGS / f'{module}-smoke.stdout.log'
            output = stdout.open('w', encoding='utf-8')
            env = os.environ.copy()
            if module == 'llm-service':
                env.pop('DIFY_API_KEY', None)
                env.pop('DIFY_BASE_URL', None)
            args = ['java', '-jar', str(jar), '--server.address=127.0.0.1', '--server.port=0',
                    '--agent.model.api-key=smoke-test-key', '--agent.model.base-url=http://127.0.0.1:1',
                    '--spring.main.banner-mode=off', f'--logging.file.name={LOGS / (module + "-smoke.log")}']
            if module == 'business-service':
                args += ['--dify.api-key=smoke-test-key', '--dify.base-url=http://127.0.0.1:1/v1']
            try:
                child = subprocess.Popen(args, cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT,
                                         creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            except BaseException:
                output.close()
                raise
            children.append((module, child, output, stdout))

        for module, child, output, stdout in children:
            deadline = time.monotonic() + 45
            port = None
            while time.monotonic() < deadline:
                if child.poll() is not None:
                    raise RuntimeError(f'{module} exited with {child.returncode}; see {stdout}')
                match = re.search(r'Tomcat started on port (\d+)', stdout.read_text(encoding='utf-8', errors='replace'))
                if match:
                    port = int(match.group(1))
                    break
                time.sleep(0.2)
            if port is None:
                raise TimeoutError(f'{module} did not start; see {stdout}')
            status, health = request(port, '/actuator/health')
            assert status == 200 and health['status'] == 'UP', (module, status, health)
            expected = 400 if module == 'business-service' else 404
            for path in ('/api/chat/chat', '/api/chat/dify'):
                status, _ = request(port, path, {'message': ''})
                assert status == expected, (module, path, status)
            results.append({'module': module, 'health': 'UP', 'chatValidationStatus': expected})
    finally:
        for _, child, output, _ in children:
            if child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=5)
            output.close()
    (LOGS / 'service-smoke.json').write_text(json.dumps(results, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(results, indent=2))


if __name__ == '__main__':
    main()
