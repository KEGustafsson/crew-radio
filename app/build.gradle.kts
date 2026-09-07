import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

plugins {
    id("com.android.application")      // brings the Kotlin compiler with it (AGP built-in Kotlin)
}

/** Output of a git command run in the repository, or empty when git is unavailable (a source download, say). */
fun git(vararg args: String): String = try {
    providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
} catch (_: Exception) { "" }

// Versions come from git, so every merge is a new version without anyone editing a number:
// versionCode is the commit count on the current branch (monotonic on main), versionName is
// 1.<count> and the short commit hash is shown on the Status screen. README: every phone on the
// crew must run the same build, and this is how a phone tells which one it has.
val commitCount = git("rev-list", "--count", "HEAD").toIntOrNull() ?: 1
val commitSha = git("rev-parse", "--short", "HEAD").ifEmpty { "local" }
val dirty = git("status", "--porcelain").isNotEmpty()
val appVersion = "1.$commitCount"

// Release signing: a keystore named by CREWRADIO_KEYSTORE (CI decodes it from a secret) or
// app/release.keystore locally, with its passwords from the environment. Without one the
// release build is signed with the debug key so `assembleRelease` always works; the README
// explains why a phone then cannot upgrade between a CI build and a local one.
//
// A keystore named explicitly but unusable is a mistake to stop on, not a reason to fall back:
// a typo in the path or a forgotten password would otherwise sign with the debug key without a
// word (and skip the shallow-clone guard below). CI's certificate check would catch it there;
// a local build has nothing else that would.
val keystoreEnv: String? = System.getenv("CREWRADIO_KEYSTORE")
val keystoreFile = file(keystoreEnv ?: "release.keystore")
val keystorePassword: String? = System.getenv("CREWRADIO_KEYSTORE_PASSWORD")
if (keystoreEnv != null) {
    if (!keystoreFile.isFile) throw GradleException("CREWRADIO_KEYSTORE names $keystoreFile, which is not a file")
    if (keystorePassword.isNullOrEmpty()) throw GradleException("CREWRADIO_KEYSTORE is set but CREWRADIO_KEYSTORE_PASSWORD is empty")
}
val hasReleaseKey = keystoreFile.isFile && !keystorePassword.isNullOrEmpty()
// One line naming the key, so nobody reads "BUILD SUCCESSFUL" as "release-signed".
logger.lifecycle(
    when {
        hasReleaseKey -> "Release signing: crew release key $keystoreFile"
        keystoreFile.isFile -> "Release signing: debug key ($keystoreFile found, but CREWRADIO_KEYSTORE_PASSWORD is not set)"
        else -> "Release signing: debug key (no release keystore; see README, Releases)"
    }
)

// A shallow clone counts fewer commits, so a release-signed build from one could carry a lower
// versionCode than the last Release and be refused by the phone as a downgrade. Refuse first.
if (hasReleaseKey && git("rev-parse", "--is-shallow-repository") == "true") {
    throw GradleException("Release-signed builds need the full git history: run `git fetch --unshallow` first")
}

android {
    namespace = "fi.crewradio"
    compileSdk = 37
    defaultConfig {
        applicationId = "fi.crewradio"
        minSdk = 29
        targetSdk = 36
        versionCode = commitCount
        versionName = appVersion
        buildConfigField("String", "GIT_SHA", "\"$commitSha${if (dirty) "+" else ""}\"")
    }
    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = System.getenv("CREWRADIO_KEY_ALIAS") ?: "crewradio"
                keyPassword = System.getenv("CREWRADIO_KEY_PASSWORD") ?: keystorePassword
            }
        }
    }
    buildTypes {
        release {
            // R8 shrinks and optimises the release build: unused AndroidX code and resources leave
            // the APK, and with them the surface a phone carries for nothing. Names are kept
            // (proguard-rules.pro) so a crash trace reads without a mapping file.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17      // built-in Kotlin takes its jvmTarget from this
    }
    lint {
        // `gradlew lintRelease` is a CI gate: an error fails the build, warnings are reported
        // (app/build/reports/lint-results-release.html) and fixed as they come.
        abortOnError = true
        warningsAsErrors = false
    }
}

// The build compiles and tests on a JDK 17 wherever it runs: Gradle looks for one on the machine
// (JAVA_HOME, the usual install locations, or -Porg.gradle.java.installations.paths=...) and
// refuses to download one (gradle.properties), so the toolchain is what the machine provides,
// not a silent download from wherever the current foojay index points.
java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

/** Hex SHA-256 of a file, for the SBOM. */
fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

/**
 * `gradlew sbom` writes app/build/reports/bom.json: a CycloneDX 1.5 software bill of materials
 * of everything on the release runtime classpath, attached to every Release. Written here
 * rather than by a plugin so the build itself pulls in nothing extra. Each component carries the
 * SHA-256 of its artifact(s), so a reader can check the jar or aar against Maven, and the
 * `dependencies` graph says who pulled in what; the serial number is new for every run.
 */
tasks.register("sbom") {
    val out = layout.buildDirectory.file("reports/bom.json")
    outputs.file(out)
    outputs.upToDateWhen { false }         // timestamped and classpath-dependent: never reuse a stale one
    doLast {
        val cfg = configurations.getByName("releaseRuntimeClasspath")
        val appRef = "pkg:generic/CrewRadio@$appVersion"
        fun purl(id: ComponentIdentifier): String? =
            (id as? ModuleComponentIdentifier)?.let { "pkg:maven/${it.group}/${it.module}@${it.version}" }

        // The graph from the resolution result: every module component and what it depends on.
        val dependsOn = sortedMapOf<String, List<String>>()
        val modules = sortedMapOf<String, ModuleComponentIdentifier>()
        fun walk(c: ResolvedComponentResult, ref: String) {
            if (dependsOn.containsKey(ref)) return
            val next = c.dependencies.filterIsInstance<ResolvedDependencyResult>()
                .mapNotNull { d -> purl(d.selected.id)?.let { it to d.selected } }
            dependsOn[ref] = next.map { it.first }.distinct().sorted()
            next.forEach { (r, sel) -> modules[r] = sel.id as ModuleComponentIdentifier; walk(sel, r) }
        }
        walk(cfg.incoming.resolutionResult.rootComponent.get(), appRef)

        // The files behind them, hashed. A module without an artifact (a BOM, a Kotlin
        // multiplatform umbrella pointing at its -jvm variant) is listed without hashes.
        val files = cfg.resolvedConfiguration.resolvedArtifacts
            .groupBy({ with(it.moduleVersion.id) { "pkg:maven/$group/$name@$version" } }, { it.file })
        val comps = modules.entries.joinToString(",\n") { (ref, id) ->
            val hashes = (files[ref] ?: emptyList()).distinct().sortedBy { it.name }
                .joinToString(", ") { """{"alg": "SHA-256", "content": "${sha256(it)}"}""" }
            """    {"type": "library", "group": "${id.group}", "name": "${id.module}", "version": "${id.version}", "purl": "$ref", "bom-ref": "$ref", "hashes": [$hashes]}"""
        }
        val deps = dependsOn.entries.joinToString(",\n") { (ref, on) ->
            """    {"ref": "$ref", "dependsOn": [${on.joinToString(", ") { "\"$it\"" }}]}"""
        }
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(
            """{
  "bomFormat": "CycloneDX",
  "specVersion": "1.5",
  "serialNumber": "urn:uuid:${UUID.randomUUID()}",
  "version": 1,
  "metadata": {
    "timestamp": "${Instant.now()}",
    "tools": {
      "components": [
        {"type": "application", "name": "Gradle", "version": "${gradle.gradleVersion}"},
        {"type": "application", "name": "crew-radio sbom task (app/build.gradle.kts)", "version": "$appVersion"}
      ]
    },
    "component": {"type": "application", "name": "CrewRadio", "version": "$appVersion", "bom-ref": "$appRef", "licenses": [{"license": {"id": "EUPL-1.2"}}]}
  },
  "components": [
$comps
  ],
  "dependencies": [
$deps
  ]
}
"""
        )
    }
}

/**
 * `gradlew printVersion` writes the versionName to app/build/version.txt (and prints it), for the
 * release workflow to name the APK and the tag. The file, not Gradle's last stdout line, is what
 * the workflow reads: anything else printed during the build would otherwise become the tag.
 */
tasks.register("printVersion") {
    val out = layout.buildDirectory.file("version.txt")
    outputs.file(out)
    outputs.upToDateWhen { false }
    doLast {
        out.get().asFile.apply { parentFile.mkdirs() }.writeText("$appVersion\n")
        println(appVersion)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    testImplementation("junit:junit:4.13.2")
}
