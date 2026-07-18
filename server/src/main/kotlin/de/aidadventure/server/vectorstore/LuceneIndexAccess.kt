package de.aidadventure.server.vectorstore

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.DirectoryReader.openIfChanged
import org.apache.lucene.index.IndexFormatTooOldException
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.store.Directory
import java.time.Clock

class LuceneIndexAccess(
    directoryFactory: () -> Directory,
) : AutoCloseable {
    private val directory: Directory = directoryFactory()
    private val analyzer = StandardAnalyzer()
    private val config = IndexWriterConfig(analyzer)
    private val writer = createWriter()
    private var reader: DirectoryReader? = null
    private val lock = Mutex()

    suspend fun <T> read(block: suspend DirectoryReader.() -> T): T =
        lock.withLock {
            val activeReader = ensureReader()
            activeReader.block()
        }

    suspend fun <T> write(block: suspend IndexWriter.() -> T): T =
        lock.withLock {
            writer.block().also {
                writer.commit()
                reader?.let { existing ->
                    openIfChanged(existing)?.let { updated ->
                        existing.close()
                        reader = updated
                    }
                }
            }
        }

    fun transaction(
        clock: Clock,
        vectorSize: Int,
    ): LuceneWriteTransaction {
        val currentReader = runCatching { ensureReader() }.getOrNull()
        return LuceneWriteTransaction(currentReader, writer, clock, vectorSize)
    }

    private fun ensureReader(): DirectoryReader {
        val currentReader = reader ?: DirectoryReader.open(directory)
        reader = currentReader
        return currentReader
    }

    private fun createWriter(): IndexWriter =
        try {
            IndexWriter(directory, config)
        } catch (exception: IndexFormatTooOldException) {
            throw RuntimeException(
                "Existing local Lucene 9 index is incompatible with Lucene 10. Delete the local index directory, such as aiadventure_lucene_index, manually and restart to create an empty Lucene 10 index.",
                exception,
            )
        }

    override fun close() {
        reader?.close()
        writer.close()
        analyzer.close()
        directory.close()
    }
}
