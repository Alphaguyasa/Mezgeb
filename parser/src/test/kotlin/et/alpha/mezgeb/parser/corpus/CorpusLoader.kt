package et.alpha.mezgeb.parser.corpus

import kotlinx.serialization.json.Json
import java.io.File

/**
 * Finds and parses the corpus from test resources.
 *
 * Layout, per `docs/04-SMS-SAMPLE-COLLECTION.md`:
 * ```
 * corpus/
 *   train/<sender>.json      tuned against
 *   holdout/<sender>.json    run once, at the end
 *   noise.json               must never produce a Success
 * ```
 * `noise.json` may sit at the corpus root or inside either split.
 */
object CorpusLoader {

    private val json = Json {
        ignoreUnknownKeys = false
        prettyPrint = true
    }

    /** Null when the corpus has not been collected yet. */
    fun corpusRoot(): File? {
        val url = CorpusLoader::class.java.classLoader?.getResource("corpus") ?: return null
        return File(url.toURI()).takeIf { it.isDirectory }
    }

    fun load(): List<LoadedCorpusFile> {
        val root = corpusRoot() ?: return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            .sortedBy { it.path }
            .map { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                val parsed = runCatching { json.decodeFromString<CorpusFile>(file.readText()) }
                    .getOrElse { cause ->
                        throw AssertionError("corpus/$relative is not a valid corpus file: ${cause.message}", cause)
                    }
                LoadedCorpusFile(split = splitOf(relative), path = relative, file = parsed)
            }
            .toList()
    }

    private fun splitOf(relativePath: String): CorpusSplit = when {
        relativePath.substringAfterLast('/').startsWith("noise") -> CorpusSplit.NOISE
        relativePath.startsWith("holdout/") -> CorpusSplit.HOLDOUT
        else -> CorpusSplit.TRAIN
    }
}
