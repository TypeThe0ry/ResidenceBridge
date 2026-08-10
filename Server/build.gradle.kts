import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17

plugins {
    java
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
    id("com.gradleup.shadow") version "9.4.1"
}

repositories {
    mavenCentral()
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.momirealms.net/releases/")
    maven("https://repo.catnies.top/releases")
    maven("https://repo.papermc.io/repository/maven-public/")
    // maven("https://ptms.ink/repository/maven-releases/")  // TEMP: ptms.ink repo is down
}

dependencies {
    // TEMP: ptms.ink repo is down; using Paper API for compile verification
    compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")

    implementation(kotlin("stdlib"))
    implementation("com.zaxxer:HikariCP:4.0.3")
    implementation("com.mysql:mysql-connector-j:8.0.33")
    implementation("net.momirealms:sparrow-reflection:0.34")
    implementation("org.ow2.asm:asm:9.9.1")
    implementation("net.momirealms:sparrow-yaml:1.0.12")

    testImplementation(kotlin("test-junit"))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget.set(JVM_17)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveBaseName.set("ResidenceBridge")
    archiveClassifier.set("")
    mergeServiceFiles()
    relocate("com.zaxxer.hikari", "org.ewsk.residencebridge.lib.hikari")
    relocate("com.mysql", "org.ewsk.residencebridge.lib.mysql")
    relocate("net.momirealms.sparrow.reflection", "org.ewsk.residencebridge.lib.reflection")
    relocate("org.objectweb.asm", "org.ewsk.residencebridge.lib.asm")
    relocate("net.momirealms.sparrow.yaml", "org.ewsk.residencebridge.lib.yaml")
    relocate("org.snakeyaml", "org.ewsk.residencebridge.lib.snakeyaml")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
