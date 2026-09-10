# License

## TabSSH License

**MIT License**

Copyright (c) 2024-2026 TabSSH Contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

---

## Third-Party Software Licenses

TabSSH uses the following open-source libraries. We are grateful to all developers and contributors.

---

### JSch - Java Secure Channel (Maintained Fork)

**Version:** 2.27.7  
**License:** BSD 3-Clause License  
**Copyright:** (c) 2002-2015 Atsuhiko Yamanaka, JCraft, Inc. / (c) 2018-2025 Matthias Wiedemann  
**Website:** https://github.com/mwiede/jsch  
**Maven:** com.github.mwiede:jsch

Pure Java implementation of SSH2. This fork provides modern algorithm support, security fixes, and active maintenance compatible with OpenSSH 8.8+.

```
Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. The names of the authors may not be used to endorse or promote products
   derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.
```

---

### Bouncy Castle Cryptography APIs

**Version:** 1.79 (bcprov-jdk18on, bcpkix-jdk18on)  
**License:** MIT License  
**Copyright:** (c) 2000-2025 The Legion of the Bouncy Castle Inc.  
**Website:** https://www.bouncycastle.org/

Java cryptography APIs with support for all SSH key formats.

```
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
```

---

### AndroidX Libraries

**License:** Apache License 2.0  
**Copyright:** The Android Open Source Project  
**Website:** https://developer.android.com/jetpack/androidx

Used libraries:
- `androidx.appcompat:appcompat:1.7.1`
- `androidx.core:core-ktx:1.16.0`
- `androidx.fragment:fragment-ktx:1.8.9`
- `androidx.recyclerview:recyclerview:1.3.2`
- `androidx.swiperefreshlayout:swiperefreshlayout:1.1.0`
- `androidx.viewpager2:viewpager2:1.0.0`
- `androidx.preference:preference-ktx:1.2.1`
- `androidx.constraintlayout:constraintlayout:2.1.4`
- `androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4`
- `androidx.work:work-runtime-ktx:2.9.0`
- `androidx.biometric:biometric:1.1.0`
- `androidx.room:room-runtime:2.8.4`, `androidx.room:room-ktx:2.8.4`
- `androidx.documentfile:documentfile:1.0.1`
- `androidx.concurrent:concurrent-futures:1.2.0`
- `androidx.window:window:1.3.0`

```
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

---

### Material Design Components for Android

**Version:** 1.11.0  
**License:** Apache License 2.0  
**Copyright:** The Android Open Source Project  
**Website:** https://material.io/

```
Licensed under the Apache License, Version 2.0
```

---

### Kotlin Standard Library

**License:** Apache License 2.0  
**Copyright:** 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors  
**Website:** https://kotlinlang.org/

Used libraries:
- `org.jetbrains.kotlin:kotlin-stdlib`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0`
- `org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0`

```
Licensed under the Apache License, Version 2.0
```

---

### JSR-305 Annotations

**Version:** 3.0.2  
**License:** Apache License 2.0  
**Copyright:** FindBugs Project  
**Website:** http://findbugs.sourceforge.net/

```
Licensed under the Apache License, Version 2.0
```

---

### OkHttp

**Version:** 4.12.0  
**License:** Apache License 2.0  
**Copyright:** 2019 Square, Inc.  
**Website:** https://square.github.io/okhttp/

HTTP client for Java and Android. Used by the hypervisor console WebSocket client.

```
Licensed under the Apache License, Version 2.0
```

---

### SnakeYAML

**Version:** 2.4  
**License:** Apache License 2.0  
**Copyright:** SnakeYAML project contributors  
**Website:** https://bitbucket.org/snakeyaml/snakeyaml

YAML parsing for Docker `run.yml` single-container configs. Only the safe compose/dump API is used — no JavaBean reflection.

```
Licensed under the Apache License, Version 2.0
```

---

### MPAndroidChart

**Version:** v3.1.0  
**License:** Apache License 2.0  
**Copyright:** Philipp Jahoda  
**Website:** https://github.com/PhilJay/MPAndroidChart

Chart library backing the performance dashboard and Multi-Host Dashboard metric graphs.

```
Licensed under the Apache License, Version 2.0
```

---

### Termux Terminal Emulator

**Version:** v0.118.1 (`terminal-emulator` module only, not `terminal-view`)  
**License:** Apache License 2.0  
**Copyright:** Fredrik Fornwall and Termux contributors  
**Website:** https://github.com/termux/termux-app

Terminal emulation engine (VT100/ANSI/xterm-256color). TabSSH renders with its own `TerminalView`, so only the `terminal-emulator` module is pulled in — `terminal-view` is explicitly excluded. Although the parent `termux-app` repository is GPLv3, the `terminal-emulator` module is carved out under Apache 2.0 (originally from `jackpal/Android-Terminal-Emulator`), so this dependency introduces no copyleft obligation.

```
Licensed under the Apache License, Version 2.0
```

---

### ZXing (QR Code Processing)

**License:** Apache License 2.0  
**Copyright:** ZXing authors  
**Website:** https://github.com/zxing/zxing

Used libraries:
- `com.google.zxing:core:3.5.2` — pure-Java QR decoding
- `com.journeyapps:zxing-android-embedded:4.3.0` — Android scan UI wrapper

Chosen over ML Kit Barcode scanning so QR pairing needs no Google Play Services dependency, keeping de-Googled ROMs a first-class platform.

```
Licensed under the Apache License, Version 2.0
```

---

## Apache License 2.0 (Full Text)

```
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   [... Full Apache 2.0 license text continues ...]

   END OF TERMS AND CONDITIONS
```

---

## Acknowledgments

Special thanks to:
- **JCraft** for JSch SSH library
- **The Legion of the Bouncy Castle** for comprehensive cryptography
- **Android Open Source Project** for AndroidX and Material Design
- **JetBrains** for Kotlin programming language
- **Google** for Android SDK and APIs
- **Square** for OkHttp
- **Termux contributors** for the terminal emulation engine
- **Philipp Jahoda** for MPAndroidChart
- **ZXing authors** for QR code processing
- **All open-source contributors** whose work makes TabSSH possible

TabSSH stands on the shoulders of giants. We are forever grateful.
