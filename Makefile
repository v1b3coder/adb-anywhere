# adb-anywhere — build, test, e2e and install helpers.
#
# `make` or `make help` lists the targets. Variables can be overridden on the
# command line, for example `make adbd-abi ABI=x86_64` or
# `make build ADBD_SKIP_BUILD=1`.
#
#   ABI                one adbd ABI for adbd-abi (default arm64-v8a)
#   ADBD_SKIP_BUILD    1 = use app/adbd-bin/ as-is, no NDK build
#   E2E_SERIAL         rooted emulator serial for the e2e targets
#   AVD                AVD name for the emulator target; required
#   AVD_GPU            avd-gpu wrapper; detected on PATH, empty forces the plain emulator
#   EMULATOR_PORT      console port of the emulator target (default 5560)
#   EMULATOR_ARGS      extra emulator arguments for the emulator target
#   EMULATOR_CORES      vCPUs for the emulator target (default 6)
#   EMULATOR_MEMORY     guest RAM in MB for the emulator target (default 8192)
#   PHONE               phone address; detected when unset
#   ADB_INSTALL_FLAGS   install flags for install-phone
#
# `make emulator AVD=<name>` boots the AVD in the foreground on EMULATOR_PORT,
# which matches the default E2E_SERIAL. The repo's AVD is `adb-anywhere-api36`
# (android-36 google_apis x86_64, 1080x2400); create it with avdmanager when it
# is missing. avd-gpu is a host-local wrapper: it finds the hardware-accelerated
# Xwayland, passes -gpu host and runs the AVD as the desktop user, so it is used
# whenever it is on PATH. A host without it keeps the SDK emulator with -gpu off;
# the default GPU path segfaults in its RenderThread on such hosts. AVD_GPU=
# forces the plain emulator, AVD_GPU=/path pins a wrapper. The target asks for
# 6 vCPUs and 8 GB; an AVD's own hw.cpu.ncore and hw.ramSize are lower (4 cores,
# 2 GB, which the emulator raises to 2.5 GB), so pass the flags on every boot.
#
# Every Gradle target builds the daemon on preBuild: adbd/build.sh
# cross-compiles the vendored AOSP adbd and syncAdbd drops the per-ABI binaries
# into app/adbd-bin/, which is never committed. A local build therefore needs the
# Android NDK; ADBD_SKIP_BUILD=1 skips the build and keeps adbd-bin/.
#
# The ABI is a product flavor: `arm` (arm64-v8a) is what build, install-phone and
# the release produce, `x86` (x86_64) is what the e2e scripts build for the emulator,
# and `universal` is a local build that installs on any ABI. Each flavor ships only
# its own adbd binary and native libraries.
#
# `make install-phone` builds the debug APK and installs it on the phone through
# the app's firewall relay. The install is an `abb_exec` call, so the call policy
# holds it for approval on the phone.
#
# Whitelisting install calls (once):
#   1. When the `abb_exec` card appears, tap Review, then Custom filter.
#   2. Keep argv[0]=package and argv[1]=install checked.
#   3. Uncheck argv[2] and later; -S, size and path vary per install.
#   4. Tap Save rule. Future `adb install` calls match the rule.
#
# The floating card only offers Review and Block; the answer is given in the
# review screen, which hides other apps' overlays while it is shown. If the card
# is a notification instead of the overlay, tap its Review action.
# The overlay cannot show over the keyguard: keep the phone unlocked and the app
# in the foreground.
#
# The install replaces the app that carries the relay, so the stream always dies
# with the old process: `adb install` ends with an empty "failed to install" and
# a non-zero exit even when the install succeeded. That message and that exit
# code are not a verdict, and nothing may read them as one. The target decides by
# hashing the installed APK against the build: it waits for the phone to come
# back and fails only when the installed bytes differ.

SHELL := /bin/sh

ABI ?= arm64-v8a
ADBD_SKIP_BUILD ?= 0
E2E_SERIAL ?= emulator-5560
# An interactive shell exports ANDROID_HOME; `make` from cron or an agent does not.
ANDROID_HOME ?= $(HOME)/Android/Sdk
export ANDROID_HOME
AVD ?=
EMULATOR_PORT ?= 5560
EMULATOR_ARGS ?= -no-audio -no-boot-anim -no-snapshot
EMULATOR_CORES ?= 6
EMULATOR_MEMORY ?= 8192
AVD_GPU ?= $(shell command -v avd-gpu 2>/dev/null)
EMULATOR_CMD ?= $(if $(AVD_GPU),$(AVD_GPU) $(AVD),$(ANDROID_HOME)/emulator/emulator -avd $(AVD) -gpu off)
# The phone address is its Tailscale address, so it is not fixed. Detect it when
# PHONE is unset, and only when adb sees exactly one non-emulator transport: zero
# or several leave it empty and the targets say so instead of talking to nothing.
PHONE ?= $(shell set -- $$(adb devices 2>/dev/null | awk '$$2 == "device" && $$1 !~ /^emulator-/ {print $$1}'); [ $$# = 1 ] && echo $$1)
ADB_INSTALL_FLAGS ?= -r
PKG := cz.slush.adbanywhere
APK := app/build/outputs/apk/arm/debug/app-arm-debug.apk

GRADLE := ./gradlew --no-daemon
ifeq ($(ADBD_SKIP_BUILD),1)
GRADLE += -PadbdSkipBuild=true
endif

.DEFAULT_GOAL := help

.PHONY: help build test lint check adbd adbd-abi adbd-tests emulator e2e e2e-firewall install-phone phone-version clean

help:
	@printf '  %-30s %s\n' \
	  'make build' 'Build the debug APK (needs the Android NDK)' \
	  'make test' 'Run the JVM unit tests' \
	  'make lint' 'Run the lint gate; errors fail' \
	  'make check' 'Run the CI gate: test, lint and build' \
	  'make adbd' 'Build adbd for every ABI' \
	  'make adbd-abi ABI=x86_64' 'Build adbd for one ABI' \
	  'make adbd-tests' 'Build the adbd shared-core tests (x86_64)' \
	  'make emulator AVD=<name>' 'Boot the AVD on the host GPU when avd-gpu exists' \
	  'make e2e' 'Run the control-plane e2e on a rooted emulator' \
	  'make e2e-firewall' 'Run the firewall e2e on a rooted emulator' \
	  'make install-phone' 'Build, install on the phone and verify the bytes' \
	  'make phone-version' 'Show the installed package version and update time' \
	  'make clean' 'Clean the Gradle build'
	@printf '\n  %s\n' \
	  'ABI: $(ABI) (override with ABI=...)' \
	  'ADBD_SKIP_BUILD: $(ADBD_SKIP_BUILD) (1 = use adbd-bin/ as-is)' \
	  'E2E serial: $(E2E_SERIAL) (override with E2E_SERIAL=...)' \
	  'AVD: $(or $(AVD),none, pass AVD=<name>) (list: emulator -list-avds)' \
	  'avd-gpu: $(or $(AVD_GPU),none found) (override with AVD_GPU=...)' \
	  'Emulator: $(EMULATOR_CORES) cores, $(EMULATOR_MEMORY) MB (override with EMULATOR_CORES=... EMULATOR_MEMORY=...)' \
	  'Phone: $(or $(PHONE),none detected) (override with PHONE=host:port)' \
	  'Install flags: $(ADB_INSTALL_FLAGS) (override with ADB_INSTALL_FLAGS=...)' \
	  'phone-version is a shell call and may also ask for approval.'

build:
	$(GRADLE) :app:assembleArmDebug

test:
	$(GRADLE) :app:testArmDebugUnitTest

lint:
	$(GRADLE) :app:lintArmDebug

check:
	$(GRADLE) :app:testArmDebugUnitTest :app:lintArmDebug :app:assembleArmDebug

adbd:
	cd adbd && ./build.sh

adbd-abi:
	cd adbd && ABIS="$(ABI)" ./build.sh

adbd-tests:
	cd adbd && ADBD_TESTS=1 ABIS=x86_64 ./build.sh

emulator:
	@test -n '$(AVD)' || { echo 'No AVD; pass AVD=<name>. Available:'; \
	  $(ANDROID_HOME)/emulator/emulator -list-avds | sed 's/^/  /'; exit 1; }
	$(EMULATOR_CMD) $(EMULATOR_ARGS) -cores $(EMULATOR_CORES) -memory $(EMULATOR_MEMORY) -port $(EMULATOR_PORT)

e2e:
	E2E_SERIAL=$(E2E_SERIAL) ./scripts/e2e-emulator.sh

e2e-firewall:
	E2E_SERIAL=$(E2E_SERIAL) ./scripts/e2e-firewall-emulator.sh

install-phone: build
	@PHONE='$(PHONE)' APK='$(APK)' PKG='$(PKG)' \
	  ADB_INSTALL_FLAGS='$(ADB_INSTALL_FLAGS)' ./scripts/install-phone.sh

phone-version:
	@test -n '$(PHONE)' || { echo 'No single phone address; pass PHONE=host:port.'; exit 1; }
	@adb connect $(PHONE) >/dev/null 2>&1 || true
	@adb -s $(PHONE) shell "dumpsys package $(PKG) | grep -E 'versionName=|lastUpdateTime='" | sed 's/^ */  /'

clean:
	./gradlew clean --no-daemon
