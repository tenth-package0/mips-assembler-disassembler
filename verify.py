#!/usr/bin/env python3
"""Run Java tests and round trips; optionally verify execution using MARS 4.5."""
import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent


def run(*args):
    result = subprocess.run([str(a) for a in args], cwd=ROOT,
                            text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--mars', type=Path, help='Path to Mars4_5.jar')
    args = parser.parse_args()
    mars = args.mars.resolve() if args.mars else None
    print('Module 5 verification')
    print(subprocess.run(['java', '-version'], text=True, capture_output=True).stderr.strip())
    with tempfile.TemporaryDirectory(prefix='mhc-verify-') as folder:
        temp = Path(folder)
        classes = temp / 'classes'
        classes.mkdir()
        run('javac', '-d', classes, *sorted(ROOT.glob('*.java')))
        java = ['java', '-cp', classes]
        print(run(*java, 'Tests'), end='')
        prefix = temp / 'fizzbuzz'
        print(run(*java, 'Assembler', ROOT / 'fizzbuzz.asm', prefix).replace(str(temp), '<temp>'), end='')
        raw = prefix.with_suffix('.mhc-bin').read_bytes()
        print('Binary image: %d bytes (20 header + 180 text + 10 data)' % len(raw))
        print('SHA-256: ' + hashlib.sha256(raw).hexdigest())
        sources = [ROOT / 'fizzbuzz.asm']
        for kind in ('mhc-ascii', 'mhc-bin'):
            image = prefix.with_suffix('.' + kind)
            checked_in = ROOT / image.name
            assert image.read_bytes() == checked_in.read_bytes(), 'Stale submitted image'
            asm = temp / (kind + '.asm')
            print(run(*java, 'Disassembler', image, asm).replace(str(temp), '<temp>'), end='')
            assert asm.read_bytes() == (ROOT / 'reconstructed.asm').read_bytes()
            rebuilt = temp / (kind + '-rebuilt')
            run(*java, 'Assembler', asm, rebuilt)
            for suffix in ('.mhc-bin', '.mhc-ascii'):
                assert rebuilt.with_suffix(suffix).read_bytes() == prefix.with_suffix(suffix).read_bytes()
            print('PASS ' + kind + ': reconstructed source and BOTH reassembled files match')
            sources.append(asm)
        # Verify CLI failure status as well as error text, independently of unit tests.
        invalid = temp / 'invalid.asm'
        invalid.write_text('.text\nmul $t0, $t1, $t2\n')
        failure = subprocess.run([str(x) for x in [*java, 'Assembler', invalid]],
                                 text=True, capture_output=True)
        assert failure.returncode == 1 and 'Line 2' in failure.stderr
        print('PASS CLI invalid input: exit 1; ' + failure.stderr.strip())
        expected = [('Fizz' if n % 3 == 0 else '') + ('Buzz' if n % 5 == 0 else '')
                    or str(n) for n in range(1, 101)]
        if mars:
            for asm in sources:
                # nc: no banner; sm: start at main; no db: delay slots disabled.
                output = run('java', '-jar', mars, 'nc', 'sm', 'ae1', 'se1', '100000', asm)
                assert output.strip().splitlines() == expected, 'FizzBuzz differs: ' + str(asm)
                print('PASS MARS: ' + asm.name + ' prints all 100 expected lines')
            print('Expected output sample: ' + ', '.join(expected[:15]))
        else:
            print('SKIPPED MARS execution: supply --mars /path/to/Mars4_5.jar')
        print('VERIFICATION PASSED')


if __name__ == '__main__':
    main()
