/**
 * Precompiled [graalvm-native.gradle.kts][Graalvm_native_gradle] script plugin.
 *
 * @see Graalvm_native_gradle
 */
public
class GraalvmNativePlugin : org.gradle.api.Plugin<org.gradle.api.Project> {
    override fun apply(target: org.gradle.api.Project) {
        try {
            Class
                .forName("Graalvm_native_gradle")
                .getDeclaredConstructor(org.gradle.api.Project::class.java, org.gradle.api.Project::class.java)
                .newInstance(target, target)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}
