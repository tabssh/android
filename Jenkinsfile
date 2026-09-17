// Jenkins mirror of .github/workflows/{ci,security,beta,release,development}.yml
// (AI.md PART 12). Same gates, Declarative Pipeline syntax: Build/Test run
// in parallel with Security; the release-channel stages (Development/Beta/
// Release) run the canonical skeleton (AI.md PART 13) when their trigger
// condition matches. All stages run in casjaysdev/android — digest-pinned,
// never :latest — no inline tool install.

pipeline {
    agent none

    triggers {
        // Mirrors development.yml's daily schedule; push/tag triggers are
        // configured on the Jenkins job itself (SCM webhook), not here.
        cron('17 4 * * *')
    }

    options {
        disableConcurrentBuilds()
    }

    environment {
        BUILD_IMAGE = 'casjaysdev/android@sha256:40a926d7959f6bcaa635a6c4de2ede01f17071d8104af77723968522484b604e'
        TRUFFLEHOG_IMAGE = 'trufflesecurity/trufflehog@sha256:1cec88f18ca39e26e04e61fe9d886c9c4e5f2fc0ba4f2ed185cac0722bd8a076'
    }

    stages {
        stage('Gates') {
            parallel {
                stage('Build') {
                    agent {
                        docker {
                            image "${env.BUILD_IMAGE}"
                            args '-u 0:0'
                        }
                    }
                    steps {
                        sh 'chmod +x gradlew'
                        sh './gradlew kspDebugKotlin compileDebugKotlin --no-daemon'
                    }
                }

                stage('Test') {
                    agent {
                        docker {
                            image "${env.BUILD_IMAGE}"
                            args '-u 0:0'
                        }
                    }
                    steps {
                        sh 'chmod +x gradlew'
                        sh './gradlew lintDebug testDebugUnitTest --no-daemon'
                        sh '''
                            for f in README.md CHANGELOG.md AI.md IDEA.md; do
                              if [ ! -f "$f" ]; then
                                echo "ERROR: $f missing" >&2
                                exit 1
                              fi
                            done
                        '''
                    }
                }

                stage('Security') {
                    agent {
                        docker {
                            image "${env.TRUFFLEHOG_IMAGE}"
                            args '--entrypoint=""'
                        }
                    }
                    steps {
                        sh 'trufflehog git file://. --results=verified,unknown --fail'
                    }
                }
            }
        }

        stage('Development') {
            when {
                anyOf {
                    triggeredBy 'TimerTrigger'
                    branch 'main'
                }
            }
            agent {
                docker {
                    image "${env.BUILD_IMAGE}"
                    args '-u 0:0'
                }
            }
            environment {
                KEYSTORE_BASE64 = credentials('KEYSTORE_BASE64')
                KEYSTORE_PASSWORD = credentials('KEYSTORE_PASSWORD')
                // KEY_PASSWORD is optional — falls back to KEYSTORE_PASSWORD
                // (AI.md PART 13); Jenkins credentials() bindings can't be
                // conditionally absent, so this credential is provisioned
                // as an empty-string secret when no separate key password
                // exists, matching the other channels' fallback behavior.
                KEY_PASSWORD = credentials('KEY_PASSWORD')
            }
            steps {
                sh 'git config --global --add safe.directory "$WORKSPACE"'
                sh 'chmod +x gradlew'
                sh '''
                    if [ -z "$KEYSTORE_BASE64" ]; then
                      echo "ERROR: KEYSTORE_BASE64 credential is not set." >&2
                      exit 1
                    fi
                    if [ -z "$KEYSTORE_PASSWORD" ]; then
                      echo "ERROR: KEYSTORE_BASE64 is set but KEYSTORE_PASSWORD is missing." >&2
                      exit 1
                    fi
                    printf '%s' "$KEYSTORE_BASE64" | base64 -d > keystore.jks
                '''
                sh '''
                    chmod +x scripts/fetch-mosh-binaries.sh scripts/fetch-spice-libs.sh scripts/fetch-tor-binaries.sh scripts/fetch-fonts.sh
                    TABSSH_REPO="tabssh/android" scripts/fetch-mosh-binaries.sh
                    TABSSH_REPO="tabssh/android" scripts/fetch-spice-libs.sh
                    TABSSH_REPO="tabssh/android" scripts/fetch-tor-binaries.sh
                    scripts/fetch-fonts.sh
                '''
                sh '''
                    for abi in arm64-v8a armeabi-v7a x86_64 x86; do
                      for lib in libmosh-client.so libtabssh_native.so libtor.so; do
                        if [ ! -f "app/src/main/jniLibs/$abi/$lib" ]; then
                          echo "ERROR: $lib missing for $abi" >&2
                          exit 1
                        fi
                      done
                    done
                '''
                sh 'COMMIT_HASH=$(git rev-parse --short=7 HEAD); ./gradlew assembleDevel --no-daemon'
                sh './gradlew :app:cyclonedxBom --no-daemon'
                sh '''
                    mkdir -p dist
                    cp app/build/outputs/apk/devel/tabssh-android-*.apk dist/
                    cp app/build/outputs/mapping/devel/mapping.txt dist/mapping.txt
                    COMMIT_HASH=$(git rev-parse --short=7 HEAD)
                    printf 'version: %s\\ncommit: %s\\nbuild_epoch: %s\\n' \
                      "$COMMIT_HASH" "$(git rev-parse HEAD)" "$(date -u +%s)" > dist/version.txt
                    git archive --format=tar.gz -o "dist/tabssh-${COMMIT_HASH}-source.tar.gz" HEAD
                    SBOM=$(find app/build \\( -name '*.json' -a -path '*cyclonedx*' \\) -o -name 'bom.json' | head -1)
                    cp "$SBOM" dist/tabssh-sbom.cdx.json
                    cd dist
                    sha256sum -- * > sha256.txt.tmp && mv sha256.txt.tmp sha256.txt
                    sha512sum -- * | grep -v -- ' sha256.txt$' > sha512.txt.tmp && mv sha512.txt.tmp sha512.txt
                '''
                // No provenance-attestation step — GitHub-only (AI.md PART 13).
                archiveArtifacts artifacts: 'dist/*', fingerprint: true
            }
        }

        stage('Beta') {
            when {
                tag pattern: '.*beta.*', comparator: 'REGEXP'
            }
            agent {
                docker {
                    image "${env.BUILD_IMAGE}"
                    args '-u 0:0'
                }
            }
            environment {
                KEYSTORE_BASE64 = credentials('KEYSTORE_BASE64')
                KEYSTORE_PASSWORD = credentials('KEYSTORE_PASSWORD')
                KEY_PASSWORD = credentials('KEY_PASSWORD')
            }
            steps {
                sh 'git config --global --add safe.directory "$WORKSPACE"'
                sh 'chmod +x gradlew'
                sh '''
                    if [ -z "$KEYSTORE_BASE64" ] || [ -z "$KEYSTORE_PASSWORD" ]; then
                      echo "ERROR: KEYSTORE_BASE64/KEYSTORE_PASSWORD credential missing." >&2
                      exit 1
                    fi
                    printf '%s' "$KEYSTORE_BASE64" | base64 -d > keystore.jks
                '''
                sh '''
                    chmod +x scripts/fetch-mosh-binaries.sh scripts/fetch-spice-libs.sh scripts/fetch-tor-binaries.sh scripts/fetch-fonts.sh
                    TABSSH_REPO="tabssh/android" scripts/fetch-mosh-binaries.sh
                    TABSSH_REPO="tabssh/android" scripts/fetch-spice-libs.sh
                    TABSSH_REPO="tabssh/android" scripts/fetch-tor-binaries.sh
                    scripts/fetch-fonts.sh
                '''
                sh './gradlew assembleRelease --no-daemon'
                sh './gradlew :app:cyclonedxBom --no-daemon'
                sh '''
                    mkdir -p dist
                    TAG_NAME="${TAG_NAME:-$(git describe --tags --exact-match)}"
                    cp app/build/outputs/apk/release/tabssh-android-*.apk dist/
                    cp app/build/outputs/mapping/release/mapping.txt dist/mapping.txt
                    printf 'version: %s\\ncommit: %s\\nbuild_epoch: %s\\n' \
                      "$TAG_NAME" "$(git rev-parse HEAD)" "$(date -u +%s)" > dist/version.txt
                    git archive --format=tar.gz -o "dist/tabssh-${TAG_NAME}-source.tar.gz" HEAD
                    SBOM=$(find app/build \\( -name '*.json' -a -path '*cyclonedx*' \\) -o -name 'bom.json' | head -1)
                    cp "$SBOM" dist/tabssh-sbom.cdx.json
                    cd dist
                    sha256sum -- * > sha256.txt.tmp && mv sha256.txt.tmp sha256.txt
                    sha512sum -- * | grep -v -- ' sha256.txt$' > sha512.txt.tmp && mv sha512.txt.tmp sha512.txt
                '''
                archiveArtifacts artifacts: 'dist/*', fingerprint: true
            }
        }

        stage('Release') {
            when {
                tag pattern: '^v[0-9].*', comparator: 'REGEXP'
            }
            agent {
                docker {
                    image "${env.BUILD_IMAGE}"
                    args '-u 0:0'
                }
            }
            environment {
                KEYSTORE_BASE64 = credentials('KEYSTORE_BASE64')
                KEYSTORE_PASSWORD = credentials('KEYSTORE_PASSWORD')
                KEY_PASSWORD = credentials('KEY_PASSWORD')
                NVD_API_KEY = credentials('NVD_API_KEY')
            }
            steps {
                sh 'git config --global --add safe.directory "$WORKSPACE"'
                sh 'chmod +x gradlew'
                sh '''
                    if [ -z "$KEYSTORE_BASE64" ] || [ -z "$KEYSTORE_PASSWORD" ]; then
                      echo "ERROR: KEYSTORE_BASE64/KEYSTORE_PASSWORD credential missing." >&2
                      exit 1
                    fi
                    printf '%s' "$KEYSTORE_BASE64" | base64 -d > keystore.jks
                '''
                sh './gradlew test --no-daemon'
                // Aggregate, not Analyze — see AI.md PART 12; CVSS >= 7.0
                // fails; suppressions read from the shared
                // .github/dependency-check-suppressions.xml path.
                sh './gradlew dependencyCheckAggregate --no-daemon'
                sh './gradlew jacocoTestReport jacocoTestCoverageVerification --no-daemon'
                sh '''
                    chmod +x scripts/fetch-mosh-binaries.sh scripts/fetch-spice-libs.sh scripts/fetch-tor-binaries.sh scripts/fetch-fonts.sh
                    TABSSH_REPO="tabssh/android" scripts/fetch-mosh-binaries.sh
                    TABSSH_REPO="tabssh/android" scripts/fetch-spice-libs.sh
                    TABSSH_REPO="tabssh/android" scripts/fetch-tor-binaries.sh
                    scripts/fetch-fonts.sh
                '''
                sh './gradlew assembleRelease --no-daemon'
                sh './gradlew assembleFdroidRelease --no-daemon'
                sh './gradlew :app:cyclonedxBom --no-daemon'
                sh '''
                    mkdir -p dist
                    TAG_NAME="${TAG_NAME:-$(git describe --tags --exact-match)}"
                    VERSION="${TAG_NAME#v}"
                    cp app/build/outputs/apk/release/tabssh-android-*.apk dist/
                    cp app/build/outputs/mapping/release/mapping.txt dist/mapping.txt
                    printf 'version: %s\\ncommit: %s\\nbuild_epoch: %s\\n' \
                      "$VERSION" "$(git rev-parse HEAD)" "$(date -u +%s)" > dist/version.txt
                    git archive --format=tar.gz -o "dist/tabssh-${VERSION}-source.tar.gz" HEAD
                    SBOM=$(find app/build \\( -name '*.json' -a -path '*cyclonedx*' \\) -o -name 'bom.json' | head -1)
                    cp "$SBOM" dist/tabssh-sbom.cdx.json
                    cd dist
                    sha256sum -- * > sha256.txt.tmp && mv sha256.txt.tmp sha256.txt
                    sha512sum -- * | grep -v -- ' sha256.txt$' > sha512.txt.tmp && mv sha512.txt.tmp sha512.txt
                '''
                archiveArtifacts artifacts: 'dist/*', fingerprint: true
            }
        }
    }
}
