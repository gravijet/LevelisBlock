plugins {
    java
}

group = "net.gravijet"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper 26.2 ("Chaos Cubed"). Mojang switched to year.drop versioning in 2026,
    // so this is what used to be called "1.26.2".
    compileOnly("io.papermc.paper:paper-api:26.2.build.92-stable")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    archiveFileName.set("LevelBlock-${project.version}.jar")
}

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-Xlint:deprecation,removal") }
