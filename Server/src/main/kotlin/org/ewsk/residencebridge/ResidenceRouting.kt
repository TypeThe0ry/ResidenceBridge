package org.ewsk.residencebridge

internal fun residenceLookupCandidates(name: String): List<String> {
    val target = name.trim()
    if (target.isEmpty()) {
        return emptyList()
    }
    val candidates = mutableListOf<String>()
    var candidate = target
    while (true) {
        candidates += candidate
        val separator = candidate.lastIndexOf('.')
        if (separator <= 0) {
            return candidates
        }
        candidate = candidate.substring(0, separator)
    }
}

internal fun <T : Any> findResidenceRoute(name: String, lookup: (String) -> T?): T? {
    residenceLookupCandidates(name).forEach { candidate ->
        lookup(candidate)?.let { return it }
    }
    return null
}

internal fun <T : Any> findResidenceByPath(
    name: String,
    rootLookup: (String) -> T?,
    childLookup: (T, String) -> T?
): T? {
    val parts = name.trim().split('.')
    if (parts.isEmpty() || parts.any { it.isBlank() }) {
        return null
    }
    var residence = rootLookup(parts.first()) ?: return null
    parts.drop(1).forEach { childName ->
        residence = childLookup(residence, childName) ?: return null
    }
    return residence
}

internal fun ResidenceIndexEntry.forTargetResidence(name: String): ResidenceIndexEntry {
    val targetKey = key(name)
    return if (nameKey == targetKey) this else copy(nameKey = targetKey, displayName = name)
}

internal fun findResidenceIndexRoute(
    name: String,
    lookup: (String) -> ResidenceIndexEntry?
): ResidenceIndexEntry? {
    return findResidenceRoute(name, lookup)?.forTargetResidence(name)
}

internal fun <T> flattenResidenceTree(
    roots: Iterable<Pair<String, T>>,
    children: (T) -> Iterable<Pair<String, T>>
): List<Pair<String, T>> {
    fun flatten(name: String, node: T): List<Pair<String, T>> {
        return listOf(name to node) + children(node).flatMap { (childName, child) ->
            flatten("$name.$childName", child)
        }
    }
    return roots.flatMap { (name, node) -> flatten(name, node) }
}
