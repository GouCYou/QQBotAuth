plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.6.1"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.org/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    compileOnly("fr.xephi:authme-core:6.0.0-R1") {
        isTransitive = false
    }

    implementation("com.google.code.gson:gson:2.14.0")
    implementation("org.xerial:sqlite-jdbc:3.53.2.1") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation("com.mysql:mysql-connector-j:9.4.0")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
    withSourcesJar()
}

tasks {
    withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-Xlint:deprecation")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching(listOf("plugin.yml", "velocity-plugin.json")) {
            expand(props)
        }
    }

    test {
        useJUnitPlatform()
    }

    shadowJar {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        archiveClassifier.set("")
        mergeServiceFiles()
        relocate("com.google.gson", "cn.cctstudio.qqbotauth.libs.gson")
        relocate("com.mysql", "cn.cctstudio.qqbotauth.libs.mysql")
        relocate("com.google.protobuf", "cn.cctstudio.qqbotauth.libs.protobuf")
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }

    jar {
        archiveClassifier.set("plain")
    }

    build {
        dependsOn(shadowJar)
    }
}
