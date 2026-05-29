package com.astarworks.astera.core.domain.news

/**
 * Editorial weight of a [NewsEntry] (Wave J 5server-content-ops, ADR-0127).
 *
 *  - [Info]      — routine update (events, cosmetic launches, devblog).
 *  - [Important] — gameplay-affecting change worth a player's attention.
 *  - [Critical]  — incident notice, emergency maintenance, security advisory.
 *
 * Adapters MAY format / colour each variant differently; the domain stays
 * presentation-free.
 */
public enum class NewsSeverity {
    Info,
    Important,
    Critical,
}
