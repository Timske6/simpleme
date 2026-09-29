
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

// mod version (git-tag versioning is disabled in gradle.properties)
project.extra["modVersion"] = "1.0.0+1.7.10"

// The automated in-game test is only packed into test builds: ./gradlew build -Pselftest
tasks.named<Jar>("jar") {
    if (!project.hasProperty("selftest")) {
        exclude("io/github/timske6/simpleme/selftest/**")
    }
}

// ./gradlew runClient25 -Pselftest runs the automated in-game test in the dev client
tasks.withType<JavaExec>().configureEach {
    if (project.hasProperty("selftest")) {
        systemProperty("simpleme.selftest", "true")
    }
}
