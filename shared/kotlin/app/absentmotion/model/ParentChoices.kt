package app.absentmotion.model

/** Candidates in timeline order, excluding self-parenting and descendant cycles. */
fun Scene.parentCandidates(selected: Set<Long>): List<Layer> {
    val byId = (layers + bones).associateBy { it.id }
    return layers.asReversed().filter { candidate ->
        var at: Layer? = candidate
        val visited = HashSet<Long>()
        var allowed = true
        while (at != null) {
            if (at.id in selected || !visited.add(at.id)) { allowed = false; break }
            at = at.node["parent"]?.toLongOrNull()?.let(byId::get)
        }
        allowed
    }
}
