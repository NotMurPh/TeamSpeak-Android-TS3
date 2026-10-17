# Java Native Access 5.19.1

This distribution selects **Apache-2.0** for JNA, including its native
`libjnidispatch` library. JNA is offered under Apache-2.0 OR
LGPL-2.1-or-later; the alternative LGPL is not the license selected here.
The full Apache license is in `Apache-2.0.txt`.

Upstream `native/dispatch.c` copyright notices:

```text
Copyright (c) 1998 Sun Microsystems, Inc. All Rights Reserved.
Copyright (c) 2007-2013 Timothy Wall. All Rights Reserved.
Copyright (c) 2007 Wayne Meissner. All Rights Reserved.
```

JNA's bundled native **libffi** has its own MIT-style license, reproduced in
`JNA-LIBFFI-LICENSE.txt`; selecting Apache for JNA does not replace that license.

Sources checked against the 5.19.1 tag and the cached JNA 5.19.1 Maven POM:

- https://github.com/java-native-access/jna/blob/5.19.1/LICENSE
- https://github.com/java-native-access/jna/blob/5.19.1/AL2.0
- https://github.com/java-native-access/jna/blob/5.19.1/native/dispatch.c
- https://github.com/java-native-access/jna/blob/5.19.1/native/libffi/LICENSE

The cached AAR contains native libraries but no top-level license/notice file.
Its nested `classes.jar` contains `META-INF/LICENSE` (the dual-license statement)
and `META-INF/AL2.0` (Apache terms). Retain these packaged notices and recheck if
the artifact, native binaries, or supported ABIs change.
