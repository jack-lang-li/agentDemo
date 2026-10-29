"""Audit module boundaries, JAR shape and preservation of the migration baseline.

Run after Maven verify. Python is only an optional audit tool, not a build dependency.
"""
from collections import Counter
from graphlib import TopologicalSorter
from pathlib import Path
import argparse
import json
import re
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
ALLOWED = {
    'common-core': set(),
    'common-web': set(),
    'llm-api': set(),
    'llm-client': {'llm-api'},
    'agent-core': {'llm-api'},
    'llm-provider-responses': {'llm-api'},
    'llm-service': {'llm-api', 'llm-provider-responses'},
    'business-service': {'llm-api', 'agent-core', 'common-web', 'llm-provider-responses'},
}
APPS = {'llm-service', 'business-service'}
SPLIT_SUITES = {
    'OrderToolAgentIntegrationTest': 'DefaultAgentRunnerTest',
    'BusinessToolCallExecutorTest': 'ToolCallExecutorTest',
}


def verify(libraries_only=False):
    parent = ET.parse(ROOT / 'pom.xml').getroot()
    modules = {e.text for e in parent.findall('m:modules/m:module', NS)}
    assert modules == set(ALLOWED), f'Unexpected reactor modules: {modules}'
    assert parent.findtext('m:packaging', namespaces=NS) == 'pom'
    graph = {}
    for module, allowed in ALLOWED.items():
        pom = ET.parse(ROOT / module / 'pom.xml').getroot()
        deps = {d.findtext('m:artifactId', namespaces=NS)
                for d in pom.findall('m:dependencies/m:dependency', NS)
                if d.findtext('m:groupId', namespaces=NS) == 'com.example.agent'}
        assert deps <= allowed, f'{module} has forbidden dependencies: {deps - allowed}'
        graph[module] = deps
        if module not in APPS:
            for java in (ROOT / module / 'src/main/java').rglob('*.java'):
                source = java.read_text(encoding='utf-8')
                assert not re.search(r'^import com\.example\.agent\.(business|llm\.service)\.', source, re.M), java
                if module in {'agent-core', 'llm-api', 'common-core'}:
                    assert not re.search(r'^import org\.springframework\.', source, re.M), java
    order = list(TopologicalSorter(graph).static_order())
    selected = modules - APPS if libraries_only else modules
    for module in sorted(selected):
        jar = ROOT / module / 'target' / f'{module}-0.0.1-SNAPSHOT.jar'
        with zipfile.ZipFile(jar) as archive:
            executable = any(name.startswith('BOOT-INF/') for name in archive.namelist())
            assert executable == (module in APPS), f'Wrong JAR shape: {jar}'

    baseline = json.loads((ROOT / 'docs/migration-test-baseline.json').read_text(encoding='utf-8'))
    expected = Counter({(suite['class'].rsplit('.', 1)[-1], method): count
                        for suite in baseline['suites'] for method, count in suite['methods'].items()})
    actual = Counter()
    totals = Counter()
    reports = []
    for module in sorted(selected):
        for report in sorted((ROOT / module / 'target/surefire-reports').glob('TEST-*.xml')):
            suite = ET.parse(report).getroot()
            for key in ('tests', 'failures', 'errors', 'skipped'):
                totals[key] += int(suite.attrib[key])
            reports.append({'module': module, 'class': suite.attrib['name'], 'tests': int(suite.attrib['tests'])})
            for case in suite.findall('testcase'):
                name = case.attrib['classname'].rsplit('.', 1)[-1]
                name = SPLIT_SUITES.get(name, name)
                method = re.split(r'[\[(]', case.attrib['name'])[0]
                actual[(name, method)] += 1
    assert totals['failures'] == totals['errors'] == totals['skipped'] == 0, dict(totals)
    if not libraries_only:
        missing = expected - actual
        assert not missing, f'Missing baseline cases: {missing}'
        assert all(actual[key] == count for key, count in expected.items()), 'Baseline invocation counts changed'
        assert totals['tests'] >= baseline['total']
    return {'mode': 'libraries-only' if libraries_only else 'full', 'dependencyOrder': order,
            'baselineCases': baseline['total'], 'baselinePreserved': None if libraries_only else True,
            'totals': dict(totals), 'reports': reports}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--libraries-only', action='store_true')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = verify(args.libraries_only)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))
