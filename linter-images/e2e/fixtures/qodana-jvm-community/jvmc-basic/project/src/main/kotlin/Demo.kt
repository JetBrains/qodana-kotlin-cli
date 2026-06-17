class Demo {
    // KotlinConstantConditions: a non-null val compared against null is always
    // true on the `!= null` branch, so the `else` branch is dead.
    fun describe(): String {
        val name = "qodana"
        return if (name != null) "named" else "anonymous"
    }
}
