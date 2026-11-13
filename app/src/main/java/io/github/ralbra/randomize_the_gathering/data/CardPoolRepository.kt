package io.github.ralbra.randomize_the_gathering.data

import io.github.ralbra.randomize_the_gathering.model.CardPool

/** Holds the custom format card pool once it is loaded or downloaded. */
class CardPoolRepository(private val bootstrapper: CardPoolBootstrapper) {
    @Volatile
    var pool: CardPool? = null
        private set

    fun hasStoredPool(): Boolean = bootstrapper.hasStoredPool()

    /** Loads the stored pool if there is one. Blocking. */
    fun loadStored(): CardPool? {
        pool?.let { return it }
        return bootstrapper.loadStored()?.also { pool = it }
    }

    /** Downloads and stores a fresh pool. Blocking. */
    fun download(
        onStage: (CardPoolBootstrapper.Stage) -> Unit,
        onProgress: (Long, Long) -> Unit,
    ): CardPool = bootstrapper.download(onStage, onProgress).also { pool = it }
}
