#!/bin/bash
set -e

# Install Android SDK tools in Docker
echo "Setting up Android emulator..."

# Install required packages
apt-get update && apt-get install -y \
    qemu-kvm \
    libvirt-daemon-system \
    libvirt-clients \
    bridge-utils

# Download and install SDK command-line tools
cd /tmp
wget -q https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip
unzip -q commandlinetools-linux-9477386_latest.zip
mkdir -p /opt/android-sdk/cmdline-tools/latest
mv cmdline-tools/* /opt/android-sdk/cmdline-tools/latest/

export ANDROID_HOME=/opt/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator

# Accept licenses
yes | sdkmanager --licenses

# Install emulator and system image
sdkmanager "emulator" "platform-tools" "platforms;android-34" "system-images;android-34;google_apis;x86_64"

# Create AVD
echo "no" | avdmanager create avd -n TabSSH_Test -k "system-images;android-34;google_apis;x86_64" -d "pixel_6"

echo "✅ Emulator setup complete!"
