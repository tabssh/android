# Contributing to TabSSH

Thank you for your interest in contributing to TabSSH! We welcome contributions from everyone, whether you're fixing a typo, reporting a bug, or implementing a major feature.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [How to Contribute](#how-to-contribute)
- [Development Setup](#development-setup)
- [Coding Guidelines](#coding-guidelines)
- [Commit Guidelines](#commit-guidelines)
- [Pull Request Process](#pull-request-process)
- [Reporting Bugs](#reporting-bugs)
- [Suggesting Features](#suggesting-features)
- [Translation & Localization](#translation--localization)
- [Community](#community)

---

## Code of Conduct

This project adheres to a Code of Conduct that all contributors are expected to follow:

- **Be respectful** - Treat everyone with respect and kindness
- **Be inclusive** - Welcome newcomers and help them get started
- **Be constructive** - Provide helpful feedback and suggestions
- **Be professional** - Keep discussions focused and on-topic
- **No harassment** - Zero tolerance for harassment of any kind

If you experience or witness unacceptable behavior, please report it to conduct@tabssh.dev.

---

## Getting Started

### Prerequisites

Before you start contributing, make sure you have:

1. **Git** - Version control
2. **GitHub Account** - For pull requests and issues
3. **Development Environment** - Choose one:
   - **Option A (Recommended):** Docker 20.10+
   - **Option B:** Android SDK 34 + JDK 17 + Gradle 8.1.1+

### Fork and Clone

1. **Fork the repository:**
   - Visit https://github.com/tabssh/android
   - Click the "Fork" button in the top-right corner

2. **Clone your fork:**
   ```bash
   git clone https://github.com/YOUR-USERNAME/android.git
   cd android
   ```

3. **Add upstream remote:**
   ```bash
   git remote add upstream https://github.com/tabssh/android.git
   ```

4. **Keep your fork updated:**
   ```bash
   git fetch upstream
   git checkout main
   git merge upstream/main
   ```

---

## How to Contribute

### Ways to Contribute

You can contribute in many ways:

1. **Code Contributions**
   - Fix bugs
   - Implement new features
   - Improve performance
   - Refactor code
   - Add tests

2. **Documentation**
   - Improve README and guides
   - Add code comments
   - Write tutorials
   - Fix typos

3. **Design**
   - Create themes
   - Design UI mockups
   - Improve accessibility
   - Suggest UX improvements

4. **Testing**
   - Report bugs
   - Test on different devices
   - Verify bug fixes
   - Test new features

5. **Translation**
   - Translate to new languages
   - Improve existing translations
   - Update outdated translations

6. **Community**
   - Help answer questions
   - Review pull requests
   - Participate in discussions
   - Share the project

---

## Development Setup

### Option A: Docker (Recommended)

```bash
# Build Docker image
make dev

# Build debug APKs
make build

# Install to device
make install

# View logs
make logs
```

### Option B: Local Build

```bash
# Install dependencies
# - Android SDK 34
# - JDK 17
# - Gradle 8.1.1+

# Build debug APKs
./gradlew assembleDebug

# Run tests
./gradlew test

# Install to device
./gradlew installDebug
```

### IDE Setup

**Android Studio (Recommended)**

1. Open Android Studio
2. File → Open → Select `android/` directory
3. Wait for Gradle sync to complete
4. Configure SDK: Tools → SDK Manager → Install Android SDK 34
5. Configure JDK: File → Project Structure → SDK Location → JDK 17

**IntelliJ IDEA**

1. Open IntelliJ IDEA
2. Open Project → Select `android/` directory
3. Configure Android SDK and JDK 17
4. Install Kotlin plugin if not already installed

**VS Code**

1. Install extensions:
   - Kotlin Language Support
   - Android SDK Tools
2. Open `android/` directory
3. Configure paths in `.vscode/settings.json`

---

## Coding Guidelines

### Kotlin Style Guide

We follow the official [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html):

```kotlin
// ✅ Good
class SSHConnection(
    private val hostname: String,
    private val port: Int,
    private val username: String
) {
    fun connect(): Result<Session> {
        // Implementation
    }
}

// ❌ Bad
class SSHConnection(private val hostname:String,private val port:Int,private val username:String){
    fun connect():Result<Session>{
        // Implementation
    }
}
```

### Code Style Rules

1. **Indentation:** 4 spaces (no tabs)
2. **Line Length:** 120 characters maximum
3. **Naming:**
   - Classes: `PascalCase`
   - Functions: `camelCase`
   - Constants: `SCREAMING_SNAKE_CASE`
   - Private properties: `_prefixWithUnderscore` (optional)

4. **File Organization:**
   - One public class per file
   - File name = Class name
   - Package structure follows directory structure

5. **Comments:**
   - Use KDoc for public APIs
   - Explain *why*, not *what*
   - Remove commented-out code
   - Keep comments up to date

### Example with Documentation

```kotlin
/**
 * Manages SSH connections with automatic reconnection and session persistence.
 *
 * This class handles the lifecycle of SSH connections, including:
 * - Initial connection establishment
 * - Authentication (password, key, keyboard-interactive)
 * - Session keep-alive
 * - Automatic reconnection on network changes
 *
 * @property hostname The SSH server hostname or IP address
 * @property port The SSH server port (default: 22)
 * @property username The username for authentication
 */
class SSHConnectionManager(
    private val hostname: String,
    private val port: Int = 22,
    private val username: String
) {
    /**
     * Establishes an SSH connection with the configured parameters.
     *
     * This method will attempt to connect using the configured authentication
     * method. If the connection fails, it will throw a [ConnectionException]
     * with details about the failure.
     *
     * @return A [Result] containing the established [Session] or an error
     * @throws ConnectionException if the connection cannot be established
     */
    suspend fun connect(): Result<Session> {
        // Implementation
    }
}
```

### Security Best Practices

1. **Never hardcode secrets:**
   ```kotlin
   // ❌ Bad
   val password = "mypassword123"

   // ✅ Good
   val password = securePasswordManager.getPassword(connectionId)
   ```

2. **Validate all input:**
   ```kotlin
   // ✅ Good
   fun setHostname(hostname: String) {
       require(hostname.isNotBlank()) { "Hostname cannot be blank" }
       require(hostname.length <= 255) { "Hostname too long" }
       // Additional validation
   }
   ```

3. **Use secure defaults:**
   ```kotlin
   // ✅ Good
   val strictHostKeyChecking = true
   val allowWeakCrypto = false
   ```

4. **Handle sensitive data carefully:**
   ```kotlin
   // ✅ Good
   try {
       val password = getPassword()
       // Use password
   } finally {
       password.fill('\u0000') // Clear from memory
   }
   ```

### Testing

1. **Write tests for new features:**
   ```kotlin
   @Test
   fun `connect with valid credentials should succeed`() {
       val connection = SSHConnection("example.com", 22, "user")
       val result = connection.connect("password")
       assertTrue(result.isSuccess)
   }
   ```

2. **Test edge cases:**
   ```kotlin
   @Test
   fun `connect with empty hostname should throw exception`() {
       assertThrows<IllegalArgumentException> {
           SSHConnection("", 22, "user")
       }
   }
   ```

3. **Mock external dependencies:**
   ```kotlin
   @Test
   fun `connection failure should retry`() {
       val mockSession = mock<Session>()
       whenever(mockSession.connect()).thenThrow(IOException())
       // Test retry logic
   }
   ```

---

## Commit Guidelines

### Commit Message Format

We follow the [Conventional Commits](https://www.conventionalcommits.org/) specification:

```
<type>(<scope>): <subject>

<body>

<footer>
```

### Types

- **feat:** New feature
- **fix:** Bug fix
- **docs:** Documentation changes
- **style:** Code style changes (formatting, no logic change)
- **refactor:** Code refactoring
- **perf:** Performance improvements
- **test:** Adding or updating tests
- **build:** Build system or dependency changes
- **ci:** CI/CD configuration changes
- **chore:** Other changes (maintenance, etc.)

### Examples

```bash
# Feature
feat(ssh): add support for Ed25519 keys

Implemented Ed25519 key parsing and authentication.
This provides better security and performance compared to RSA.

Fixes #123

# Bug fix
fix(terminal): prevent crash on rapid tab switching

Added synchronization to tab manager to prevent race condition
when switching tabs quickly.

Fixes #456

# Documentation
docs(readme): update installation instructions

Added steps for F-Droid installation and clarified
system requirements.

# Refactoring
refactor(crypto): extract key parsing to separate class

Moved PEM key parsing logic from SSHKeyManager to
dedicated PEMKeyParser class for better separation
of concerns.
```

### Commit Best Practices

1. **Keep commits atomic** - One logical change per commit
2. **Write clear messages** - Explain *why*, not *what*
3. **Reference issues** - Use `Fixes #123`, `Closes #456`, `Related to #789`
4. **Sign your commits** - Use GPG signing when possible

---

## Pull Request Process

### Before Submitting

1. **Update your branch:**
   ```bash
   git fetch upstream
   git rebase upstream/main
   ```

2. **Run tests:**
   ```bash
   ./gradlew test
   ./gradlew lint
   ```

3. **Build successfully:**
   ```bash
   make build
   # or
   ./gradlew assembleDebug
   ```

4. **Test on device:**
   - Install the APK on a physical device or emulator
   - Test your changes thoroughly
   - Verify no regressions

### Creating a Pull Request

1. **Push your changes:**
   ```bash
   git push origin feature/your-feature-name
   ```

2. **Open a pull request:**
   - Go to https://github.com/tabssh/android/pulls
   - Click "New Pull Request"
   - Select your fork and branch
   - Fill out the PR template completely
   - Add screenshots/videos if applicable

3. **Wait for review:**
   - Maintainers will review your PR
   - Address any feedback or requested changes
   - Be patient and respectful

### Pull Request Checklist

- [ ] Code follows style guidelines
- [ ] All tests pass
- [ ] Documentation is updated
- [ ] Commits are atomic and well-described
- [ ] PR description is complete
- [ ] Screenshots/videos added (if UI changes)
- [ ] No merge conflicts
- [ ] CI/CD pipeline passes

### After Approval

1. **Squash commits if requested:**
   ```bash
   git rebase -i HEAD~N  # N = number of commits
   ```

2. **Update if needed:**
   ```bash
   git push --force-with-lease
   ```

3. **Celebrate!** Your contribution is now part of TabSSH! 🎉

---

## Reporting Bugs

### Before Reporting

1. **Check existing issues:** Search for duplicates
2. **Update to latest version:** Ensure you're on the latest release
3. **Reproduce the bug:** Can you consistently reproduce it?
4. **Gather information:** Version, device, Android version, logs

### Bug Report Template

Use the [Bug Report template](.github/ISSUE_TEMPLATE/bug_report.yml) and include:

- Clear description of the bug
- Steps to reproduce
- Expected vs actual behavior
- Version and device information
- Logs (if available)
- Screenshots (if applicable)

### Getting Logs

```bash
# On your computer (with device connected):
adb logcat | grep TabSSH > tabssh-logs.txt

# Or use:
adb logcat -s TabSSH:V
```

---

## Suggesting Features

### Before Suggesting

1. **Check existing issues and roadmap:** Might already be planned
2. **Consider scope:** Does it fit TabSSH's goals?
3. **Think about implementation:** Is it technically feasible?

### Feature Request Template

Use the [Feature Request template](.github/ISSUE_TEMPLATE/feature_request.yml) and include:

- Clear problem statement
- Proposed solution
- Alternative solutions considered
- Use cases and examples
- Mockups or references (if applicable)

---

## Translation & Localization

### Adding a New Language

1. **Create string resources:**
   ```bash
   # Copy base strings
   cp app/src/main/res/values/strings.xml \
      app/src/main/res/values-XX/strings.xml

   # XX = language code (es, fr, de, etc.)
   ```

2. **Translate strings:**
   ```xml
   <!-- values-es/strings.xml -->
   <resources>
       <string name="app_name">TabSSH</string>
       <string name="connection_title">Conexión</string>
       <string name="connect_button">Conectar</string>
       <!-- ... -->
   </resources>
   ```

3. **Test thoroughly:**
   - Switch device language
   - Verify all strings display correctly
   - Check for truncation or layout issues

4. **Submit PR:**
   - Include all translated strings
   - Add language to README
   - Note any strings that need context

### Translation Guidelines

1. **Maintain tone:** Keep the friendly, professional tone
2. **Be concise:** UI strings should be short
3. **Use native terms:** Use terms familiar to native speakers
4. **Preserve placeholders:** Keep %s, %d, etc. intact
5. **Test on device:** Verify appearance in UI

---

## Community

### Communication Channels

- **GitHub Issues:** Bug reports and feature requests
- **GitHub Discussions:** Q&A, ideas, and general discussion
- **Email:** support@tabssh.dev
- **Twitter:** [@tabssh](https://twitter.com/tabssh)
- **Matrix:** [#tabssh:matrix.org](https://matrix.to/#/#tabssh:matrix.org)

### Getting Help

- **Documentation:** Check README, SPEC.md, and docs/
- **Search issues:** Someone may have asked before
- **Ask in discussions:** Community members are helpful
- **Be specific:** Provide details, versions, logs

### Helping Others

- Answer questions in discussions
- Review pull requests
- Test bug fixes
- Improve documentation
- Share the project

---

## Recognition

Contributors are recognized in:

- **CHANGELOG.md:** For each release
- **README.md:** In the acknowledgments section
- **Git history:** Your commits are永久 preserved
- **GitHub:** Contributor badge on your profile

All contributions, no matter how small, are valued and appreciated!

---

## License

By contributing to TabSSH, you agree that your contributions will be licensed under the **MIT License**.

See [LICENSE.md](../LICENSE.md) for details.

---

## Questions?

If you have questions about contributing:

- Open a [GitHub Discussion](https://github.com/tabssh/android/discussions)
- Email us at contribute@tabssh.dev
- Check the [FAQ in README](../README.md#faq)

---

**Thank you for contributing to TabSSH! Together, we're building the best SSH client for Android.** 🚀
