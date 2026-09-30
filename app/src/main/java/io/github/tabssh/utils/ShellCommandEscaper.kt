package io.github.tabssh.utils

/** Utilities for safely passing user-controlled values as POSIX shell arguments. */
object ShellCommandEscaper {
    /** Wrap [value] in single quotes, escaping embedded quotes without evaluating shell syntax. */
    fun quotePosixArgument(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
