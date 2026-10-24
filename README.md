# otg-adb-file-manager

Native Android Kotlin application for USB OTG ADB file management.

## Target features

- USB device detector and permission handling
- ADB authorization and USB transport
- Remote file browser for Device B
- Multi-select files and folders
- Transfer A -> B and B -> A
- Destination picker
- Progress, speed, cancellation and completion UI
- GitHub Actions APK build

## Current status

Initial project scaffold only.

The USB detector lists attached USB devices. It does not yet
establish an ADB session, browse remote files or transfer data.

## Requirements

- JDK 17
- Android SDK Platform 35
- Android Gradle Plugin 8.13.2
- Compatible Gradle installation

## Build

    gradle assembleDebug

A valid Android SDK installation is required.
