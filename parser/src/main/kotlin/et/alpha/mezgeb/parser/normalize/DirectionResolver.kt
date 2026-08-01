package et.alpha.mezgeb.parser.normalize

import et.alpha.mezgeb.parser.model.Direction
import et.alpha.mezgeb.parser.rule.DirectionStrategy
import et.alpha.mezgeb.parser.rule.ParserRule

/**
 * Decides whether money came in or went out.
 *
 * Most templates are unambiguous by construction — a "you have received" pattern is
 * always inbound — so the rule states the direction outright. Where one template
 * serves both directions, keywords decide, and those keywords live in the rule's data
 * rather than in this file. That is what lets an Amharic template be added without
 * touching code.
 */
object DirectionResolver {

    /**
     * @param captured the text of the rule's direction group, when it has one.
     * @return the direction, or null when a KEYWORD rule found no keyword — in which
     *   case the parser must not guess.
     */
    fun resolve(rule: ParserRule, body: String, captured: String?): Direction? {
        rule.fixedDirection()?.let { return it }

        if (rule.directionStrategy != DirectionStrategy.KEYWORD) return null
        val keywords = rule.directionKeywords ?: return null

        // Prefer the captured span: it is the part of the message the rule author
        // pointed at, so it carries less risk of a stray word elsewhere deciding.
        val haystacks = listOfNotNull(captured, body)
        for (haystack in haystacks) {
            val lower = haystack.lowercase()
            val inboundHit = keywords.inbound.firstOrNull { lower.contains(it.lowercase()) }
            val outboundHit = keywords.outbound.firstOrNull { lower.contains(it.lowercase()) }

            when {
                inboundHit != null && outboundHit == null -> return Direction.IN
                outboundHit != null && inboundHit == null -> return Direction.OUT
                // Both present in the same haystack: the longer keyword is the more
                // specific statement, e.g. "transferred to" beats "transferred".
                inboundHit != null && outboundHit != null ->
                    return if (inboundHit.length >= outboundHit.length) Direction.IN else Direction.OUT
            }
        }
        return null
    }
}
