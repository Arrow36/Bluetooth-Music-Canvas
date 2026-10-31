import os
import sys
import subprocess
import zipfile
import shutil
from pathlib import Path

PROJECT_DIR = Path(r"C:\Users\Kiriko\.gemini\antigravity\scratch\c10h_music_canvas")
SCRATCH_DIR = Path(r"C:\Users\Kiriko\.gemini\antigravity\scratch")

AAPT2 = SCRATCH_DIR / "aapt2.exe"
ANDROID_JAR = SCRATCH_DIR / "android.jar"
R8_JAR = SCRATCH_DIR / "r8.jar"
JAVAC = Path(r"C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot\bin\javac.exe")
JAVA = Path(r"C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot\bin\java.exe")
JARSIGNER = Path(r"C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot\bin\jarsigner.exe")
KEYSTORE = PROJECT_DIR / "debug.jks"

BUILD_DIR = PROJECT_DIR / "build"
GEN_DIR = BUILD_DIR / "gen"
BIN_DIR = BUILD_DIR / "bin"
RES_COMPILED = BUILD_DIR / "compiled_res.zip"
UNALIGNED_APK = BUILD_DIR / "unaligned.apk"
FINAL_APK = PROJECT_DIR / "C10H_MusicCanvas.apk"

def clean():
    if BUILD_DIR.exists():
        shutil.rmtree(BUILD_DIR)
    GEN_DIR.mkdir(parents=True, exist_ok=True)
    BIN_DIR.mkdir(parents=True, exist_ok=True)

def step1_aapt2_compile():
    print("[1/5] Compiling resources with aapt2...")
    cmd = [
        str(AAPT2), "compile",
        "--dir", str(PROJECT_DIR / "res"),
        "-o", str(RES_COMPILED)
    ]
    subprocess.check_call(cmd)

def step2_aapt2_link():
    print("[2/5] Linking resources with aapt2...")
    cmd = [
        str(AAPT2), "link",
        "-I", str(ANDROID_JAR),
        "--manifest", str(PROJECT_DIR / "AndroidManifest.xml"),
        "--java", str(GEN_DIR),
        "-A", str(PROJECT_DIR / "assets"),
        "-o", str(UNALIGNED_APK),
        str(RES_COMPILED),
        "--auto-add-overlay"
    ]
    subprocess.check_call(cmd)

def step3_javac():
    print("[3/5] Compiling Java classes with javac...")
    java_files = list((PROJECT_DIR / "src").rglob("*.java")) + list(GEN_DIR.rglob("*.java"))
    cmd = [
        str(JAVAC),
        "-encoding", "UTF-8",
        "-cp", str(ANDROID_JAR),
        "-d", str(BIN_DIR),
        "-source", "8",
        "-target", "8",
    ] + [str(f) for f in java_files]
    subprocess.check_call(cmd)

def step4_d8():
    print("[4/5] Compiling Dalvik bytecode with D8...")
    class_files = list(BIN_DIR.rglob("*.class"))
    cmd = [
        str(JAVA),
        "-cp", str(R8_JAR),
        "com.android.tools.r8.D8",
        "--lib", str(ANDROID_JAR),
        "--output", str(BIN_DIR),
        "--min-api", "21"
    ] + [str(f) for f in class_files]
    subprocess.check_call(cmd)

    dex_file = BIN_DIR / "classes.dex"
    assert dex_file.exists(), "classes.dex not generated!"

    print("      Injecting classes.dex into APK...")
    with zipfile.ZipFile(UNALIGNED_APK, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(dex_file, "classes.dex")

def step5_sign():
    print("[5/5] Signing APK with jarsigner...")
    if FINAL_APK.exists():
        FINAL_APK.unlink()
    cmd = [
        str(JARSIGNER),
        "-keystore", str(KEYSTORE),
        "-storepass", "android",
        "-keypass", "android",
        "-sigalg", "SHA256withRSA",
        "-digestalg", "SHA-256",
        "-signedjar", str(FINAL_APK),
        str(UNALIGNED_APK),
        "androiddebugkey"
    ]
    subprocess.check_call(cmd)
    print(f"SUCCESS! Signed APK built at: {FINAL_APK} ({os.path.getsize(FINAL_APK)} bytes)")

if __name__ == "__main__":
    clean()
    step1_aapt2_compile()
    step2_aapt2_link()
    step3_javac()
    step4_d8()
    step5_sign()
