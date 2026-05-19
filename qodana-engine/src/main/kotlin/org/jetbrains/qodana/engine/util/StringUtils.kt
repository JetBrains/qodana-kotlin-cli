package org.jetbrains.qodana.engine.util

object StringUtils {
    /**
     * Splits string by separator and returns element at index, or empty string if out of range.
     */
    fun safeSplit(
        s: String,
        sep: String,
        index: Int,
    ): String {
        if (index < 0) return ""
        val parts = s.split(sep)
        return if (index < parts.size) parts[index] else ""
    }

    /**
     * Checks if a list contains a given string.
     */
    fun contains(
        list: List<String>,
        element: String,
    ): Boolean = element in list

    /**
     * Appends element to list only if not already present.
     */
    fun appendIfAbsent(
        list: MutableList<String>,
        element: String,
    ) {
        if (element !in list) list.add(element)
    }

    /**
     * Removes first occurrence of element from list.
     */
    fun remove(
        list: MutableList<String>,
        element: String,
    ) {
        list.remove(element)
    }

    /**
     * Wraps string in quotes if it contains spaces and isn't already quoted.
     */
    fun quoteIfSpace(s: String): String {
        if (s.isEmpty()) return s
        if (isStringQuoted(s)) return s
        return if (s.contains(' ')) "\"$s\"" else s
    }

    /**
     * On Windows, wraps string in quotes if it contains batch-special characters.
     */
    fun quoteForWindows(s: String): String {
        val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
        return if (isWindows && containsWinSpecialChar(s)) "\"$s\"" else s
    }

    /**
     * Returns OS-appropriate quoted path.
     */
    fun getQuotedPath(path: String): String {
        val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
        return if (isWindows) quoteForWindows(path) else quoteIfSpace(path)
    }

    /**
     * Checks if string starts and ends with double quotes.
     */
    fun isStringQuoted(s: String): Boolean = s.length >= 2 && s.startsWith('"') && s.endsWith('"')

    /**
     * Checks if string contains Windows batch special characters.
     */
    fun containsWinSpecialChar(s: String): Boolean {
        val specials = charArrayOf(' ', '(', ')', '^', '&', '|', '<', '>')
        return s.any { it in specials }
    }

    /**
     * Reverses a mutable list in place.
     */
    fun <T> reverse(list: MutableList<T>) {
        list.reverse()
    }
}
