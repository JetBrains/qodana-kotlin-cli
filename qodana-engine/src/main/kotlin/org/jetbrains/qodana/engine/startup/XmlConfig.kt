package org.jetbrains.qodana.engine.startup

object XmlConfig {
    fun jdkTableXml(jdkPath: String): String =
        """
<application>
  <component name="ProjectJdkTable">
    <jdk version="2">
      <name value="11" />
      <type value="JavaSDK" />
      <version value="java version &quot;11&quot;" />
      <homePath value="$jdkPath" />
      <roots>
        <annotationsPath>
          <root type="composite">
            <root url="jar://${'$'}APPLICATION_HOME_DIR$/plugins/java/lib/jdkAnnotations.jar!/" type="simple" />
          </root>
        </annotationsPath>
        <classPath>
          <root type="composite">
            <root url="jrt://$jdkPath!/java.base" type="simple" />
            <root url="jrt://$jdkPath!/java.compiler" type="simple" />
            <root url="jrt://$jdkPath!/java.datatransfer" type="simple" />
            <root url="jrt://$jdkPath!/java.desktop" type="simple" />
            <root url="jrt://$jdkPath!/java.instrument" type="simple" />
            <root url="jrt://$jdkPath!/java.logging" type="simple" />
            <root url="jrt://$jdkPath!/java.management" type="simple" />
            <root url="jrt://$jdkPath!/java.management.rmi" type="simple" />
            <root url="jrt://$jdkPath!/java.naming" type="simple" />
            <root url="jrt://$jdkPath!/java.net.http" type="simple" />
            <root url="jrt://$jdkPath!/java.prefs" type="simple" />
            <root url="jrt://$jdkPath!/java.rmi" type="simple" />
            <root url="jrt://$jdkPath!/java.scripting" type="simple" />
            <root url="jrt://$jdkPath!/java.se" type="simple" />
            <root url="jrt://$jdkPath!/java.security.jgss" type="simple" />
            <root url="jrt://$jdkPath!/java.security.sasl" type="simple" />
            <root url="jrt://$jdkPath!/java.smartcardio" type="simple" />
            <root url="jrt://$jdkPath!/java.sql" type="simple" />
            <root url="jrt://$jdkPath!/java.sql.rowset" type="simple" />
            <root url="jrt://$jdkPath!/java.transaction.xa" type="simple" />
            <root url="jrt://$jdkPath!/java.xml" type="simple" />
            <root url="jrt://$jdkPath!/java.xml.crypto" type="simple" />
            <root url="jrt://$jdkPath!/jdk.accessibility" type="simple" />
            <root url="jrt://$jdkPath!/jdk.aot" type="simple" />
            <root url="jrt://$jdkPath!/jdk.attach" type="simple" />
            <root url="jrt://$jdkPath!/jdk.charsets" type="simple" />
            <root url="jrt://$jdkPath!/jdk.compiler" type="simple" />
            <root url="jrt://$jdkPath!/jdk.crypto.cryptoki" type="simple" />
            <root url="jrt://$jdkPath!/jdk.crypto.ec" type="simple" />
            <root url="jrt://$jdkPath!/jdk.dynalink" type="simple" />
            <root url="jrt://$jdkPath!/jdk.hotspot.agent" type="simple" />
            <root url="jrt://$jdkPath!/jdk.httpserver" type="simple" />
            <root url="jrt://$jdkPath!/jdk.internal.ed" type="simple" />
            <root url="jrt://$jdkPath!/jdk.internal.jvmstat" type="simple" />
            <root url="jrt://$jdkPath!/jdk.internal.le" type="simple" />
            <root url="jrt://$jdkPath!/jdk.internal.opt" type="simple" />
            <root url="jrt://$jdkPath!/jdk.internal.vm.ci" type="simple" />
            <root url="jrt://$jdkPath!/jdk.internal.vm.compiler" type="simple" />
            <root url="jrt://$jdkPath!/jdk.internal.vm.compiler.management" type="simple" />
            <root url="jrt://$jdkPath!/jdk.jcmd" type="simple" />
            <root url="jrt://$jdkPath!/jdk.jdi" type="simple" />
            <root url="jrt://$jdkPath!/jdk.jdwp.agent" type="simple" />
            <root url="jrt://$jdkPath!/jdk.jfr" type="simple" />
            <root url="jrt://$jdkPath!/jdk.jsobject" type="simple" />
            <root url="jrt://$jdkPath!/jdk.localedata" type="simple" />
            <root url="jrt://$jdkPath!/jdk.management" type="simple" />
            <root url="jrt://$jdkPath!/jdk.management.agent" type="simple" />
            <root url="jrt://$jdkPath!/jdk.management.jfr" type="simple" />
            <root url="jrt://$jdkPath!/jdk.naming.dns" type="simple" />
            <root url="jrt://$jdkPath!/jdk.naming.rmi" type="simple" />
            <root url="jrt://$jdkPath!/jdk.net" type="simple" />
            <root url="jrt://$jdkPath!/jdk.pack" type="simple" />
            <root url="jrt://$jdkPath!/jdk.scripting.nashorn" type="simple" />
            <root url="jrt://$jdkPath!/jdk.scripting.nashorn.shell" type="simple" />
            <root url="jrt://$jdkPath!/jdk.sctp" type="simple" />
            <root url="jrt://$jdkPath!/jdk.security.auth" type="simple" />
            <root url="jrt://$jdkPath!/jdk.security.jgss" type="simple" />
            <root url="jrt://$jdkPath!/jdk.unsupported" type="simple" />
            <root url="jrt://$jdkPath!/jdk.xml.dom" type="simple" />
            <root url="jrt://$jdkPath!/jdk.zipfs" type="simple" />
          </root>
        </classPath>
        <javadocPath>
          <root type="composite" />
        </javadocPath>
      </roots>
      <additional />
    </jdk>
  </component>
</application>
""".trimStart()

    fun androidProjectDefaultXml(androidSdkPath: String): String =
        """
<application>
  <component name="ProjectManager">
    <defaultProject>
      <component name="PropertiesComponent">
        <property name="android.sdk.path" value="$androidSdkPath" />
      </component>
    </defaultProject>
  </component>
</application>
""".trimStart().trimEnd()

    const val SECURITY_XML = """<application>
    <component name="PasswordSafe">
        <option name="PROVIDER" value="KEEPASS" />
    </component>
</application>"""
}
