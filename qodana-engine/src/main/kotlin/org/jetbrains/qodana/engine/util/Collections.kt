package org.jetbrains.qodana.engine.util

object Collections {

    /**
     * Filters a list based on a predicate.
     * Equivalent to Go's algorithm.Filter.
     */
    fun <T> filter(list: List<T>, predicate: (T) -> Boolean): List<T> = list.filter(predicate)

    /**
     * Returns a list with duplicate elements removed, preserving order of first appearance.
     * Equivalent to Go's algorithm.Unique.
     */
    fun <T> unique(list: List<T>): List<T> = list.distinct()
}
