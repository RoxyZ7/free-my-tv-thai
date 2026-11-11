"""Rebuild the checked-in Android Rust blur libraries. No NDK is needed."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TARGETS = {
    'arm64-v8a': 'aarch64-linux-android',
    'armeabi-v7a': 'armv7-linux-androideabi',
    'x86': 'i686-linux-android',
    'x86_64': 'x86_64-linux-android',
}


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('--rustc', default='rustc')
    args = cli.parse_args()
    version = subprocess.check_output([args.rustc, '-vV'], text=True)
    host = next(line.split(': ', 1)[1] for line in version.splitlines() if line.startswith('host: '))
    sysroot = Path(subprocess.check_output([args.rustc, '--print', 'sysroot'], text=True).strip())
    lld = sysroot / 'lib/rustlib' / host / 'bin' / ('rust-lld.exe' if 'windows' in host else 'rust-lld')
    target_dir = ROOT / 'tools/bundles'
    archive = target_dir / 'rust-blur.zip'
    source = ROOT / 'native/blur/lib.rs'
    compiled = {}
    with tempfile.TemporaryDirectory() as temp:
        for abi, target in TARGETS.items():
            library = Path(temp) / abi / 'libfreemytv_blur.so'
            library.parent.mkdir()
            subprocess.run([args.rustc, source, '--edition', '2024', '--target', target, '--crate-type',
                            'cdylib', '-C', f'linker={lld}', '-C', 'linker-flavor=ld.lld',
                            '-C', 'opt-level=3', '-C', 'panic=abort', '-C', 'strip=symbols',
                            '-C', 'link-arg=--no-undefined', '-C', 'link-arg=-z',
                            '-C', 'link-arg=max-page-size=16384', '-o', library], check=True)
            compiled[f'lib/{abi}/{library.name}'] = library.read_bytes()
    with zipfile.ZipFile(archive, 'w') as output:
        for name, data in compiled.items():
            output.writestr(zipfile.ZipInfo(name), data, compress_type=zipfile.ZIP_DEFLATED)
    info = {
        'sha256': hashlib.sha256(archive.read_bytes()).hexdigest(),
        'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
        'rustc': version.splitlines()[0],
        'libraries': list(compiled),
    }
    archive.with_suffix('.json').write_text(json.dumps(info, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
