#!/usr/bin/env python3
"""Writes the IntelliJ run configurations of the demos into .run/.

For every demo registered in vmath-samples (found by its `new DemoInfo("id", ...)`) it writes
  Demo - <id>              Gradle: run the demo interactively
  Demo - <id> benchmark    Gradle: a scripted run of 600 frames that prints the numbers
  Demo - <id> (debug)      Application: the launcher with the demo, for the debugger
and, once, `Demos - menu` (the launcher with its menu), `Demos - smoke` (every demo checked) and
`Demos - list`. The other configurations of .run/ are left alone. Run it from anywhere after adding
a demo: python vmath-samples/tools/make_run_configs.py. The registry test fails when a demo has no
`Demo - <id>` configuration.
"""
import os
import re
from xml.sax.saxutils import quoteattr

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
RUN = os.path.join(ROOT, '.run')
SOURCES = os.path.join(ROOT, 'vmath-samples', 'src', 'main', 'java')
FOLDER = 'Demos'
JVM = '--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xmx2g'


def file_name(name):
    return re.sub(r'[^A-Za-z0-9]+', '_', name).strip('_') + '.run.xml'


def write(name, xml):
    with open(os.path.join(RUN, file_name(name)), 'w', encoding='utf-8', newline='\n') as f:
        f.write(xml)


def gradle(name, tasks, params=''):
    task_xml = ''.join('              <option value="%s" />\n' % t for t in tasks)
    write(name, f'''<component name="ProjectRunConfigurationManager">
  <configuration default="false" name={quoteattr(name)} type="GradleRunConfiguration" factoryName="Gradle" folderName={quoteattr(FOLDER)}>
    <ExternalSystemSettings>
      <option name="executionName" />
      <option name="externalProjectPath" value="$PROJECT_DIR$" />
      <option name="externalSystemIdString" value="GRADLE" />
      <option name="scriptParameters" value={quoteattr(params)} />
      <option name="taskDescriptions">
        <list />
      </option>
      <option name="taskNames">
        <list>
{task_xml}        </list>
      </option>
      <option name="vmOptions" />
    </ExternalSystemSettings>
    <ExternalSystemDebugServerProcess>true</ExternalSystemDebugServerProcess>
    <ExternalSystemReattachDebugProcess>true</ExternalSystemReattachDebugProcess>
    <DebugAllEnabled>false</DebugAllEnabled>
    <RunAsTest>false</RunAsTest>
    <method v="2" />
  </configuration>
</component>
''')


def application(name, args):
    write(name, f'''<component name="ProjectRunConfigurationManager">
  <configuration default="false" name={quoteattr(name)} type="Application" factoryName="Application" folderName={quoteattr(FOLDER)}>
    <option name="MAIN_CLASS_NAME" value="vmath.samples.Launcher" />
    <module name="vmath.vmath-samples.main" />
    <option name="VM_PARAMETERS" value={quoteattr(JVM)} />
    <option name="PROGRAM_PARAMETERS" value={quoteattr(args)} />
    <method v="2">
      <option name="Make" enabled="true" />
    </method>
  </configuration>
</component>
''')


def demo_ids():
    ids = []
    for base, _, files in os.walk(SOURCES):
        for f in files:
            if f.endswith('.java'):
                text = open(os.path.join(base, f), encoding='utf-8').read()
                ids += re.findall(r'new DemoInfo\(\s*"([a-z0-9-]+)"', text)
    return sorted(set(ids))


def main():
    os.makedirs(RUN, exist_ok=True)
    for f in os.listdir(RUN):
        if f.startswith('Sample') or f.startswith('Demo'):
            os.remove(os.path.join(RUN, f))
    gradle('Demos - menu', [':vmath-samples:run'])
    gradle('Demos - smoke', [':vmath-samples:smoke'])
    gradle('Demos - list', [':vmath-samples:run'], '--args="--list"')
    ids = demo_ids()
    for id in ids:
        gradle('Demo - ' + id, [':vmath-samples:run'], '--args="--demo %s"' % id)
        gradle('Demo - %s benchmark' % id, [':vmath-samples:run'], '--args="--demo %s --frames 600"' % id)
        application('Demo - %s (debug)' % id, '--demo ' + id)
    print('demos:', ', '.join(ids))


if __name__ == '__main__':
    main()
