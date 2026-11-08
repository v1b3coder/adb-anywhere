# adb-anywhere — build, test, e2e and install helpers.
#
# `make` or `make help` lists the targets. Variables can be overridden on the
# command line, for example `make adbd-abi ABI=x86_64` or
# `make build ADBD_SKIP_BUILD=1`.
#
#   ABI                one adbd ABI for adbd-abi (default arm64-v8a)
#   ADBD_SKIP_BUILD    1 = use app/src/main/assets/adbd/ as-is, no NDK build
#   E2E_SERIAL         rooted emulator serial for the e2e targets
#   PHONE               phone address for install-phone
#   ADB_INSTALL_FLAGS   install flags for install-phone
#
# Every Gradle target builds the daemon on preBuild: adbd/build.sh
# cross-compiles the vendored AOSP adbd and syncAdbd copies the per-ABI
# binaries into app/src/main/assets/adbd/, which is never committed. A local
# build therefore needs the Android NDK; ADBD_SKIP_BUILD=1 skips the build and
# keeps the assets.
#
# `make install-phone` builds the debug APK and installs it on the phone through
# the app's firewall relay. The install is an `abb_exec` call, so the call policy
# holds it for approval on the phone.
#
# Whitelisting install calls (once):
#   1. When the `abb_exec` card appears, tap Custom filter.
#   2. Keep argv[0]=package and argv[1]=install checked.
#   3. Uncheck argv[2] and later; -S, size and path vary per install.
#   4. Tap Save rule. Future `adb install` calls match the rule.
#
# If the card is a notification instead of the overlay, tap Review first.
# The overlay cannot show over the keyguard: keep the phone unlocked and the app
# in the foreground.
#
# `adb install` prints an empty "failed to install" message when the package
# manager replaces the app and kills it mid-stream. The install still completes;
# verify with `make phone-version`.

SHELL := /bin/sh

ABI ?= arm64-v8a
ADBD_SKIP_BUILD ?= 0
E2E_SERIAL ?= emulator-5560
PHONE ?= 100.64.0.10:5556
ADB_INSTALL_FLAGS ?= -r
PKG := cz.slush.adbanywhere
APK := app/build/outputs/apk/debug/app-debug.apk

GRADLE := ./gradlew --no-daemon
ifeq ($(ADBD_SKIP_BUILD),1)
GRADLE += -PadbdSkipBuild=true
endif

.DEFAULT_GOAL := help

.PHONY: help build test lint check adbd adbd-abi adbd-tests e2e e2e-firewall install-phone phone-version clean

help:
	@printf '  %-30s %s\n' \
	  'make build' 'Build the debug APK (needs the Android NDK)' \
	  'make test' 'Run the JVM unit tests' \
	  'make lint' 'Run the lint gate; errors fail' \
	  'make check' 'Run the CI gate: test, lint and build' \
	  'make adbd' 'Build adbd for every ABI' \
	  'make adbd-abi ABI=x86_64' 'Build adbd for one ABI' \
	  'make adbd-tests' 'Build the adbd shared-core tests (x86_64)' \
	  'make e2e' 'Run the control-plane e2e on a rooted emulator' \
	  'make e2e-firewall' 'Run the firewall e2e on a rooted emulator' \
	  'make install-phone' 'Build and install on the phone through the relay' \
	  'make phone-version' 'Show the installed package version and update time' \
	  'make clean' 'Clean the Gradle build'
	@printf '\n  %s\n' \
	  'ABI: $(ABI) (override with ABI=...)' \
	  'ADBD_SKIP_BUILD: $(ADBD_SKIP_BUILD) (1 = use the assets as-is)' \
	  'E2E serial: $(E2E_SERIAL) (override with E2E_SERIAL=...)' \
	  'Phone: $(PHONE) (override with PHONE=host:port)' \
	  'Install flags: $(ADB_INSTALL_FLAGS) (override with ADB_INSTALL_FLAGS=...)' \
	  'phone-version is a shell call and may also ask for approval.'

build:
	$(GRADLE) :app:assembleDebug

test:
	$(GRADLE) :app:testDebugUnitTest

lint:
	$(GRADLE) :app:lintDebug

check:
	$(GRADLE) :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

adbd:
	cd adbd && ./build.sh

adbd-abi:
	cd adbd && ABIS="$(ABI)" ./build.sh

adbd-tests:
	cd adbd && ADBD_TESTS=1 ABIS=x86_64 ./build.sh

e2e:
	E2E_SERIAL=$(E2E_SERIAL) ./scripts/e2e-emulator.sh

e2e-firewall:
	E2E_SERIAL=$(E2E_SERIAL) ./scripts/e2e-firewall-emulator.sh

install-phone: build
	@echo "Connecting to $(PHONE)..."
	@adb connect $(PHONE) >/dev/null 2>&1 || true
	@printf '\n%s\n' \
	  'The install is an abb_exec call through the firewall relay.' \
	  'On the phone, approve the card:' \
	  '  1. Tap Custom filter.' \
	  '  2. Keep argv[0]=package and argv[1]=install checked.' \
	  '  3. Uncheck argv[2] and later; -S, size and path vary.' \
	  '  4. Tap Save rule to whitelist future install calls.' \
	  'If it is a notification, tap Review first. Keep the phone unlocked.' \
	  ''
	-adb -s $(PHONE) install $(ADB_INSTALL_FLAGS) $(APK)
	@printf '\n%s\n' \
	  'An empty "failed to install" message is expected: the package manager' \
	  'kills the app while replacing it and closes the relay stream. The install' \
	  'still completes. Verify with: make phone-version'

phone-version:
	adb -s $(PHONE) shell "dumpsys package $(PKG) | grep -E 'versionName|lastUpdateTime' | head -3"

clean:
	./gradlew clean --no-daemon
