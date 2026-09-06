plugins {
    kotlin("jvm") version "2.0.21"
}

group = "com.jeremybernsdorff.lottolab"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    testImplementation(kotlin("test"))
}

tasks.register<JavaExec>("acquireCurrentDraw") {
    group = "acquisition"
    description = "Acquire and verify one current official draw"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.jeremybernsdorff.lottolab.dataplatform.acquisition.current.CurrentDrawAcquisitionCli")
    val game = providers.gradleProperty("game")
    val drawDate = providers.gradleProperty("drawDate")
    args(game.orNull, drawDate.orNull)
}

tasks.register<JavaExec>("buildCoreThreeSnapshotCandidate") {
    group = "verification"
    description = "Builds the deterministic B2 core-three snapshot candidate"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.jeremybernsdorff.lottolab.dataplatform.snapshot.current.CoreThreeSnapshotCandidateCli")
    val throughDate = providers.gradleProperty("throughDate").orElse("2026-09-02")
    args(throughDate.get())
}

tasks.register<JavaExec>("catchUpCoreThree") {
    group = "acquisition"
    description = "Fills all mature core-three draw gaps into the permanent verified ledger"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.jeremybernsdorff.lottolab.dataplatform.snapshot.current.CoreThreeCatchUpCli")
    val descriptor = providers.gradleProperty("descriptor").orElse("data/distribution/core_three/latest.json")
    val ledgerRoot = providers.gradleProperty("ledgerRoot").orElse("data/current/core_three/verified")
    val nowUtc = providers.gradleProperty("nowUtc")
    val output = providers.gradleProperty("output").orElse("build/core-three-catchup-summary.json")
    doFirst { require(nowUtc.isPresent) { "-PnowUtc is required" } }
    args(descriptor.get(), ledgerRoot.get(), nowUtc.get(), output.get())
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

tasks.register<JavaExec>("buildCoreThreePublishableSnapshot") {
    group = "publication"
    description =
        "Builds a PUBLISHABLE core-three snapshot from a strict catch-up summary and the permanent verified ledger"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(
        "com.jeremybernsdorff.lottolab.dataplatform.snapshot.current." +
            "CoreThreePublishableSnapshotPreparationCli"
    )

    val descriptor =
        providers.gradleProperty(
            "descriptor"
        ).orElse(
            "data/distribution/core_three/latest.json"
        )

    val catchUpSummary =
        providers.gradleProperty(
            "catchUpSummary"
        ).orElse(
            "build/core-three-catchup-summary.json"
        )

    val ledgerRoot =
        providers.gradleProperty(
            "ledgerRoot"
        ).orElse(
            "data/current/core_three/verified"
        )

    val sourceRepositoryCommit =
        providers.gradleProperty(
            "sourceRepositoryCommit"
        )

    val createdAtUtc =
        providers.gradleProperty(
            "createdAtUtc"
        )

    val outputRoot =
        providers.gradleProperty(
            "outputRoot"
        ).orElse(
            "build/core-three-publishable"
        )

    doFirst {
        require(
            sourceRepositoryCommit.isPresent
        ) {
            "-PsourceRepositoryCommit is required"
        }

        require(
            createdAtUtc.isPresent
        ) {
            "-PcreatedAtUtc is required"
        }

        setArgs(
            listOf(
                descriptor.get(),
                catchUpSummary.get(),
                ledgerRoot.get(),
                sourceRepositoryCommit.get(),
                createdAtUtc.get(),
                outputRoot.get()
            )
        )
    }
}

tasks.register<JavaExec>("prepareCoreThreeReleaseDescriptor") {
    group = "publication"
    description =
        "Verifies a PUBLISHABLE core-three artifact and prepares its release descriptor without remote mutation"

    classpath =
        sourceSets["main"].runtimeClasspath

    mainClass.set(
        "com.jeremybernsdorff.lottolab.dataplatform.snapshot.current." +
            "CoreThreeReleaseDescriptorPreparationCli"
    )

    val artifactDirectory =
        providers.gradleProperty(
            "artifactDirectory"
        )

    val publishedAtUtc =
        providers.gradleProperty(
            "publishedAtUtc"
        )

    val outputDescriptor =
        providers.gradleProperty(
            "outputDescriptor"
        ).orElse(
            "build/core-three-release-descriptor/descriptor.json"
        )

    doFirst {
        require(
            artifactDirectory.isPresent
        ) {
            "-PartifactDirectory is required"
        }

        require(
            publishedAtUtc.isPresent
        ) {
            "-PpublishedAtUtc is required"
        }

        setArgs(
            listOf(
                artifactDirectory.get(),
                publishedAtUtc.get(),
                outputDescriptor.get()
            )
        )
    }
}
