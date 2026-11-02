#!/usr/bin/env python3
"""Run PR818's real Kotlin subtitle core/Preferences DataStore tests without Android SDK.

Uses an isolated Kotlin/JVM Gradle project (the app's Kotlin version, real
serialization, coroutines, DataStore and MockK). Production pure sources and tests
are copied verbatim. The Android-only DataStore DI factory is omitted, Subtitle
is extracted verbatim from Models.kt, and Android logging / translation transport
and the full settings-screen state model are replaced by explicit test-only boundaries. No provider request is possible.
This is NOT an Android/Compose/Media3 compilation, Robolectric player integration
run, R8 validation, or a replacement for the TV release smoke test.
Dependencies are downloaded by Gradle into its normal user cache on the first run.
"""
from pathlib import Path
import os
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/com/arflix/tv"
TEST = ROOT / "app/src/test/kotlin/com/arflix/tv/ui/screens/player/subtitles/bilingual"
PURE = [
    "SubtitleLanguage", "BilingualSubtitleSettings", "BilingualStrings", "LegacySubtitleAppearance",
    "BilingualAligner", "BilingualCueBus", "BilingualLayout", "BilingualCuePublisher",
    "EmbeddedSubtitleStore", "EmbeddedTimingAligner", "DualTrackSecondarySource",
    "SmartBilingualPlanner", "SmartBilingualSession",
]
TESTS = [
    "SubtitleLanguageTest", "BilingualSettingsCodecTest", "LegacySubtitleAppearanceTest",
    "BilingualAlignerTest", "BilingualComposerTest", "BilingualCueBusTest", "BilingualLayoutTest",
    "BilingualSettingsStoreTest", "LegacySubtitleStoreIntegrationTest", "BilingualTranslationManagerTest",
    "EmbeddedTimingAlignerTest", "SmartBilingualFlowTest", "SmartBilingualPlannerTest",
    "SmartBilingualSessionTest", "TranslationLifecycleTest",
]


def put(base, name, text):
    target = base / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text)


with tempfile.TemporaryDirectory(prefix="arvio-pr818-jvm-") as directory:
    project = Path(directory)
    main = project / "src/main/kotlin"
    tests = project / "src/test/kotlin"
    for name in PURE:
        put(main, name + ".kt", (MAIN / "ui/screens/player/subtitles/bilingual" / (name + ".kt")).read_text())
    for path in ["ui/screens/player/SubtitleFontOption.kt", "ui/screens/player/subtitles/SubtitleAiModel.kt",
                 "ui/screens/player/subtitles/SubtitleTranslationManager.kt", "ui/screens/player/subtitles/TranslationDiskCache.kt"]:
        put(main, Path(path).name, (MAIN / path).read_text())
    for name in TESTS:
        path = TEST / (name + ".kt")
        if path.is_file():
            put(tests, path.name, path.read_text())

    models = (MAIN / "data/model/Models.kt").read_text()
    subtitle = re.search(r"^data class Subtitle\([\s\S]*?^\) : Serializable", models, re.MULTILINE)
    if not subtitle:
        raise SystemExit("Cannot extract production Subtitle declaration; run the Android test task.")
    put(main, "Subtitle.kt", "package com.arflix.tv.data.model\nimport java.io.Serializable\n" + subtitle.group() + "\n")

    # The real preference writer needs only one SettingsUiState field in its standalone tests.
    put(main, "SettingsPreferenceWriter.kt", (MAIN / "ui/screens/settings/SettingsPreferenceWriter.kt").read_text())
    put(main, "SettingsUiState.kt", '''package com.arflix.tv.ui.screens.settings
// Test boundary for the full Android settings screen model, not a SettingsViewModel integration test.
data class SettingsUiState(val subtitleSize: String = "Medium")
''')
    put(tests, "SettingsPreferenceWriterTest.kt", (ROOT / "app/src/test/kotlin/com/arflix/tv/ui/screens/settings/SettingsPreferenceWriterTest.kt").read_text())

    # Keep the actual DataStore implementation; remove only Android/Hilt construction at its edge.
    store = (MAIN / "ui/screens/player/subtitles/bilingual/BilingualSubtitleSettingsStore.kt").read_text()
    factory = re.compile(r"        fun from\(context: Context\): BilingualSubtitleSettingsStore =[\s\S]*?            \)\.bilingualSubtitleSettingsStore\(\)\n")
    store, replaced = factory.subn("", store)
    if replaced != 1:
        raise SystemExit("DataStore Android factory changed; update the headless boundary deliberately.")
    store = store.split("\n@EntryPoint")[0]
    store = "\n".join(line for line in store.splitlines() if not line.startswith((
        "import android.", "import dagger.", "import javax.inject.",
        "import com.arflix.tv.data.repository.", "import com.arflix.tv.util.",
    )))
    put(main, "BilingualSubtitleSettingsStore.kt", store + "\n")
    put(main, "AndroidLog.kt", '''package android.util
object Log { @JvmStatic fun w(tag: String, message: String): Int = 0 }
''')
    put(main, "CustomAiConfig.kt", '''package com.arflix.tv.ui.screens.player.subtitles.ai
class CustomAiConfig
''')
    # Signatures match the production network boundary; every test mocks it. Accidental calls fail.
    service = (MAIN / "ui/screens/player/subtitles/SubtitleTranslationService.kt").read_text()
    result = re.search(r"data class TranslationResult\([\s\S]*?^\)", service, re.MULTILINE).group()
    put(main, "SubtitleTranslationService.kt", '''package com.arflix.tv.ui.screens.player.subtitles
''' + result + '''
class SubtitleTranslationService(
    apiKeyProvider: () -> String = { "" },
    modelProvider: () -> SubtitleAiModel = { SubtitleAiModel.GROQ_LLAMA_70B },
    customConfigProvider: () -> com.arflix.tv.ui.screens.player.subtitles.ai.CustomAiConfig? = { null },
) {
    suspend fun translateBatch(lines: List<String>, targetLanguage: String,
        context: List<Pair<String, String>> = emptyList(), contentTitle: String? = null): TranslationResult =
        error("Headless regression: network boundary must be mocked")
}
''')
    kotlin_version = re.search(r'id\("org.jetbrains.kotlin.android"\) version "([^"]+)"', (ROOT / "build.gradle.kts").read_text()).group(1)
    put(project, "settings.gradle.kts", '''pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
rootProject.name = "pr818-headless-regression"
''')
    put(project, "build.gradle.kts", f'''plugins {{
    kotlin("jvm") version "{kotlin_version}"
    kotlin("plugin.serialization") version "{kotlin_version}"
}}
repositories {{
    mavenCentral()
    // Restrict Google lookups to AndroidX; no SDK or Android Gradle plugin is used.
    maven("https://dl.google.com/dl/android/maven2/") {{ content {{ includeGroupByRegex("androidx\\\\..*") }} }}
}}
dependencies {{
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("androidx.datastore:datastore-preferences-core:1.0.0")
    implementation("org.json:json:20231013")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.mockk:mockk:1.13.8")
}}
kotlin {{ jvmToolchain(17) }}
tasks.test {{
    useJUnit()
    maxHeapSize = "384m"
    jvmArgs("-XX:ActiveProcessorCount=2")
    testLogging {{ events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }}
}}
''')
    put(project, "gradle.properties", '''org.gradle.jvmargs=-Xmx768m -XX:ActiveProcessorCount=2
kotlin.compiler.execution.strategy=in-process
org.gradle.workers.max=2
''')
    print("Headless JVM core tests only; Android/Compose/Media3 and provider transport are NOT covered.", flush=True)
    command = ["bash", str(ROOT / "gradlew"), "-p", str(project), "test", "--no-daemon", "--console=plain", *sys.argv[1:]]
    result = subprocess.run(command, cwd=ROOT)
    total = failed = errors = skipped = 0
    for report in sorted((project / "build/test-results/test").glob("TEST-*.xml")):
        suite = ET.parse(report).getroot()
        count = int(suite.attrib.get("tests", 0))
        f = int(suite.attrib.get("failures", 0))
        e = int(suite.attrib.get("errors", 0))
        s = int(suite.attrib.get("skipped", 0))
        total += count; failed += f; errors += e; skipped += s
        print(f"{suite.attrib.get('name')}: {count} tests, {f + e} failed, {s} skipped")
    print(f"HEADLESS TOTAL: tests={total}, failures={failed}, errors={errors}, skipped={skipped}", flush=True)
    sys.exit(result.returncode)
