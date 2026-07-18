package de.aidadventure.server.vectorstore

import kotlinx.cli.ArgType
import kotlinx.cli.ExperimentalCli
import kotlinx.cli.Subcommand
import kotlinx.cli.default
import de.aidadventure.server.CommandLineOptions

@OptIn(ExperimentalCli::class)
class VectorStoreCommandLineOptions(
    command: Subcommand,
) : CommandLineOptions {
    val indexPath by
        command
            .option(
                ArgType.String,
                fullName = INDEX_PATH,
                description = "Path for the Lucene vector store index directory",
            ).default(DEFAULT_INDEX_PATH)
    val pageSize by
        command
            .option(
                ArgType.Int,
                fullName = PAGE_SIZE,
                description = "Page size for vector store query pagination",
            ).default(DEFAULT_PAGE_SIZE)
    val maxNotFinishedQueries by
        command
            .option(
                ArgType.Int,
                fullName = MAX_NOT_FINISHED_QUERIES,
                description = "Maximum in-flight vector store queries",
            ).default(DEFAULT_MAX_NOT_FINISHED_QUERIES)
    val maxResults by
        command
            .option(
                ArgType.Int,
                fullName = MAX_RESULTS,
                description = "Maximum number of vector store results per query",
            ).default(DEFAULT_MAX_RESULTS)

    override fun toArgumentValues(): Map<String, Any?> =
        mapOf(
            INDEX_PATH to indexPath,
            PAGE_SIZE to pageSize,
            MAX_NOT_FINISHED_QUERIES to maxNotFinishedQueries,
            MAX_RESULTS to maxResults,
        )

    companion object {
        const val INDEX_PATH = "vectorstore-index-path"
        const val PAGE_SIZE = "vectorstore-page-size"
        const val MAX_NOT_FINISHED_QUERIES = "vectorstore-max-not-finished-queries"
        const val MAX_RESULTS = "vectorstore-max-results"
        const val DEFAULT_INDEX_PATH = "aiadventure_lucene_index"
        const val DEFAULT_PAGE_SIZE = 10
        const val DEFAULT_MAX_NOT_FINISHED_QUERIES = 10000
        const val DEFAULT_MAX_RESULTS = 1000
    }
}
