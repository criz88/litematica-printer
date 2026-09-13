import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.*

/** Shared setup; keep Loom's remapped and non-remapped dependency configurations in their scripts. */
fun Project.configureFabricRepositories(remapped: Boolean) {
    repositories {
        mavenLocal()
        maven("https://maven.fabricmc.net") { name = "FabricMC" }
        maven("https://maven.fallenbreath.me/releases") { name = "FallenBreath" }
        maven("https://api.modrinth.com/maven") { name = "Modrinth" }
        maven("https://www.cursemaven.com") { name = "CurseMaven" }
        maven("https://maven.terraformersmc.com/releases") { name = "TerraformersMC" }
        maven("https://maven.nucleoid.xyz") { name = "Nucleoid" }
        maven("https://masa.dy.fi/maven") { name = "Masa" }
        maven("https://masa.dy.fi/maven/sakura-ryoko") { name = "SakuraRyoko" }
        maven("https://maven.kyrptonaught.dev") { name = "Kyrptonaught" }
        maven("https://jitpack.io") { name = "Jitpack" }
        if (remapped) {
            maven("https://mvnrepository.com/artifact/com.belerweb/pinyin4j") {
                name = "Pinyin4j"
                content { includeGroupAndSubgroups("com.belerweb") }
            }
        }
        maven("https://maven.pkg.github.com/BiliXWhite/remote-inventory-next") {
            name = "GitHub"
            credentials {
                username = System.getenv("GH_USERNAME") ?: ""
                password = System.getenv("GH_TOKEN") ?: ""
            }
        }
    }
}

fun Project.configureFabricResolution() {
    configurations.all {
        resolutionStrategy {
            force("net.fabricmc:fabric-loader:$fabricLoaderVersion")
            force("com.terraformersmc:modmenu:${prop("modmenu")}")
            force("maven.modrinth:malilib:${prop("malilib_dependency")}")
            force("maven.modrinth:litematica:${prop("litematica_dependency")}")
            force("maven.modrinth:tweakeroo:${prop("tweakeroo_dependency")}")
        }
    }
}

fun Project.configureFabricPublication() {
    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("mavenJava") {
                from(components["java"])
                artifactId = modId
                version = modVersion
            }
        }
        repositories {
            mavenLocal()
            maven { url = uri("$rootDir/publish") }
        }
    }
}
