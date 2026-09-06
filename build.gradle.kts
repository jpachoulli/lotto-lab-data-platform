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
