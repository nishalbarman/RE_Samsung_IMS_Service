#!/bin/bash

set -e

# Gradle needs JDK 17; try Windows JDK from WSL path first, then Linux paths
if [ -z "$JAVA_HOME" ]; then
    for jdkDir in /mnt/t/Important_Softwares_Insalled/dev_tools/Java/Jdk-17 \
                  /usr/lib/jvm/java-17-openjdk-amd64 \
                  /usr/lib/jvm/temurin-17-jdk-amd64 \
                  /usr/lib/jvm/zulu17; do
        if [ -d "$jdkDir" ]; then
            export JAVA_HOME="$jdkDir"
            break
        fi
    done
fi

# Find Java 8 (required for signapk native conscrypt) — separate from gradle Java
SIGN_JAVA=""
for javaDir in /usr/lib/jvm/java-8-openjdk-amd64/bin/ \
               /usr/lib/jvm/java-8-oracle/bin/ \
               /usr/lib/jvm/temurin-8-jdk-amd64/bin/ \
               /usr/lib/jvm/zulu8/bin/; do
    [ -d "$javaDir" ] && SIGN_JAVA="$javaDir" && break
done

if [ -z "$ANDROID_HOME" ];then
    export ANDROID_HOME=$PWD/sdk
fi

# Use gradlew.bat on Windows, gradlew on Linux
if [ "$(uname -o 2>/dev/null)" = "Msys" ] || [ -n "$WINDIR" ]; then
    GRADLE_CMD="cmd.exe /c gradlew.bat"
else
    GRADLE_CMD="./gradlew"
fi

gradleTarget=assembleDebug
target=debug
file=app-debug
if [ "$1" == "release" ];then
    gradleTarget=assembleRelease
    target=release
    file=app-release-unsigned
fi
$GRADLE_CMD $gradleTarget

apkIn="$PWD/app/build/outputs/apk/$target/${file}.apk"
apkOut="$PWD/app.apk"

SIGN_JAVA_CMD="${SIGN_JAVA}java"
if LD_LIBRARY_PATH=./signapk/ $SIGN_JAVA_CMD -jar signapk/signapk.jar keys/platform.x509.pem keys/platform.pk8 "$apkIn" "$apkOut"; then
    echo "Signed → $apkOut"
else
    cp "$apkIn" "$apkOut"
    echo "Signing skipped (use Magisk module) → $apkOut"
fi
