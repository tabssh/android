#!/bin/bash
# Create persistent release keystore
keytool -genkey -v \
  -keystore keystore.jks \
  -alias tabssh \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass tabssh123 \
  -keypass tabssh123 \
  -dname "CN=TabSSH, OU=Development, O=TabSSH, L=Unknown, S=Unknown, C=US"
