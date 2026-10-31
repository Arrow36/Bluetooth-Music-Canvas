#!/usr/bin/env python3
"""
Bluetooth Music Canvas - Autonomous Build Pipeline
Zero heavy IDE dependencies (Android Studio is NOT required).
Requires: JDK 8+ (or 11/17) and Android Build Tools (aapt2, android.jar, d8/r8).
"""

import os
import sys
import subprocess
import zipfile
import shutil
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parent

def find_jdk_tools():
    """Locate java, javac, jarsigner, and keytool."""
    tools = {}
    names = ["javac", "java", "jarsigner", "keytool"]

    # 1. Check JAVA_HOME
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        home_path = Path(java_home)
        for name in names:
            exe = home_path / "bin" / (name + (".exe" if sys.platform == "win32" else ""))
            if exe.exists():
                tools[name] = exe

    # 2. Check PATH
    for name in names:
        if name not in tools:
            which_path = shutil.which(name)
            if which_path:
                tools[name] = Path(which_path)

    # 3. Check common OS installation directories if still missing
    if len(tools) < len(names):
        candidates = []
        if sys.platform == "win32":
            candidates.extend(Path(r"C:\Program Files\Microsoft").glob("jdk*"))
            candidates.extend(Path(r"C:\Program Files\Java").glob("jdk*"))
            candidates.extend(Path(r"C:\Program Files\Eclipse Adoptium").glob("jdk*"))
        elif sys.platform == "darwin":
            candidates.extend(Path("/Library/Java/JavaVirtualMachines").glob("*/Contents/Home"))
        else:
            candidates.extend(Path("/usr/lib/jvm").glob("java-*"))

        for c in sorted(candidates, reverse=True):
            bin_dir = c if (c / "bin").exists() else c / "Contents" / "Home"
            for name in names:
                if name not in tools:
                    exe = bin_dir / "bin" / (name + (".exe" if sys.platform == "win32" else ""))
                    if exe.exists():
                        tools[name] = exe

    missing = [name for name in names if name not in tools]
    if missing:
        print(f"[ERROR] Missing JDK tool(s): {', '.join(missing)}.")
        print("Please install a JDK (version 8 or newer) and set JAVA_HOME or add it to PATH.")
        sys.exit(1)

    return tools

def find_android_tools():
    """Locate aapt2, android.jar, and d8/r8."""
    tools = {}

    # Check explicit environment variables first
    if os.environ.get("AAPT2_PATH"):
        tools["aapt2"] = Path(os.environ["AAPT2_PATH"])
    if os.environ.get("ANDROID_JAR"):
        tools["android_jar"] = Path(os.environ["ANDROID_JAR"])
    if os.environ.get("D8_JAR"):
        tools["d8_jar"] = Path(os.environ["D8_JAR"])

    # Search candidate Android SDK roots
    sdk_roots = []
    if os.environ.get("ANDROID_HOME"):
        sdk_roots.append(Path(os.environ["ANDROID_HOME"]))
    if os.environ.get("ANDROID_SDK_ROOT"):
        sdk_roots.append(Path(os.environ["ANDROID_SDK_ROOT"]))

    if sys.platform == "win32":
        local_app_data = os.environ.get("LOCALAPPDATA")
        if local_app_data:
            sdk_roots.append(Path(local_app_data) / "Android" / "Sdk")
    elif sys.platform == "darwin":
        sdk_roots.append(Path.home() / "Library" / "Android" / "sdk")
    else:
        sdk_roots.append(Path.home() / "Android" / "Sdk")

    # Local tools directory inside project or immediate parent
    sdk_roots.append(PROJECT_DIR / "tools")
    sdk_roots.append(PROJECT_DIR.parent)

    exe_suffix = ".exe" if sys.platform == "win32" else ""

    # 1. Search for aapt2
    if "aapt2" not in tools:
        aapt2_in_path = shutil.which("aapt2")
        if aapt2_in_path:
            tools["aapt2"] = Path(aapt2_in_path)
        else:
            for root in sdk_roots:
                if not root.exists():
                    continue
                # Direct file in root/tools
                direct = root / ("aapt2" + exe_suffix)
                if direct.exists():
                    tools["aapt2"] = direct
                    break
                # Under build-tools/*
                bt_dir = root / "build-tools"
                if bt_dir.exists():
                    versions = sorted(bt_dir.glob("*"), reverse=True)
                    for v in versions:
                        cand = v / ("aapt2" + exe_suffix)
                        if cand.exists():
                            tools["aapt2"] = cand
                            break
                    if "aapt2" in tools:
                        break

    # 2. Search for android.jar
    if "android_jar" not in tools:
        for root in sdk_roots:
            if not root.exists():
                continue
            direct = root / "android.jar"
            if direct.exists():
                tools["android_jar"] = direct
                break
            plat_dir = root / "platforms"
            if plat_dir.exists():
                plats = sorted(plat_dir.glob("android-*"), reverse=True)
                for p in plats:
                    cand = p / "android.jar"
                    if cand.exists():
                        tools["android_jar"] = cand
                        break
                if "android_jar" in tools:
                    break

    # 3. Search for d8.jar / r8.jar
    if "d8_jar" not in tools:
        for root in sdk_roots:
            if not root.exists():
                continue
            for jar_name in ["d8.jar", "r8.jar"]:
                direct = root / jar_name
                if direct.exists():
                    tools["d8_jar"] = direct
                    break
            if "d8_jar" in tools:
                break
            bt_dir = root / "build-tools"
            if bt_dir.exists():
                versions = sorted(bt_dir.glob("*"), reverse=True)
                for v in versions:
                    cand = v / "lib" / "d8.jar"
                    if cand.exists():
                        tools["d8_jar"] = cand
                        break
                if "d8_jar" in tools:
                    break

    missing = []
    if "aapt2" not in tools: missing.append("aapt2")
    if "android_jar" not in tools: missing.append("android.jar")
    if "d8_jar" not in tools: missing.append("d8.jar / r8.jar")

    if missing:
        print(f"[ERROR] Missing Android build tool(s): {', '.join(missing)}.")
        print("Please install the Android SDK or set ANDROID_HOME / ANDROID_SDK_ROOT.")
        print("You can also place them in a 'tools/' directory inside the project root.")
        sys.exit(1)

    return tools

def ensure_keystore(keytool_bin, keystore_path):
    """Ensure a standard debug keystore exists; create one if missing."""
    if not keystore_path.exists():
        print(f"[*] Generating debug keystore at {keystore_path}...")
        cmd = [
            str(keytool_bin),
            "-genkeypair",
            "-v",
            "-keystore", str(keystore_path),
            "-storepass", "android",
            "-alias", "androiddebugkey",
            "-keypass", "android",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "10000",
            "-dname", "CN=Android Debug,O=Android,C=US"
        ]
        subprocess.check_call(cmd)

def build():
    print("=" * 60)
    print("  Bluetooth Music Canvas - Build System")
    print("=" * 60)

    jdk = find_jdk_tools()
    android = find_android_tools()

    print(f"[*] Java compiler : {jdk['javac']}")
    print(f"[*] AAPT2 binary  : {android['aapt2']}")
    print(f"[*] Android framework: {android['android_jar']}")
    print(f"[*] D8/R8 dexer   : {android['d8_jar']}")

    build_dir = PROJECT_DIR / "build"
    gen_dir = build_dir / "gen"
    bin_dir = build_dir / "bin"
    res_compiled = build_dir / "compiled_res.zip"
    unaligned_apk = build_dir / "unaligned.apk"
    final_apk = PROJECT_DIR / "BluetoothMusicCanvas.apk"
    keystore = PROJECT_DIR / "debug.jks"

    # 0. Clean & Prepare
    if build_dir.exists():
        shutil.rmtree(build_dir)
    gen_dir.mkdir(parents=True, exist_ok=True)
    bin_dir.mkdir(parents=True, exist_ok=True)

    ensure_keystore(jdk["keytool"], keystore)

    # 1. Compile Resources with AAPT2
    print("\n[1/5] Compiling resources with aapt2...")
    cmd_compile = [
        str(android["aapt2"]), "compile",
        "--dir", str(PROJECT_DIR / "res"),
        "-o", str(res_compiled)
    ]
    subprocess.check_call(cmd_compile)

    # 2. Link Resources with AAPT2
    print("[2/5] Linking resources with aapt2...")
    cmd_link = [
        str(android["aapt2"]), "link",
        "-I", str(android["android_jar"]),
        "--manifest", str(PROJECT_DIR / "AndroidManifest.xml"),
        "--java", str(gen_dir),
        "-A", str(PROJECT_DIR / "assets"),
        "-o", str(unaligned_apk),
        str(res_compiled),
        "--auto-add-overlay"
    ]
    subprocess.check_call(cmd_link)

    # 3. Compile Java with javac
    print("[3/5] Compiling Java classes with javac...")
    java_files = list((PROJECT_DIR / "src").rglob("*.java")) + list(gen_dir.rglob("*.java"))
    cmd_javac = [
        str(jdk["javac"]),
        "-encoding", "UTF-8",
        "-cp", str(android["android_jar"]),
        "-d", str(bin_dir),
        "-source", "8",
        "-target", "8",
    ] + [str(f) for f in java_files]
    subprocess.check_call(cmd_javac)

    # 4. Compile Bytecode with D8
    print("[4/5] Compiling Dalvik bytecode with D8...")
    class_files = list(bin_dir.rglob("*.class"))
    cmd_d8 = [
        str(jdk["java"]),
        "-cp", str(android["d8_jar"]),
        "com.android.tools.r8.D8",
        "--lib", str(android["android_jar"]),
        "--output", str(bin_dir),
        "--min-api", "21"
    ] + [str(f) for f in class_files]
    subprocess.check_call(cmd_d8)

    dex_file = bin_dir / "classes.dex"
    if not dex_file.exists():
        raise RuntimeError("classes.dex was not generated!")

    print("      Injecting classes.dex into APK...")
    with zipfile.ZipFile(unaligned_apk, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(dex_file, "classes.dex")

    # 5. Sign APK with jarsigner
    print("[5/5] Signing APK with jarsigner...")
    if final_apk.exists():
        final_apk.unlink()

    cmd_sign = [
        str(jdk["jarsigner"]),
        "-keystore", str(keystore),
        "-storepass", "android",
        "-keypass", "android",
        "-sigalg", "SHA256withRSA",
        "-digestalg", "SHA-256",
        "-signedjar", str(final_apk),
        str(unaligned_apk),
        "androiddebugkey"
    ]
    subprocess.check_call(cmd_sign)

    size = os.path.getsize(final_apk)
    print("=" * 60)
    print(f"SUCCESS! Signed APK built: {final_apk.name} ({size:,} bytes)")
    print("=" * 60)

if __name__ == "__main__":
    build()
