#!/usr/bin/env bash
# Linux x86_64 cloud setup. Source this script to retain exports in the caller.
# SDK licences are accepted for this explicitly requested Android development setup.
set -euo pipefail
test "$(uname -m)" = x86_64 || { echo '此脚本仅支持 Linux x86_64；其他平台请使用 Android Studio SDK Manager。' >&2; exit 1; }
android_setup_root=${ANDROID_SETUP_ROOT:-/workspace/.android-dev}
mkdir -p "$android_setup_root/downloads" "$android_setup_root/android-user"
export JAVA_HOME="$android_setup_root/jdk/jdk-17.0.13+11"
export ANDROID_HOME="$android_setup_root/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$android_setup_root/android-user"
export GRADLE_USER_HOME="$android_setup_root/gradle-home"
# Honour the cloud's existing HTTP proxy without exposing or copying credentials.
if [ -n "${HTTPS_PROXY:-}" ]; then
    android_proxy_options=$(python3 - <<'PY'
import os, re, urllib.parse
u = urllib.parse.urlparse(os.environ['HTTPS_PROXY'])
if u.username or u.password:
    raise SystemExit('Authenticated proxy requires supported JVM credential configuration; no credentials are copied.')
if not u.hostname or not re.fullmatch(r'[A-Za-z0-9.-]+', u.hostname):
    raise SystemExit('Unsupported proxy hostname')
port = u.port or 80
print(f'-Dhttps.proxyHost={u.hostname} -Dhttps.proxyPort={port} -Dhttp.proxyHost={u.hostname} -Dhttp.proxyPort={port}')
PY
)
    export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} $android_proxy_options"
    if [ -f /etc/ssl/certs/java/cacerts ]; then
        export JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
    fi
fi
android_fetch() {
    local destination="$1" url="$2"
    if [ ! -f "$destination" ]; then
        curl --fail --location --retry 2 --output "$destination.part" "$url"
        mv "$destination.part" "$destination"
    fi
}
if [ ! -x "$JAVA_HOME/bin/javac" ]; then
    android_fetch "$android_setup_root/downloads/jdk17.tar.gz" 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.13%2B11/OpenJDK17U-jdk_x64_linux_hotspot_17.0.13_11.tar.gz'
    echo "8682892fc02965930b9022c066fa164dd6f458ef4a5dc262016aa28333b30f49  $android_setup_root/downloads/jdk17.tar.gz" | sha256sum --check
    mkdir -p "$android_setup_root/jdk"
    tar -xzf "$android_setup_root/downloads/jdk17.tar.gz" -C "$android_setup_root/jdk"
fi
if [ ! -x "$ANDROID_HOME/cmdline-tools/12.0/bin/sdkmanager" ]; then
    android_fetch "$android_setup_root/downloads/commandlinetools.zip" 'https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip'
    # Official repository2-1.xml checksum for commandlinetools 11076708 (12.0).
    echo "d313adb7aedccf6cf0cfca51ec180f0059f5f8f8  $android_setup_root/downloads/commandlinetools.zip" | sha1sum --check
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    unzip -q "$android_setup_root/downloads/commandlinetools.zip" -d "$ANDROID_HOME/cmdline-tools"
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/12.0"
fi
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
"$JAVA_HOME/bin/javac" -version
"$ANDROID_HOME/cmdline-tools/12.0/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --licenses < <(yes)
"$ANDROID_HOME/cmdline-tools/12.0/bin/sdkmanager" --sdk_root="$ANDROID_HOME" 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
echo 'Android SDK 35 / Build-tools 35.0.0 / JDK 17 已准备。Gradle 由工程 Wrapper 下载并校验。'
