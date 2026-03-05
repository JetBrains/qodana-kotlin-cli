package org.jetbrains.qodana.engine.scan

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectDetectorTest {

    @Test
    fun `isAndroidProject true when AndroidManifest exists`(@TempDir dir: Path) {
        dir.resolve("app/src/main").createDirectories()
        dir.resolve("app/src/main/AndroidManifest.xml").writeText("<manifest/>")
        assertTrue(ProjectDetector.isAndroidProject(dir))
    }

    @Test
    fun `isAndroidProject false when no manifest`(@TempDir dir: Path) {
        dir.resolve("src/main").createDirectories()
        dir.resolve("src/main/App.java").writeText("class App {}")
        assertFalse(ProjectDetector.isAndroidProject(dir))
    }

    @Test
    fun `isAndroidProject false for nonexistent dir`(@TempDir dir: Path) {
        assertFalse(ProjectDetector.isAndroidProject(dir.resolve("nonexistent")))
    }

    @Test
    fun `containsUnityProjects true when both dirs and version file`(@TempDir dir: Path) {
        dir.resolve("Assets").createDirectories()
        dir.resolve("ProjectSettings").createDirectories()
        dir.resolve("ProjectSettings/ProjectVersion.txt").writeText("m_EditorVersion: 2021.3.0f1")
        assertTrue(ProjectDetector.containsUnityProjects(dir))
    }

    @Test
    fun `containsUnityProjects true with asset file`(@TempDir dir: Path) {
        dir.resolve("Assets").createDirectories()
        dir.resolve("ProjectSettings").createDirectories()
        dir.resolve("ProjectSettings/InputManager.asset").writeText("asset data")
        assertTrue(ProjectDetector.containsUnityProjects(dir))
    }

    @Test
    fun `containsUnityProjects false when missing Assets`(@TempDir dir: Path) {
        dir.resolve("ProjectSettings").createDirectories()
        dir.resolve("ProjectSettings/ProjectVersion.txt").writeText("v1")
        assertFalse(ProjectDetector.containsUnityProjects(dir))
    }

    @Test
    fun `containsUnityProjects false when missing ProjectSettings`(@TempDir dir: Path) {
        dir.resolve("Assets").createDirectories()
        assertFalse(ProjectDetector.containsUnityProjects(dir))
    }

    @Test
    fun `containsDotNetProjects true with sln`(@TempDir dir: Path) {
        dir.resolve("MySolution.sln").writeText("solution file")
        assertTrue(ProjectDetector.containsDotNetProjects(dir))
    }

    @Test
    fun `containsDotNetProjects true with csproj`(@TempDir dir: Path) {
        dir.resolve("src").createDirectories()
        dir.resolve("src/App.csproj").writeText("<Project/>")
        assertTrue(ProjectDetector.containsDotNetProjects(dir))
    }

    @Test
    fun `containsDotNetProjects false for java project`(@TempDir dir: Path) {
        dir.resolve("src").createDirectories()
        dir.resolve("src/App.java").writeText("class App {}")
        assertFalse(ProjectDetector.containsDotNetProjects(dir))
    }

    @Test
    fun `containsDotNetFrameworkProjects true with net45`(@TempDir dir: Path) {
        dir.resolve("App.csproj").writeText("""
            <Project>
              <PropertyGroup>
                <TargetFramework>net45</TargetFramework>
              </PropertyGroup>
            </Project>
        """.trimIndent())
        assertTrue(ProjectDetector.containsDotNetFrameworkProjects(dir))
    }

    @Test
    fun `containsDotNetFrameworkProjects true with v4 version`(@TempDir dir: Path) {
        dir.resolve("App.csproj").writeText("""
            <Project>
              <PropertyGroup>
                <TargetFrameworkVersion>v4.7.2</TargetFrameworkVersion>
              </PropertyGroup>
            </Project>
        """.trimIndent())
        assertTrue(ProjectDetector.containsDotNetFrameworkProjects(dir))
    }

    @Test
    fun `containsDotNetFrameworkProjects false with net6`(@TempDir dir: Path) {
        dir.resolve("App.csproj").writeText("""
            <Project>
              <PropertyGroup>
                <TargetFramework>net6.0</TargetFramework>
              </PropertyGroup>
            </Project>
        """.trimIndent())
        assertFalse(ProjectDetector.containsDotNetFrameworkProjects(dir))
    }

    @Test
    fun `containsDotNetFrameworkProjects false when no csproj`(@TempDir dir: Path) {
        dir.resolve("App.java").writeText("class App {}")
        assertFalse(ProjectDetector.containsDotNetFrameworkProjects(dir))
    }

    @Test
    fun `readIdeaDir detects Java module`(@TempDir dir: Path) {
        dir.resolve(".idea").createDirectories()
        dir.resolve(".idea/project.iml").writeText("""<module type="JAVA_MODULE"/>""")
        val languages = ProjectDetector.readIdeaDir(dir)
        assertTrue(languages.contains("Java"))
    }

    @Test
    fun `readIdeaDir detects multiple languages`(@TempDir dir: Path) {
        dir.resolve(".idea").createDirectories()
        dir.resolve(".idea/project.iml").writeText("""<module type="JAVA_MODULE"><Go/></module>""")
        val languages = ProjectDetector.readIdeaDir(dir)
        assertTrue(languages.contains("Java"))
        assertTrue(languages.contains("Go"))
    }

    @Test
    fun `readIdeaDir returns empty when no idea dir`(@TempDir dir: Path) {
        assertEquals(emptyList(), ProjectDetector.readIdeaDir(dir))
    }
}
