# SPDX-License-Identifier: GPL-2.0-or-later
import argparse
import pathlib
import platform
import subprocess
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('--ndk', required=True, help='Android NDK root')
parser.add_argument('--adb', required=True, help='Path to adb')
parser.add_argument('--serial', required=True, help='Authorized arm64 Android device')
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[2]
source = (root / 'core-saturn/mednafen/ss/vdp2_render.cpp').read_text(encoding='utf-8')
start = source.index('template<bool IsRot>\nstruct TileFetcher')
end = source.index('\nstruct RotVars', start)
output = root / 'build/saturn-tile-cache-test'
output.mkdir(parents=True, exist_ok=True)
(output / 'vdp2_tile_fetcher_fixture.inc').write_text(source[start:end], encoding='utf-8')
host = {'Windows': 'windows-x86_64', 'Linux': 'linux-x86_64', 'Darwin': 'darwin-x86_64'}[platform.system()]
compiler_name = 'aarch64-linux-android26-clang++' + ('.cmd' if platform.system() == 'Windows' else '')
compiler = pathlib.Path(args.ndk) / 'toolchains/llvm/prebuilt' / host / 'bin' / compiler_name
binary = output / 'tile_row_cache_test'
subprocess.run([str(compiler), '-std=c++11', '-O2', '-fno-strict-aliasing', '-static-libstdc++',
    '-I', str(output), '-I', str(root / 'core-saturn/mednafen/ss'),
    str(root / 'core-saturn/tests/tile_row_cache_test.cpp'), '-o', str(binary)], check=True)
remote = '/data/local/tmp/emucoreh-vdp2-test-' + uuid.uuid4().hex
try:
    subprocess.run([args.adb, '-s', args.serial, 'push', str(binary), remote], check=True)
    subprocess.run([args.adb, '-s', args.serial, 'shell', 'chmod', '755', remote], check=True)
    subprocess.run([args.adb, '-s', args.serial, 'shell', remote], check=True)
finally:
    subprocess.run([args.adb, '-s', args.serial, 'shell', 'rm', '-f', remote], check=False)
