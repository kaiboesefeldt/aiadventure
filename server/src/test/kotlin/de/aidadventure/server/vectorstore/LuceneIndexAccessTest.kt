package de.aidadventure.server.vectorstore

import org.apache.lucene.store.MMapDirectory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.isDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class LuceneIndexAccessTest {
    @Test
    fun `creating access for lucene 9 index fails with actionable message`() {
        val fixtureDirectory =
            createTempDirectory("lucene9-index-").also {
                copyResourceDirectory("de/aidadventure/server/vectorstore/lucene9-index", it)
            }

        try {
            val exception =
                assertFailsWith<RuntimeException> {
                    LuceneIndexAccess { MMapDirectory(fixtureDirectory) }
                }

            assertContains(exception.message.orEmpty(), "Lucene 9")
            assertContains(exception.message.orEmpty(), "Delete")
            assertContains(exception.message.orEmpty(), "aiadventure_lucene_index")
        } finally {
            fixtureDirectory.toFile().deleteRecursively()
        }
    }

    private fun copyResourceDirectory(
        resourcePath: String,
        targetDirectory: Path,
    ) {
        val sourceDirectory = Path.of(checkNotNull(javaClass.classLoader.getResource(resourcePath)).toURI())
        Files.walk(sourceDirectory).use { paths ->
            paths.forEach { sourcePath ->
                val relativePath = sourceDirectory.relativize(sourcePath)
                val targetPath = targetDirectory.resolve(relativePath.toString())
                if (sourcePath.isDirectory()) {
                    Files.createDirectories(targetPath)
                } else {
                    Files.createDirectories(targetPath.parent)
                    Files.copy(sourcePath, targetPath)
                }
            }
        }
    }
}
