package me.danielstiner.dumble.ui.about

/** A license whose full text ships in res/raw. */
enum class License(val displayName: String) {
    APACHE_2_0("Apache License 2.0"),
    APACHE_2_0_LLVM("Apache License 2.0 with LLVM Exception"),
    BSD_3_CLAUSE("BSD 3-Clause License"),
    MIT("MIT License"),
}

/**
 * One attributed component. [groupPrefix] matches a Maven group exactly or on a dot boundary,
 * so a single entry covers a whole family (all 38 androidx groups, for instance).
 *
 * Components with no Maven coordinate cannot be discovered from the dependency graph at all, so
 * they live outside [ATTRIBUTIONS] as their own vals — see [MUMBLE_SCHEMA], [LIBOPUS], [LIBCXX].
 * Keeping them out of the list is what makes an empty [groupPrefix] impossible there by
 * construction, rather than merely caught by a test. verifyShippedGroups covers the Maven side;
 * the submodule and native-library manifests cover this side.
 */
data class Attribution(
    val groupPrefix: String,
    val description: String,
    val license: License,
)

/**
 * Every Maven group resolved on releaseRuntimeClasspath. Licenses were read from each group's
 * declared POM <license> element, not assumed.
 *
 * Test-only dependencies (JUnit, BouncyCastle, Espresso) are not distributed and owe no
 * attribution. verifyAttribution fails the build if a shipped group is missing here.
 */
val ATTRIBUTIONS: List<Attribution> = listOf(
    Attribution(groupPrefix = "androidx", description = "AndroidX — Jetpack Compose, Lifecycle, Activity, DataStore, Navigation (libandroidx.graphics.path.so, libdatastore_shared_counter.so)", license = License.APACHE_2_0),
    Attribution(groupPrefix = "org.jetbrains.kotlin", description = "Kotlin standard library", license = License.APACHE_2_0),
    Attribution(groupPrefix = "org.jetbrains.kotlinx", description = "kotlinx.coroutines", license = License.APACHE_2_0),
    Attribution(groupPrefix = "org.jetbrains", description = "JetBrains Java annotations", license = License.APACHE_2_0),
    Attribution(groupPrefix = "com.google.dagger", description = "Dagger and Hilt", license = License.APACHE_2_0),
    Attribution(groupPrefix = "com.google.guava", description = "Guava ListenableFuture (transitive, via Dagger)", license = License.APACHE_2_0),
    Attribution(groupPrefix = "com.google.code.findbugs", description = "JSR-305 annotations", license = License.APACHE_2_0),
    Attribution(groupPrefix = "com.squareup.okio", description = "Okio", license = License.APACHE_2_0),
    Attribution(groupPrefix = "io.ktor", description = "Ktor utilities", license = License.APACHE_2_0),
    Attribution(groupPrefix = "javax.inject", description = "javax.inject", license = License.APACHE_2_0),
    Attribution(groupPrefix = "jakarta.inject", description = "jakarta.inject", license = License.APACHE_2_0),
    Attribution(groupPrefix = "org.jspecify", description = "JSpecify annotations", license = License.APACHE_2_0),
    Attribution(groupPrefix = "com.google.protobuf", description = "Protocol Buffers (javalite runtime)", license = License.BSD_3_CLAUSE),
    Attribution(groupPrefix = "org.slf4j", description = "SLF4J API", license = License.MIT),
    Attribution(groupPrefix = "com.google.oboe", description = "Oboe — Android audio streams (liboboe.so)", license = License.APACHE_2_0),
)

/**
 * The Mumble protocol schema is vendored into this repo rather than pulled from Maven, so it has
 * no group and cannot be discovered from the dependency graph — but the generated classes in the
 * APK are a derivative work and BSD-3-Clause clause 2 requires the notice to ship with them.
 */
val MUMBLE_SCHEMA = Attribution(
    groupPrefix = "",
    description = "Mumble protocol schema (Mumble.proto, MumbleUDP.proto, vendored from https://github.com/mumble-voip/mumble )",
    license = License.BSD_3_CLAUSE,
)

/**
 * libopus is built from source vendored as a git submodule, so like the Mumble schema it has no
 * Maven group and verifyShippedGroups cannot see it. The compiled library ships inside
 * libdumble.so, so BSD-3-Clause clause 2 applies to the binary we distribute.
 */
val LIBOPUS = Attribution(
    groupPrefix = "",
    description = "libopus (Xiph.Org Foundation, vendored from https://github.com/xiph/opus/ )",
    license = License.BSD_3_CLAUSE,
)

/**
 * The NDK toolchain packages libc++_shared.so because liboboe.so links against it. It arrives with
 * no Maven coordinate and no submodule, so neither the group diff nor the submodule diff can see
 * it — the native-library manifest is what does.
 */
val LIBCXX = Attribution(
    groupPrefix = "",
    description = "LLVM libc++ runtime (libc++_shared.so)",
    license = License.APACHE_2_0_LLVM,
)

/** The attribution covering [group], preferring the most specific prefix, or null if unattributed. */
fun attributionFor(group: String): Attribution? =
    ATTRIBUTIONS
        .filter { it.groupPrefix.isNotEmpty() && (group == it.groupPrefix || group.startsWith(it.groupPrefix + ".")) }
        .maxByOrNull { it.groupPrefix.length }

/**
 * Maven-discovered and vendored alike. One list so a new vendored component is added in one place
 * rather than in the display path and each manifest test separately.
 */
val ALL_ATTRIBUTIONS: List<Attribution> = ATTRIBUTIONS + MUMBLE_SCHEMA + LIBOPUS + LIBCXX

/** Components covered by [license], for display grouped by license. */
fun attributionsFor(license: License): List<Attribution> =
    ALL_ATTRIBUTIONS.filter { it.license == license }
