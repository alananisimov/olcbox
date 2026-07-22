#!/bin/bash
# Trimmed variant of build-apple.sh: iOS device + arm64 simulator only.
# Outputs straight to iosApp/Frameworks/, where the Xcode project expects it.
set -e

cd "$(dirname "$0")"
REPO_ROOT="$(cd ../../../../.. && pwd)"
OUTPUT_XCFRAMEWORK="$REPO_ROOT/iosApp/Frameworks/HevSocks5Tunnel.xcframework"

XCFRAMEWORK_DIR="./apple_xcframework"

buildStatic()
{
     echo "build for $1, $2, min version $3"
     local MIN_VERSION="-m$1-version-min=$3"
     make PP="xcrun --sdk $1 --toolchain $1 clang" \
          CC="xcrun --sdk $1 --toolchain $1 clang" \
          CFLAGS="-arch $2 $MIN_VERSION" \
          LFLAGS="-arch $2 $MIN_VERSION -Wl,-Bsymbolic-functions" static
     local OUTPUT_DIR="$XCFRAMEWORK_DIR/$1-$2"
     mkdir -p $OUTPUT_DIR
     local OUTPUT_ARCH_FILE="$OUTPUT_DIR/libhev-socks5-tunnel.a"
     libtool -static -o $OUTPUT_ARCH_FILE \
                   bin/libhev-socks5-tunnel.a \
                   third-part/lwip/bin/liblwip.a \
                   third-part/yaml/bin/libyaml.a \
                   third-part/hev-task-system/bin/libhev-task-system.a
     make clean
}

rm -rf $XCFRAMEWORK_DIR
rm -rf "$OUTPUT_XCFRAMEWORK"
mkdir -p "$(dirname "$OUTPUT_XCFRAMEWORK")"
mkdir $XCFRAMEWORK_DIR

buildStatic iphoneos arm64 15.0
buildStatic iphonesimulator arm64 15.0

INCLUDE_DIR="$XCFRAMEWORK_DIR/include"
mkdir -p $INCLUDE_DIR
cp ./src/hev-main.h $INCLUDE_DIR
cp ./module.modulemap $INCLUDE_DIR
xcodebuild -create-xcframework \
    -library ./apple_xcframework/iphoneos-arm64/libhev-socks5-tunnel.a -headers $INCLUDE_DIR \
    -library ./apple_xcframework/iphonesimulator-arm64/libhev-socks5-tunnel.a -headers $INCLUDE_DIR \
    -output "$OUTPUT_XCFRAMEWORK"

rm -rf ./apple_xcframework
echo "DONE $OUTPUT_XCFRAMEWORK"
