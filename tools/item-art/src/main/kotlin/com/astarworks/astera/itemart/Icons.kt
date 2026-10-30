package com.astarworks.astera.itemart

import java.awt.image.BufferedImage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private fun c(v: Long): Int = v.toInt()

/** Colour set of an icon plaque: bevelled border, flat fill, glyph ink and an accent. */
internal enum class Theme(val border: Ramp, val fill: Int, val ink: Ramp, val accent: Ramp, val glow: Int) {
    TILE(
        Ramp(c(0xFF6B4A0C), c(0xFFC4901C), c(0xFFF2C94C), c(0xFFFFF0A0)), c(0xFF1E2140),
        Ramp(c(0xFF9AA3C8), c(0xFFD6DBF2), c(0xFFF2F4FF), c(0xFFFFFFFF)),
        Ramp(c(0xFF8C5A0E), c(0xFFD9A020), c(0xFFFFD34D), c(0xFFFFF2A8)), c(0xFFFFD34D),
    ),
    ADMIN(
        Ramp(c(0xFF2E3A4E), c(0xFF566A88), c(0xFF8AA2C4), c(0xFFC4D6EE)), c(0xFF171C28),
        Ramp(c(0xFF5CB8D8), c(0xFF9BE0F2), c(0xFFD2F4FC), c(0xFFFFFFFF)),
        Ramp(c(0xFF2F7FA8), c(0xFF4DB4DE), c(0xFF7FD6F2), c(0xFFC4F0FC)), c(0xFF4DB4DE),
    ),
    SKILL(
        Ramp(c(0xFF45194F), c(0xFF84389A), c(0xFFB04CC8), c(0xFFE08CF0)), c(0xFF1D1129),
        Ramp(c(0xFFB89AE0), c(0xFFE0D0F8), c(0xFFF4ECFF), c(0xFFFFFFFF)),
        Ramp(c(0xFFC0501A), c(0xFFFF8F2B), c(0xFFFFC04D), c(0xFFFFF0A0)), c(0xFFD070E8),
    ),
    DANGER(
        Ramp(c(0xFF4A0F14), c(0xFF8E1B24), c(0xFFC03340), c(0xFFE86A70)), c(0xFF2A1014),
        Ramp(c(0xFFE08A90), c(0xFFF4B8BC), c(0xFFFFDADC), c(0xFFFFFFFF)),
        Ramp(c(0xFFA82A32), c(0xFFE8434C), c(0xFFFF7A80), c(0xFFFFB8BA)), c(0xFFE8434C),
    ),
    GOOD(
        Ramp(c(0xFF174A22), c(0xFF2A8A38), c(0xFF3FB04A), c(0xFF8EE36B)), c(0xFF102418),
        Ramp(c(0xFFA8E8A0), c(0xFFD0F8C8), c(0xFFEAFFE4), c(0xFFFFFFFF)),
        Ramp(c(0xFF2A8A38), c(0xFF3FB04A), c(0xFF8EE36B), c(0xFFE2FFB8)), c(0xFF6BE070),
    ),
}

internal enum class Glyph {
    SHOP, QUEUE, CUSTOMIZE, PARTY, FRIENDS, SETTINGS, TRAVEL, HOME, ARENA, SAPLING, VAULT, COMPASS,
    ORB_ON, ORB_OFF, AOE, SLASH, ARROW_SHOT, BUFF, SKULL, PORTAL,
    BACK, PREV, NEXT, PAGE, CLOSE, REFRESH, CHECK, BRING, KICK, KIT, FLAG, SWORDS, CLOCK, WING, CUBE, SHIELD,
    HEART, HEAL, SERVER, BOOK, FLASK, SWORD, GUN, STAFF, BOX, STAR, COIN, BOLT, AMMO, MIRROR,
}

/** Icon design of one item id. */
internal data class IconSpec(val glyph: Glyph, val theme: Theme, val plaque: Boolean = true)

internal object Icons {
    val SPECS: Map<String, IconSpec> = buildMap {
        fun t(id: String, g: Glyph, th: Theme = Theme.TILE) { put(id, IconSpec(g, th)) }
        // lobby kit and main-menu tiles
        t("menu-compass", Glyph.COMPASS)
        t("gui-tile-queue", Glyph.SWORDS)
        t("gui-tile-shop", Glyph.SHOP)
        t("gui-tile-customize", Glyph.CUSTOMIZE)
        t("gui-tile-party", Glyph.PARTY)
        t("gui-tile-friends", Glyph.FRIENDS)
        t("gui-tile-settings", Glyph.SETTINGS)
        t("gui-tile-travel", Glyph.TRAVEL)
        t("gui-tile-travel-lobby", Glyph.HOME)
        t("gui-tile-travel-pve", Glyph.ARENA)
        t("gui-tile-travel-survival", Glyph.SAPLING)
        t("gui-tile-vault", Glyph.VAULT)
        t("gui-tile-cosmetics", Glyph.MIRROR)
        // indicators
        put("indicator-on", IconSpec(Glyph.ORB_ON, Theme.GOOD, plaque = false))
        put("indicator-off", IconSpec(Glyph.ORB_OFF, Theme.ADMIN, plaque = false))
        // skill kinds
        t("skill-tester", Glyph.BOLT, Theme.SKILL)
        // admin panel
        t("gui-admin-back", Glyph.BACK, Theme.ADMIN)
        t("gui-admin-prev", Glyph.PREV, Theme.ADMIN)
        t("gui-admin-next", Glyph.NEXT, Theme.ADMIN)
        t("gui-admin-page", Glyph.PAGE, Theme.ADMIN)
        t("gui-admin-close", Glyph.CLOSE, Theme.DANGER)
        t("gui-admin-refresh", Glyph.REFRESH, Theme.ADMIN)
        t("gui-admin-reload", Glyph.REFRESH, Theme.GOOD)
        t("gui-admin-validate", Glyph.CHECK, Theme.GOOD)
        t("gui-admin-bring", Glyph.BRING, Theme.ADMIN)
        t("gui-admin-teleport", Glyph.PORTAL, Theme.ADMIN)
        t("gui-admin-kick", Glyph.KICK, Theme.DANGER)
        t("gui-admin-kit", Glyph.KIT, Theme.ADMIN)
        t("gui-admin-lanes", Glyph.FLAG, Theme.ADMIN)
        t("gui-admin-lane", Glyph.FLAG, Theme.GOOD)
        t("gui-admin-match", Glyph.SWORDS, Theme.ADMIN)
        t("gui-admin-match-control", Glyph.CLOCK, Theme.ADMIN)
        t("gui-admin-cooldowns", Glyph.CLOCK, Theme.SKILL)
        t("gui-admin-fly", Glyph.WING, Theme.ADMIN)
        t("gui-admin-gamemode", Glyph.CUBE, Theme.ADMIN)
        t("gui-admin-god", Glyph.SHIELD, Theme.TILE)
        t("gui-admin-health", Glyph.HEART, Theme.DANGER)
        t("gui-admin-heal", Glyph.HEAL, Theme.GOOD)
        t("gui-admin-self", Glyph.STAR, Theme.ADMIN)
        t("gui-admin-server", Glyph.SERVER, Theme.ADMIN)
        t("gui-admin-skills", Glyph.BOOK, Theme.SKILL)
        t("gui-admin-statuses", Glyph.FLASK, Theme.SKILL)
        t("gui-admin-weapons", Glyph.SWORD, Theme.TILE)
        t("gui-admin-currency", Glyph.COIN, Theme.TILE)
        t("gui-admin-refill", Glyph.AMMO, Theme.ADMIN)
        t("gui-admin-cat-all", Glyph.COMPASS, Theme.ADMIN)
        t("gui-admin-cat-gun", Glyph.GUN, Theme.ADMIN)
        t("gui-admin-cat-other", Glyph.BOX, Theme.ADMIN)
        t("gui-admin-cat-staff", Glyph.STAFF, Theme.ADMIN)
        t("gui-admin-cat-support", Glyph.SHIELD, Theme.ADMIN)
        t("gui-admin-cat-sword", Glyph.SWORD, Theme.ADMIN)
    }

    /** Items that deliberately keep their vanilla look: filler panes, player heads (skin), a spacer. */
    val VANILLA: Set<String> = setOf(
        "gui-pane-dark", "gui-pane-red", "gui-admin-empty",
        "gui-admin-player-head", "gui-admin-players", "gui-tile-profile", "gui-tile-training",
    )

    fun render(id: String, spec: IconSpec): BufferedImage {
        val seed = id.fold(1469598103934665603L) { h, ch -> (h xor ch.code.toLong()) * 1099511628211L }
        val sprite = Sprite(seed, partEdges = true)
        IconCanvas(sprite, spec).draw()
        return sprite.render()
    }
}

private class IconCanvas(val s: Sprite, val spec: IconSpec) {
    val th = spec.theme
    val bord = s.ramp(th.border)
    val fill = s.ramp(Ramp(th.fill, th.fill, th.fill, th.fill, flat = true))
    val ink = s.ramp(th.ink)
    val acc = s.ramp(th.accent)
    val dark = s.ramp(Ramp(th.border.dark, th.border.dark, th.border.dark, th.border.dark, flat = true))
    val red = s.ramp(Ramp(c(0xFFA82A32), c(0xFFE8434C), c(0xFFFF7A80), c(0xFFFFB8BA)))

    fun draw() {
        if (spec.plaque) {
            s.poly(chamfer(2.0, 2.0, 30.0, 30.0, 2.5), bord)
            s.poly(chamfer(4.0, 4.0, 28.0, 28.0, 1.5), fill)
        }
        glyph(spec.glyph)
    }

    private fun chamfer(x0: Double, y0: Double, x1: Double, y1: Double, c: Double) = listOf(
        P(x0 + c, y0), P(x1 - c, y0), P(x1, y0 + c), P(x1, y1 - c), P(x1 - c, y1), P(x0 + c, y1), P(x0, y1 - c), P(x0, y0 + c),
    )

    // ---- primitives ---------------------------------------------------------------------------

    private fun ring(cx: Double, cy: Double, r: Double, w: Double, id: Int, hole: Int = fill) {
        s.disc(cx, cy, r, id); s.disc(cx, cy, r - w, hole)
    }

    private fun star(cx: Double, cy: Double, ro: Double, ri: Double, n: Int, id: Int, rot: Double = -PI / 2) {
        val pts = (0 until n * 2).map { i ->
            val r = if (i % 2 == 0) ro else ri
            val a = rot + i * PI / n
            P(cx + cos(a) * r, cy + sin(a) * r)
        }
        s.poly(pts, id)
    }

    private fun arrow(x0: Double, y0: Double, x1: Double, y1: Double, w: Double, head: Double, id: Int) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = Math.hypot(dx, dy)
        val ux = dx / len
        val uy = dy / len
        val bx = x1 - ux * head
        val by = y1 - uy * head
        s.line(P(x0, y0), P(bx + ux, by + uy), w, id)
        val nx = -uy * head * 0.8
        val ny = ux * head * 0.8
        s.poly(listOf(P(x1, y1), P(bx + nx, by + ny), P(bx - nx, by - ny)), id)
    }

    private fun heart(cx: Double, cy: Double, k: Double, id: Int) {
        s.disc(cx - 2.9 * k, cy - 2.0 * k, 3.3 * k, id)
        s.disc(cx + 2.9 * k, cy - 2.0 * k, 3.3 * k, id)
        s.poly(listOf(P(cx - 6.0 * k, cy - 0.6 * k), P(cx + 6.0 * k, cy - 0.6 * k), P(cx, cy + 6.6 * k)), id)
    }

    private fun figure(cx: Double, cy: Double, k: Double, id: Int) {
        s.disc(cx, cy - 4.2 * k, 3.0 * k, id)
        s.disc(cx, cy + 2.6 * k, 4.6 * k, id)
        s.rect(cx - 5.2 * k, cy + 3.0 * k, cx + 5.2 * k, cy + 7.5 * k, id)
    }

    private fun sword(x0: Double, y0: Double, x1: Double, y1: Double, bladeId: Int = ink, hiltId: Int = acc) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = Math.hypot(dx, dy)
        val ux = dx / len
        val uy = dy / len
        val gx = x0 + dx * 0.32
        val gy = y0 + dy * 0.32
        s.line(P(x0, y0), P(gx, gy), 2.0, hiltId)
        s.line(P(gx, gy), P(x1 - ux * 1.5, y1 - uy * 1.5), 3.0, bladeId)
        s.poly(listOf(P(x1, y1), P(x1 - ux * 3 - uy * 1.5, y1 - uy * 3 + ux * 1.5), P(x1 - ux * 3 + uy * 1.5, y1 - uy * 3 - ux * 1.5)), bladeId)
        s.line(P(gx - uy * 3.6, gy + ux * 3.6), P(gx + uy * 3.6, gy - ux * 3.6), 1.8, hiltId)
        s.disc(x0, y0, 1.5, hiltId)
    }

    private fun clock(cx: Double, cy: Double, r: Double) {
        ring(cx, cy, r, 2.0, ink)
        s.line(P(cx, cy), P(cx, cy - r + 3.2), 1.6, acc)
        s.line(P(cx, cy), P(cx + r * 0.5, cy + r * 0.25), 1.6, acc)
        s.disc(cx, cy, 1.3, ink)
    }

    private fun shield(cx: Double, cy: Double, k: Double, outer: Int, inner: Int) {
        fun pts(f: Double) = listOf(
            P(cx - 7 * k * f, cy - 7.5 * k * f), P(cx + 7 * k * f, cy - 7.5 * k * f), P(cx + 7 * k * f, cy + 0.5 * k * f),
            P(cx + 3.5 * k * f, cy + 5.5 * k * f), P(cx, cy + 8 * k * f), P(cx - 3.5 * k * f, cy + 5.5 * k * f), P(cx - 7 * k * f, cy + 0.5 * k * f),
        )
        s.poly(pts(1.0), outer)
        s.poly(pts(0.62).map { P(it.x, it.y + 0.2) }, inner)
    }

    // ---- glyphs -------------------------------------------------------------------------------

    private fun glyph(g: Glyph) {
        when (g) {
            Glyph.SHOP -> {
                s.poly(listOf(P(9.0, 13.0), P(23.0, 13.0), P(25.0, 25.0), P(7.0, 25.0)), acc)
                s.poly(listOf(P(12.0, 13.0), P(14.0, 8.0), P(18.0, 8.0), P(20.0, 13.0)), acc)
                s.rect(12.0, 7.0, 20.0, 9.0, ink)
                ring(16.0, 19.0, 3.8, 1.5, ink, acc)
                s.rect(15.2, 17.0, 16.8, 21.0, ink)
            }
            Glyph.SWORDS, Glyph.QUEUE -> {
                sword(7.0, 25.0, 25.0, 7.0)
                sword(25.0, 25.0, 7.0, 7.0, ink, acc)
            }
            Glyph.CUSTOMIZE -> {
                s.poly(listOf(P(9.0, 8.0), P(13.5, 8.0), P(16.0, 11.0), P(18.5, 8.0), P(23.0, 8.0), P(27.0, 12.0), P(24.0, 15.0), P(22.0, 14.0), P(22.0, 25.0), P(10.0, 25.0), P(10.0, 14.0), P(8.0, 15.0), P(5.0, 12.0)), ink)
                s.rect(10.0, 19.0, 22.0, 21.0, acc)
            }
            Glyph.PARTY -> {
                figure(9.5, 15.0, 0.75, acc)
                figure(22.5, 15.0, 0.75, acc)
                figure(16.0, 14.0, 1.0, ink)
            }
            Glyph.FRIENDS -> {
                heart(16.0, 14.5, 1.5, acc)
                s.disc(16.0, 12.0, 2.2, ink)
                s.poly(listOf(P(11.5, 21.0), P(12.5, 16.5), P(19.5, 16.5), P(20.5, 21.0)), ink)
            }
            Glyph.SETTINGS -> {
                for (i in 0 until 8) {
                    val a = i * PI / 4
                    s.line(P(16.0, 16.0), P(16.0 + cos(a) * 10.5, 16.0 + sin(a) * 10.5), 4.0, acc)
                }
                s.disc(16.0, 16.0, 8.0, ink)
                s.disc(16.0, 16.0, 3.4, fill)
            }
            Glyph.TRAVEL -> {
                ring(16.0, 16.0, 9.5, 2.2, ink)
                s.line(P(6.5, 16.0), P(25.5, 16.0), 1.4, acc)
                s.line(P(16.0, 6.5), P(16.0, 25.5), 1.4, acc)
                s.poly(listOf(P(16.0, 8.0), P(18.5, 16.0), P(16.0, 24.0), P(13.5, 16.0)), acc)
                s.disc(16.0, 16.0, 1.6, ink)
            }
            Glyph.HOME -> {
                s.poly(listOf(P(16.0, 6.5), P(27.0, 16.0), P(5.0, 16.0)), acc)
                s.rect(8.0, 16.0, 24.0, 26.0, ink)
                s.rect(13.5, 19.0, 18.5, 26.0, fill)
            }
            Glyph.ARENA -> {
                shield(16.0, 15.5, 1.3, ink, acc)
                sword(9.0, 23.0, 23.0, 9.0, ink, dark)
            }
            Glyph.SAPLING -> {
                s.rect(14.8, 16.0, 17.2, 26.0, acc)
                s.disc(16.0, 12.0, 6.0, ink)
                s.disc(11.0, 15.0, 4.0, ink)
                s.disc(21.0, 15.0, 4.0, ink)
                s.disc(14.0, 10.5, 1.6, acc)
                s.disc(19.0, 13.5, 1.3, acc)
            }
            Glyph.VAULT -> {
                s.rect(7.0, 14.0, 25.0, 26.0, acc)
                s.poly(listOf(P(7.0, 14.0), P(8.5, 8.0), P(23.5, 8.0), P(25.0, 14.0)), ink)
                s.rect(7.0, 13.5, 25.0, 15.5, dark)
                s.rect(14.0, 15.0, 18.0, 20.5, ink)
                s.disc(16.0, 17.2, 1.0, fill)
            }
            Glyph.MIRROR -> {
                // standing oval mirror: accent frame, ink glass with two glints, stand and a sparkle
                s.disc(13.5, 15.0, 8.8, acc)
                s.disc(13.5, 15.0, 6.6, ink)
                s.line(P(10.0, 17.0), P(13.0, 11.0), 1.4, acc)
                s.line(P(14.5, 19.5), P(15.5, 17.5), 1.0, acc)
                s.rect(12.5, 23.5, 14.5, 26.5, acc)
                s.rect(9.0, 26.0, 18.0, 27.5, acc)
                s.line(P(24.0, 5.5), P(24.0, 11.5), 1.6, ink)
                s.line(P(21.0, 8.5), P(27.0, 8.5), 1.6, ink)
                s.spark(24, 8, th.glow)
            }
            Glyph.COMPASS -> {
                ring(16.0, 16.0, 11.0, 2.2, acc)
                s.poly(listOf(P(16.0, 7.5), P(19.0, 16.0), P(13.0, 16.0)), ink)
                s.poly(listOf(P(16.0, 24.5), P(19.0, 16.0), P(13.0, 16.0)), red)
                s.disc(16.0, 16.0, 1.5, dark)
            }
            Glyph.ORB_ON, Glyph.ORB_OFF -> {
                val on = g == Glyph.ORB_ON
                s.disc(16.0, 16.0, 7.5, if (on) acc else bord)
                s.disc(14.0, 13.8, 2.0, if (on) ink else acc)
                if (on) s.glow(16.0, 16.0, 13.0, th.glow)
            }
            Glyph.AOE -> {
                star(16.0, 16.0, 11.5, 6.0, 8, acc)
                star(16.0, 16.0, 7.0, 3.6, 8, ink, rot = -PI / 2 + PI / 8)
                s.disc(16.0, 16.0, 2.0, fill)
            }
            Glyph.SLASH -> {
                sword(8.0, 25.0, 25.0, 8.0)
                s.line(P(6.0, 14.0), P(14.0, 6.0), 1.2, acc)
                s.line(P(18.0, 26.0), P(26.0, 18.0), 1.2, acc)
            }
            Glyph.ARROW_SHOT -> {
                arrow(6.0, 25.0, 26.0, 6.0, 2.2, 7.0, ink)
                s.poly(listOf(P(6.0, 25.0), P(6.0, 20.0), P(9.0, 22.0)), acc)
                s.poly(listOf(P(6.0, 25.0), P(11.0, 25.0), P(9.0, 22.0)), acc)
            }
            Glyph.BUFF -> {
                arrow(16.0, 27.0, 16.0, 6.0, 4.0, 9.0, acc)
                s.poly(listOf(P(8.0, 22.0), P(16.0, 14.0), P(24.0, 22.0), P(24.0, 25.0), P(16.0, 17.0), P(8.0, 25.0)), ink)
            }
            Glyph.SKULL -> {
                s.disc(16.0, 13.5, 9.0, ink)
                s.rect(11.0, 17.0, 21.0, 25.0, ink)
                s.disc(12.2, 14.0, 2.6, fill)
                s.disc(19.8, 14.0, 2.6, fill)
                s.poly(listOf(P(16.0, 17.0), P(14.6, 20.0), P(17.4, 20.0)), fill)
                s.rect(13.0, 22.0, 13.9, 25.0, fill)
                s.rect(15.5, 22.0, 16.4, 25.0, fill)
                s.rect(18.1, 22.0, 19.0, 25.0, fill)
            }
            Glyph.PORTAL -> {
                ring(16.0, 16.0, 11.0, 2.4, acc)
                ring(16.0, 16.0, 7.0, 2.0, ink)
                s.disc(16.0, 16.0, 2.6, acc)
            }
            Glyph.BACK -> arrow(25.0, 16.0, 7.0, 16.0, 4.0, 9.0, ink)
            Glyph.PREV -> s.poly(listOf(P(21.0, 7.0), P(23.0, 9.0), P(15.0, 16.0), P(23.0, 23.0), P(21.0, 25.0), P(10.5, 16.0)), ink)
            Glyph.NEXT -> s.poly(listOf(P(11.0, 7.0), P(9.0, 9.0), P(17.0, 16.0), P(9.0, 23.0), P(11.0, 25.0), P(21.5, 16.0)), ink)
            Glyph.PAGE -> {
                s.poly(listOf(P(9.0, 6.0), P(19.0, 6.0), P(24.0, 11.0), P(24.0, 26.0), P(9.0, 26.0)), ink)
                s.poly(listOf(P(19.0, 6.0), P(24.0, 11.0), P(19.0, 11.0)), acc)
                for (y in listOf(14.0, 18.0, 22.0)) s.rect(12.0, y, 21.0, y + 1.6, acc)
            }
            Glyph.CLOSE -> {
                s.line(P(8.5, 8.5), P(23.5, 23.5), 4.0, ink)
                s.line(P(23.5, 8.5), P(8.5, 23.5), 4.0, ink)
            }
            Glyph.REFRESH -> {
                ring(16.0, 17.0, 9.0, 3.0, ink)
                s.poly(listOf(P(16.0, 3.0), P(30.0, 3.0), P(30.0, 17.0), P(16.0, 17.0)), fill)
                s.poly(listOf(P(14.5, 3.5), P(14.5, 14.5), P(24.0, 9.0)), acc)
            }
            Glyph.CHECK -> s.poly(listOf(P(6.5, 17.0), P(9.5, 14.0), P(13.5, 18.0), P(22.5, 8.0), P(25.5, 11.0), P(13.5, 24.0)), ink)
            Glyph.BRING -> {
                ring(16.0, 21.0, 4.5, 2.0, acc)
                arrow(16.0, 5.0, 16.0, 17.0, 3.4, 7.0, ink)
                s.rect(8.0, 25.0, 24.0, 27.0, acc)
            }
            Glyph.KICK -> {
                s.rect(6.0, 6.0, 17.0, 26.0, ink)
                s.rect(8.0, 8.0, 15.0, 24.0, acc)
                s.disc(13.0, 16.0, 1.0, ink)
                arrow(15.0, 16.0, 27.0, 16.0, 3.0, 6.0, ink)
            }
            Glyph.KIT -> {
                s.rect(6.0, 12.0, 26.0, 26.0, acc)
                s.rect(6.0, 8.0, 26.0, 13.0, ink)
                s.rect(14.0, 12.0, 18.0, 26.0, ink)
                s.rect(6.0, 12.0, 26.0, 13.5, dark)
            }
            Glyph.FLAG -> {
                s.rect(8.0, 5.0, 10.0, 27.0, ink)
                s.poly(listOf(P(10.0, 6.0), P(25.0, 6.0), P(21.0, 11.0), P(25.0, 16.0), P(10.0, 16.0)), acc)
            }
            Glyph.CLOCK -> clock(16.0, 16.0, 11.0)
            Glyph.WING -> {
                s.poly(listOf(P(25.0, 6.0), P(18.0, 7.0), P(10.0, 13.0), P(6.0, 25.0), P(12.0, 21.0), P(17.0, 22.0), P(15.0, 17.0), P(21.0, 15.0), P(19.0, 11.0), P(24.0, 11.0)), ink)
                s.line(P(24.0, 7.0), P(8.0, 24.0), 1.2, acc)
            }
            Glyph.CUBE -> {
                s.poly(listOf(P(16.0, 5.5), P(26.0, 11.0), P(16.0, 16.5), P(6.0, 11.0)), ink)
                s.poly(listOf(P(6.0, 12.5), P(15.0, 17.8), P(15.0, 27.5), P(6.0, 22.0)), acc)
                s.poly(listOf(P(26.0, 12.5), P(17.0, 17.8), P(17.0, 27.5), P(26.0, 22.0)), dark)
            }
            Glyph.SHIELD -> shield(16.0, 15.5, 1.5, ink, acc)
            Glyph.HEART -> heart(16.0, 14.0, 1.7, acc)
            Glyph.HEAL -> {
                heart(16.0, 14.5, 1.7, ink)
                s.rect(14.4, 10.0, 17.6, 20.0, acc)
                s.rect(11.0, 13.4, 21.0, 16.6, acc)
            }
            Glyph.SERVER -> {
                s.rect(6.0, 7.0, 26.0, 20.0, ink)
                s.rect(8.0, 9.0, 24.0, 18.0, fill)
                s.rect(10.0, 11.0, 14.0, 13.0, acc)
                s.rect(10.0, 14.5, 20.0, 16.0, acc)
                s.rect(14.0, 20.0, 18.0, 24.0, ink)
                s.rect(9.0, 24.0, 23.0, 26.0, ink)
            }
            Glyph.BOOK -> {
                s.rect(7.0, 6.0, 25.0, 26.0, acc)
                s.rect(9.0, 6.0, 25.0, 24.0, ink)
                star(17.0, 15.0, 4.8, 2.0, 4, acc)
                s.rect(7.0, 6.0, 9.5, 26.0, dark)
            }
            Glyph.FLASK -> {
                s.rect(13.0, 5.0, 19.0, 8.0, acc)
                s.poly(listOf(P(14.0, 8.0), P(18.0, 8.0), P(18.0, 14.0), P(25.0, 25.0), P(7.0, 25.0), P(14.0, 14.0)), ink)
                s.poly(listOf(P(11.0, 20.0), P(21.0, 20.0), P(24.0, 25.0), P(8.0, 25.0)), acc)
            }
            Glyph.SWORD -> sword(7.0, 25.0, 25.0, 7.0)
            Glyph.GUN -> {
                s.rect(6.0, 9.0, 26.0, 15.0, ink)
                s.rect(23.0, 7.5, 27.0, 11.0, ink)
                s.poly(listOf(P(8.0, 15.0), P(14.0, 15.0), P(13.0, 26.0), P(8.5, 26.0)), acc)
                s.rect(10.0, 11.0, 20.0, 12.4, acc)
            }
            Glyph.STAFF -> {
                s.line(P(7.0, 26.0), P(20.0, 13.0), 2.2, acc)
                star(21.5, 11.0, 6.5, 3.2, 4, ink)
                s.disc(21.5, 11.0, 1.6, acc)
            }
            Glyph.BOX -> {
                s.poly(listOf(P(16.0, 5.5), P(26.0, 10.5), P(16.0, 15.5), P(6.0, 10.5)), ink)
                s.poly(listOf(P(6.0, 12.0), P(15.0, 17.0), P(15.0, 27.0), P(6.0, 22.0)), acc)
                s.poly(listOf(P(26.0, 12.0), P(17.0, 17.0), P(17.0, 27.0), P(26.0, 22.0)), ink)
            }
            Glyph.STAR -> star(16.0, 16.5, 11.5, 5.0, 5, ink)
            Glyph.COIN -> {
                s.disc(16.0, 16.0, 11.0, acc)
                ring(16.0, 16.0, 7.5, 1.6, ink, acc)
                s.rect(14.6, 11.0, 17.4, 21.0, ink)
                s.rect(12.0, 14.6, 20.0, 17.4, ink)
            }
            Glyph.BOLT -> {
                star(16.0, 16.0, 12.0, 6.5, 6, acc)
                s.poly(listOf(P(18.5, 5.0), P(10.0, 17.5), P(15.0, 17.5), P(13.0, 27.0), P(22.0, 14.0), P(17.0, 14.0)), ink)
            }
            Glyph.AMMO -> {
                for (i in 0 until 3) {
                    val x = 9.0 + i * 6.0
                    s.rect(x, 12.0, x + 4.0, 25.0, acc)
                    s.poly(listOf(P(x, 12.0), P(x + 2.0, 6.0), P(x + 4.0, 12.0)), ink)
                    s.rect(x, 21.0, x + 4.0, 22.5, dark)
                }
            }
        }
    }
}
