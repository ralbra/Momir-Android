package io.github.ralbra.randomize_the_gathering.data

import io.github.ralbra.randomize_the_gathering.model.Creature
import kotlin.random.Random

class CardRepository(private val bootstrapper: AtomicBootstrapper) {
    @Volatile
    private var byMv: Map<Int, List<Creature>> = emptyMap()

    fun ensureLoaded(onStage: (AtomicBootstrapper.Stage) -> Unit = {}): Int {
        byMv = bootstrapper.loadOrBootstrap(onStage)
        return byMv.values.sumOf { it.size }
    }

    fun refreshData(onStage: (AtomicBootstrapper.Stage) -> Unit = {}): Int {
        byMv = bootstrapper.forceRefresh(onStage)
        return byMv.values.sumOf { it.size }
    }

    fun getRandomCreature(mv: Int, includeFunny: Boolean): Creature? {
        val bucket = byMv[mv].orEmpty().let { cards ->
            if (includeFunny) cards else cards.filter { !it.isFunny }
        }
        if (bucket.isEmpty()) return null
        return bucket[Random.nextInt(bucket.size)]
    }

    /**
     * Cards whose name matches [query] (case-insensitive, at least two characters), best
     * matches first: exact, then names that start with the text, then names that contain it.
     * For "Front // Back" names every face counts. Un-set cards are included.
     */
    fun searchByName(query: String, limit: Int = 8): List<Creature> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()

        return byMv.values.asSequence()
            .flatten()
            .mapNotNull { card -> matchRank(card, q)?.let { rank -> rank to card } }
            .sortedWith(compareBy({ it.first }, { it.second.name }))
            .take(limit)
            .map { it.second }
            .toList()
    }

    private fun matchRank(card: Creature, q: String): Int? {
        val name = card.name.lowercase()
        val parts = name.split(" // ")
        return when {
            name == q || parts.any { it == q } -> 0
            name.startsWith(q) || parts.any { it.startsWith(q) } -> 1
            name.contains(q) -> 2
            else -> null
        }
    }
}
