# SPDX-License-Identifier: GPL-3.0-or-later
"""Package the separate debuggable Vulkan QA NativeActivity, without adb.
Usage: python build_validation_apk.py SDK NATIVE_BUILD OFFICIAL_LAYER OUT JAVA_HOME
NATIVE_BUILD must contain libmodel3_vulkan_validation.so and libsupermodel_libretro.so.
The caller supplies the official Khronos Android layer; nothing is downloaded.
"""
import os
import pathlib
import subprocess
import sys
import zipfile

sdk, native, layer, output, java = map(pathlib.Path, sys.argv[1:6])
output.mkdir(parents=True, exist_ok=True)
tools = sorted((sdk / 'build-tools').iterdir(), key=lambda p: tuple(int(x) for x in p.name.split('.') if x.isdigit()))[-1]
platform = sorted((sdk / 'platforms').glob('android-*'), key=lambda p: tuple(int(x) for x in p.name.split('-')[1].split('.')))[-1]
env = dict(os.environ, JAVA_HOME=str(java))
def run(*args):
    subprocess.run([str(x) for x in args], check=True, env=env)

manifest = output / 'AndroidManifest.xml'
manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.emucoreh.model3vulkanqa">
<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/>
<application android:label="Model3 Vulkan QA" android:debuggable="true" android:hasCode="false" android:extractNativeLibs="true">
<activity android:name="android.app.NativeActivity" android:exported="true" android:theme="@android:style/Theme.Material.NoActionBar.Fullscreen">
<meta-data android:name="android.app.lib_name" android:value="model3_vulkan_validation"/>
<intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter>
</activity></application></manifest>''')
unsigned = output / 'unsigned.apk'
run(tools / 'aapt.exe', 'package', '-f', '-M', manifest, '-I', platform / 'android.jar', '-F', unsigned)
with zipfile.ZipFile(unsigned, 'a') as apk:
    for path in [native / 'libmodel3_vulkan_validation.so', native / 'libsupermodel_libretro.so', layer]:
        apk.write(path, 'lib/arm64-v8a/' + path.name, compress_type=zipfile.ZIP_STORED)
aligned = output / 'aligned.apk'
run(tools / 'zipalign.exe', '-P', '16', '-f', '4', unsigned, aligned)
key = output / 'debug.keystore'
if not key.exists():
    run(java / 'bin/keytool.exe', '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android', '-alias', 'androiddebugkey', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=Model3 QA')
result = output / 'model3-vulkan-qa.apk'
run(tools / 'apksigner.bat', 'sign', '--ks', key, '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', result, aligned)
run(tools / 'apksigner.bat', 'verify', result)
print(result)
