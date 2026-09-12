import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    id("java-library")
    id("maven-publish")
    id("mod-plugin")
}

repositories {
    mavenLocal()
    mavenCentral()
    gradlePluginPortal()
}

group = modMavenGroup
version = fullProjectVersion

base {
    archivesName.set("$modArchivesBaseName-versionpack")
}

val fabricSubprojects = rootProject.subprojects.filter { it.name != "fabricWrapper" }

fabricSubprojects.forEach {
    evaluationDependsOn(":${it.name}")
}

val subArchives = fabricSubprojects.map { sub ->
    sub.tasks.named<AbstractArchiveTask>(
        if (sub.plugins.hasPlugin("net.fabricmc.fabric-loom-remap")) "remapJar" else "jar"
    )
}

tasks {
    val collectSubModules by registering {
        description = "Collect all submodules into a single jar"
        val destDir = layout.buildDirectory.dir("tmp/submods/META-INF/jars")

        outputs.upToDateWhen { false }

        dependsOn(fabricSubprojects.map { it.tasks.named("build") })

        doLast {
            val destDirFile = destDir.get().asFile
            destDirFile.deleteRecursively()
            destDirFile.mkdirs()

            subArchives.forEach { archive ->
                val jar = archive.get().archiveFile.get().asFile
                check(jar.isFile) { "Missing submodule artifact: $jar" }
                jar.copyTo(destDirFile.resolve(jar.name), overwrite = true)
                println("Copied: ${jar.name}")
            }
        }
    }

    named<Jar>("jar") {
        dependsOn(collectSubModules)
        dependsOn("processResources")

        from(rootProject.file("LICENSE"))
        from(layout.buildDirectory.dir("tmp/submods"))
    }

    named<ProcessResources>("processResources") {
        dependsOn(collectSubModules)

        doLast {
            val jarsDir = layout.buildDirectory.dir("tmp/submods/META-INF/jars").get().asFile
            val jars = jarsDir.listFiles()
                ?.filter { it.extension == "jar" }
                ?.map { mapOf("file" to "META-INF/jars/${it.name}") }
                ?: emptyList()

            val minecraftVersions = fabricSubprojects.mapNotNull { sub ->
                try {
                    sub.property("minecraft_dependency") as? String
                } catch (e: Exception) {
                    null
                }
            }.distinct()

            val jsonFile = layout.buildDirectory.file("resources/main/fabric.mod.json").get().asFile
            if (jsonFile.exists()) {
                @Suppress("UNCHECKED_CAST")
                val json = JsonSlurper().parse(jsonFile) as MutableMap<String, Any>
                json["jars"] = jars
                @Suppress("UNCHECKED_CAST")
                (json["depends"] as? MutableMap<String, Any>)?.put("minecraft", minecraftVersions)
                jsonFile.writeText(JsonBuilder(json).toPrettyString())

                println("JAR files: ${jars.size}, Minecraft: $minecraftVersions")
            }
        }
    }
}
