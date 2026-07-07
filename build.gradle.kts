import net.minecrell.pluginyml.bukkit.BukkitPluginDescription

plugins {
    id("java")
    id("com.gradleup.shadow") version "9.4.1"
    id("de.eldoria.plugin-yml.bukkit") version "0.7.1"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
    maven("https://repo.momirealms.net/releases/")
    maven("https://repo.momirealms.net/snapshots/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${rootProject.findProperty("paper_version")}-R0.1-SNAPSHOT")
    compileOnly("net.momirealms:craft-engine-core:${rootProject.findProperty("craftengine_version")}")
    compileOnly("com.google.code.gson:gson:${rootProject.findProperty("gson_version")}")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
    dependsOn(tasks.clean)
}

bukkit {
    main = "cn.gtemc.craftengine.CraftEngineHosts"
    version = rootProject.findProperty("project_version") as String
    name = "CraftEngineHosts"
    apiVersion = "1.20"
    load = BukkitPluginDescription.PluginLoadOrder.STARTUP
    author = "CraftEngine Community"
    website = "https://github.com/CraftEngine-Community/craft-engine-hosts"
    depend = listOf("CraftEngine")
    foliaSupported = true
}

artifacts {
    archives(tasks.shadowJar)
}

tasks {
    shadowJar {
        archiveFileName = "${rootProject.name}-${rootProject.findProperty("project_version")}.jar"
        destinationDirectory.set(file("$rootDir/target"))
    }
}