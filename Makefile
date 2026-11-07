# adb-anywhere — build, test and install helpers.
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

PHONE ?= 100.64.0.10:5556
PKG := cz.slush.adbanywhere
APK := app/build/outputs/apk/debug/app-debug.apk
E2E_SERIAL ?= emulator-5560
ADB_INSTALL_FLAGS ?= -r

.DEFAULT_GOAL := help

.PHONY: help build test e2e-firewall install-phone phone-version clean

help:
	@printf '  %-22s %s\n' \
	  'make build' 'Build the debug APK' \
	  'make test' 'Run the JVM unit tests' \
	  'make e2e-firewall' 'Run the firewall emulator e2e' \
	  'make install-phone' 'Build and install on the phone through the relay' \
	  'make phone-version' 'Show the installed package version and update time' \
	  'make clean' 'Clean the Gradle build'
	@printf '\n  %s\n' \
	  'Phone: $(PHONE) (override with PHONE=host:port)' \
	  'Install flags: $(ADB_INSTALL_FLAGS) (override with ADB_INSTALL_FLAGS=...)' \
	  'phone-version is a shell call and may also ask for approval.'

build:
	./gradlew :app:assembleDebug --no-daemon

test:
	./gradlew :app:testDebugUnitTest --no-daemon

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
