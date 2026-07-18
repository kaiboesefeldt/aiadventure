package de.aidadventure.server.embedding

import org.koin.core.module.Module
import org.koin.dsl.onClose
import de.aidadventure.server.EmbeddingService

fun Module.configureEmbeddingAppModule() {
    single<EmbeddingService> { OnnxLocalEmbeddingService.create() } onClose { (it as? AutoCloseable)?.close() }
}
