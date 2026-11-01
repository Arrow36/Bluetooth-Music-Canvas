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

    android_jar = Path("C:/Users/Kiriko/.gemini/antigravity/scratch/android.jar")
    cp_sep = ';' if sys.platform == 'win32' else ':'
    base_cp = f"src{cp_sep}tests"
    if android_jar.exists():
        base_cp = f"{android_jar}{cp_sep}{base_cp}"

    javac_cmd = [
        "javac", "-encoding", "UTF-8",
        "-cp", base_cp,
        str(ROOT / "src" / "com" / "bluetoothmusiccanvas" / "SongIdentity.java"),
        str(ROOT / "src" / "com" / "bluetoothmusiccanvas" / "MetadataNormalizer.java"),
        str(ROOT / "src" / "com" / "bluetoothmusiccanvas" / "MediaClassifier.java"),
        str(ROOT / "src" / "com" / "bluetoothmusiccanvas" / "MediaStateStore.java"),
        str(ROOT / "tests" / "TestMediaStateStore.java"),
        str(ROOT / "tests" / "TestStep2CacheAndTickets.java"),
        str(ROOT / "tests" / "TestMediaClassifier.java"),
        "-d", str(bin_tests)
    ]
    if subprocess.call(javac_cmd) != 0:
        print("[FAIL] Failed to compile Java tests.")
        sys.exit(1)

    for test_class in ["tests.TestMediaStateStore", "tests.TestStep2CacheAndTickets", "tests.TestMediaClassifier"]:
        java_cmd = [
            "java", "-ea",
            "-cp", f"{bin_tests}{cp_sep}tests",
            test_class
        ]
        if subprocess.call(java_cmd) != 0:
            print(f"[FAIL] Java test {test_class} failed.")
            sys.exit(1)

    print("\n==================================================")
    print("ALL TEST SUITES (JS + Java) PASSED SUCCESSFULLY!")
    print("==================================================")

if __name__ == "__main__":
    main()
