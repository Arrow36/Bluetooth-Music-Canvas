#!/usr/bin/env python3
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

def main():
    print("==================================================")
    print("Running Bluetooth Music Canvas Test Suite")
    print("==================================================")

    # 1. Run Node.js Tests
    print("\n--- Running Frontend Unit Tests (Node.js) ---")
    res1 = subprocess.call(["node", str(ROOT / "tests" / "test_phase1.js")])
    if res1 != 0:
        print("[FAIL] Phase 1 tests failed.")
        sys.exit(1)

    res2 = subprocess.call(["node", str(ROOT / "tests" / "test_phase2.js")])
    if res2 != 0:
        print("[FAIL] Phase 2 tests failed.")
        sys.exit(1)

    # 2. Run Java Tests
    print("\n--- Running State & Normalizer Tests (Java) ---")
    bin_tests = ROOT / "bin_tests"
    bin_tests.mkdir(exist_ok=True)

    javac_cmd = [
        "javac", "-encoding", "UTF-8",
        "-cp", f"src{';' if sys.platform == 'win32' else ':'}tests",
        str(ROOT / "src" / "com" / "bluetoothmusiccanvas" / "SongIdentity.java"),
        str(ROOT / "src" / "com" / "bluetoothmusiccanvas" / "MetadataNormalizer.java"),
        str(ROOT / "src" / "com" / "bluetoothmusiccanvas" / "MediaStateStore.java"),
        str(ROOT / "tests" / "TestMediaStateStore.java"),
        "-d", str(bin_tests)
    ]
    if subprocess.call(javac_cmd) != 0:
        print("[FAIL] Failed to compile Java tests.")
        sys.exit(1)

    java_cmd = [
        "java", "-ea",
        "-cp", f"{bin_tests}{';' if sys.platform == 'win32' else ':'}tests",
        "tests.TestMediaStateStore"
    ]
    if subprocess.call(java_cmd) != 0:
        print("[FAIL] Java MediaStateStore tests failed.")
        sys.exit(1)

    print("\n==================================================")
    print("ALL TEST SUITES (JS + Java) PASSED SUCCESSFULLY!")
    print("==================================================")

if __name__ == "__main__":
    main()
