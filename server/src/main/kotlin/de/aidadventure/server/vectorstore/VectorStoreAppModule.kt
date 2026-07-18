package de.aidadventure.server.vectorstore

import org.apache.lucene.store.MMapDirectory
import org.koin.core.module.Module
import org.koin.dsl.onClose
import de.aidadventure.server.CommandLineArgumentStore
import de.aidadventure.server.EmbeddingService
import de.aidadventure.server.VectorStore
import java.nio.file.Paths
import java.time.Clock

fun Module.configureVectorStoreAppModule() {
    single {
        val cliArguments: CommandLineArgumentStore = get()
        val indexPath =
            cliArguments.getString(VectorStoreCommandLineOptions.INDEX_PATH)
                ?: VectorStoreCommandLineOptions.DEFAULT_INDEX_PATH
        LuceneIndexAccess { MMapDirectory(Paths.get(indexPath)) }
    } onClose { (it as? AutoCloseable)?.close() }
    single {
        val cliArguments: CommandLineArgumentStore = get()
        val pageSize =
            cliArguments.getInt(VectorStoreCommandLineOptions.PAGE_SIZE)
                ?: VectorStoreCommandLineOptions.DEFAULT_PAGE_SIZE
        val maxNotFinishedQueries =
            cliArguments.getInt(VectorStoreCommandLineOptions.MAX_NOT_FINISHED_QUERIES)
                ?: VectorStoreCommandLineOptions.DEFAULT_MAX_NOT_FINISHED_QUERIES
        QueryOnRegistry(pageSize = pageSize, maxNotFinishedQueries = maxNotFinishedQueries)
    }
    single {
        val cliArguments: CommandLineArgumentStore = get()
        val maxResults =
            cliArguments.getInt(VectorStoreCommandLineOptions.MAX_RESULTS)
                ?: VectorStoreCommandLineOptions.DEFAULT_MAX_RESULTS
        LuceneQueryPlanner(maxResults = maxResults)
    }
    single<VectorStore> {
        LuceneLocalVectorStore.create(
            indexAccess = get(),
            paginationRegistry = get(),
            queryPlanner = get(),
            clock = get<Clock>(),
            vectorSize = get<EmbeddingService>().dimension,
        )
    } onClose { (it as? AutoCloseable)?.close() }
}
