# FossFiles builder image: a JDK + the Android SDK, nothing else.
#
# Build it once, then build the APKs inside it (no JDK/SDK/Gradle/Kotlin on
# the host). Easiest way: ./build-in-container.sh
#
#   podman build -t fossfiles-builder:latest -f Containerfile .
#
# Base: Eclipse Temurin JDK 17 (the version the project targets).
FROM docker.io/library/eclipse-temurin:17-jdk

ARG ANDROID_SDK_ROOT=/opt/android-sdk
ARG CMDLINE_TOOLS_VERSION=13114758
ARG ANDROID_PLATFORM=android-34
ARG ANDROID_BUILD_TOOLS=34.0.0

ENV ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT} \
    ANDROID_HOME=${ANDROID_SDK_ROOT} \
    PATH=${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:${PATH}

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl unzip ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# Install the Android command-line tools and the exact SDK components the
# project needs, and accept the SDK licenses non-interactively.
RUN curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" -o /tmp/tools.zip \
 && mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools" \
 && unzip -q /tmp/tools.zip -d /tmp/tools \
 && mv /tmp/tools/cmdline-tools "${ANDROID_SDK_ROOT}/cmdline-tools/latest" \
 && rm -rf /tmp/tools.zip /tmp/tools \
 && yes | sdkmanager --licenses > /dev/null \
 && sdkmanager --install "platform-tools" "platforms;${ANDROID_PLATFORM}" "build-tools;${ANDROID_BUILD_TOOLS}" \
 && rm -rf "${ANDROID_SDK_ROOT}/.temp" "${ANDROID_SDK_ROOT}/.downloadIntermediates" /root/.android \
 && chmod -R a+rX "${ANDROID_SDK_ROOT}"

# Keep the toolchain's own telemetry off at build time.
# (Gradle/AGP do not send telemetry by default; the Kotlin FUS reporter is opt-in.)
ENV KOTLIN_INTERNAL_COLLECT_FUS_METRICS=false

WORKDIR /project
