plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
    id("java-library")
    id("application")
    id("maven-publish")
    id("signing")
}

group = "ch.obermuhlner"
version = "0.1.0"

application {
    mainClass = "ch.obermuhlner.pdfmarkdown.MainKt"
}

repositories {
    mavenCentral()
}

dependencies {
    api("org.apache.pdfbox:pdfbox:3.0.3")
    implementation("com.github.ajalt.clikt:clikt:4.4.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
}

// Fat JAR for standalone CLI use
tasks.jar {
    manifest {
        attributes("Main-Class" to "ch.obermuhlner.pdfmarkdown.MainKt")
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(11)
}

// ─── Publishing ───────────────────────────────────────────────────────────────

java {
    withJavadocJar()
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            // Use the plain (non-fat) jar for the library artifact
            artifact(tasks.named("jar"))
            artifact(tasks.named("sourcesJar"))
            artifact(tasks.named("javadocJar"))

            artifactId = "pdf-markdown"

            pom {
                name = "pdf-markdown"
                description = "Deterministic PDF-to-Markdown converter using Apache PDFBox — no LLM required."
                url = "https://github.com/eobermuhlner/pdf-markdown"

                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/licenses/MIT"
                    }
                }
                developers {
                    developer {
                        id = "eobermuhlner"
                        name = "Eric Obermühlner"
                    }
                }
                scm {
                    connection = "scm:git:git://github.com/eobermuhlner/pdf-markdown.git"
                    developerConnection = "scm:git:ssh://github.com/eobermuhlner/pdf-markdown.git"
                    url = "https://github.com/eobermuhlner/pdf-markdown"
                }
            }
        }
    }

    repositories {
        // Local staging — upload this directory to OSSRH/Central Portal manually or via CI
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

signing {
    // Set ORG_GRADLE_PROJECT_signingKey and ORG_GRADLE_PROJECT_signingPassword
    // as environment variables (or in ~/.gradle/gradle.properties) to enable signing.
    val signingKey: String? by project
    val signingPassword: String? by project
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["mavenJava"])
    }
}
