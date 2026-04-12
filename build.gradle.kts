plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
    id("java-library")
    id("application")
    id("maven-publish")
    id("signing")
}

group = "ch.obermuhlner"
version = (project.findProperty("version") as String?) ?: "0.1.0-SNAPSHOT"

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
    if (project.hasProperty("eval.full")) {
        systemProperty("eval.full", project.property("eval.full")!!)
    }
    if (project.hasProperty("eval.dir")) {
        systemProperty("eval.dir", project.property("eval.dir")!!)
    }
}

kotlin {
    jvmToolchain(11)
}

// ─── Eval data ────────────────────────────────────────────────────────────────

tasks.register("downloadEvalData") {
    group = "verification"
    description = "Downloads the full synthetic eval dataset (001 dir) from pdf-markdown-testdata."
    doLast {
        val dest = file("data/pdf/synthetic/data/001")
        if (dest.exists() && (dest.listFiles()?.isNotEmpty() == true)) {
            println("Eval data already present at ${dest.path} (${dest.listFiles()!!.size} files)")
            return@doLast
        }
        dest.mkdirs()
        val url = "https://github.com/eobermuhlner/pdf-markdown-testdata/archive/refs/heads/main.zip"
        println("Downloading eval data from $url ...")
        val zip = file("build/eval-data.zip")
        zip.parentFile.mkdirs()
        uri(url).toURL().openStream().use { input ->
            zip.outputStream().use { out -> input.copyTo(out) }
        }
        println("Extracting to ${dest.path} ...")
        copy {
            from(zipTree(zip)) {
                include("pdf-markdown-testdata-main/data/001/**")
                eachFile {
                    relativePath = RelativePath(true, *relativePath.segments.drop(2).toTypedArray())
                }
                includeEmptyDirs = false
            }
            into(dest)
        }
        zip.delete()
        println("Done. ${dest.listFiles()?.size ?: 0} files extracted to ${dest.path}")
    }
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
