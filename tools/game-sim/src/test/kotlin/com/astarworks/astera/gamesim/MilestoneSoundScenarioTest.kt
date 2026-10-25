package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.application.port.outbound.effect.ISoundRegistry
import com.astarworks.astera.core.application.port.outbound.music.IPlayerBgm
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.effect.SoundCues
import com.astarworks.astera.core.domain.effect.SoundId
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.LoginBonusClaimed
import com.astarworks.astera.core.domain.event.PlayerEnteredMatch
import com.astarworks.astera.core.domain.event.PlayerRespawned
import com.astarworks.astera.core.domain.event.QuestChainCompleted
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.music.MusicMood
import com.astarworks.astera.core.domain.music.MusicTrackId
import com.astarworks.astera.core.domain.quest.QuestChainId
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.domain.event.PhaseChanged
import com.astarworks.astera.stats.domain.event.LeveledUp
import com.astarworks.astera.stats.domain.level.Level
import com.astarworks.astera.stats.domain.level.Xp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0256: every game milestone plays a sound cue defined in `content/sounds/`, through the one
 * `ISoundCuePlayer` -> `IPlayerSoundGateway` path, and BGM follows lobby / match / tension / result.
 * The simulator records every per-viewer sound in `sim.playerSounds`.
 */
class MilestoneSoundScenarioTest {

    /** The primary sound of [cue] exactly as `content/sounds/` defines it (volume setting at the default 100%). */
    private fun ScenarioScope.spec(cue: SoundId): SoundSpec {
        val def = checkNotNull(sim.services.getOrThrow<ISoundRegistry>().find(cue)) { "cue $cue is not defined in content/sounds" }
        return SoundSpec(def.resourceKey, def.defaultVolume, def.defaultPitch)
    }

    /** How many times [who] has heard [cue]'s primary sound since the last `clear()`. */
    private fun ScenarioScope.heard(who: SimEntity, cue: SoundId): Int =
        sim.playerSounds.calls.count { it.playerId == who.id && it.sound == spec(cue) }

    @Test
    fun `countdown, go, kill, death, respawn and the result each play their cue and BGM follows the match`() {
        scenario("milestone-sounds-match") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val bgm = sim.services.getOrThrow<IPlayerBgm>()
            assertThat(bgm.moodOf(red.id)).`as`("joining the server starts the lobby BGM").isEqualTo(MusicMood.LOBBY)
            assertThat(bgm.trackOf(red.id)).isEqualTo(MusicTrackId("lobby-loop"))

            sim.playerSounds.clear()
            val matchId = startMatch("annihilation", mapOf(red to "red", blue to "blue"), stage = "annihilation-arena")
            sim.eventBus.publish(PlayerEnteredMatch(matchId, red.id))
            assertThat(bgm.moodOf(red.id)).`as`("entering the match switches BGM").isEqualTo(MusicMood.MATCH)
            assertThat(bgm.trackOf(red.id)).isEqualTo(MusicTrackId("match-default"))

            // 5-4-3-2-1: one tick per second, then "go".
            advance(125)
            assertThat(heard(red, SoundCues.MATCH_COUNTDOWN_TICK)).`as`("countdown ticks").isEqualTo(5)
            assertThat(heard(blue, SoundCues.MATCH_COUNTDOWN_TICK)).isEqualTo(5)
            assertThat(heard(red, SoundCues.MATCH_GO)).`as`("go").isEqualTo(1)
            // Layers of a cue play together with the primary sound.
            val goLayers = checkNotNull(sim.services.getOrThrow<ISoundRegistry>().find(SoundCues.MATCH_GO)).layers
            assertThat(goLayers).isNotEmpty()
            assertThat(sim.playerSounds.calls.map { it.sound.key }).contains(goLayers.first().resourceKey)

            // Last stretch: BGM tension.
            sim.eventBus.publish(PhaseChanged(matchId, MatchPhase.ACTIVE, MatchPhase.SUDDEN_DEATH, 1L))
            assertThat(bgm.moodOf(red.id)).isEqualTo(MusicMood.HIGH_INTENSITY)
            assertThat(bgm.trackOf(red.id)).isEqualTo(MusicTrackId("high-intensity-drums"))

            // Red shoots blue (1 HP) dead: kill cue for the killer, death cue for the victim, respawn cue later.
            sim.setMaxHealth(blue, 1.0)
            val blueSpawn = blue.position
            red.moveTo(blueSpawn + Vec3(0.0, 0.0, 6.0))
            red.holds("pistol")
            red.faces(blue)
            sim.playerSounds.clear()
            repeat(20) {
                if (eventsOf<PlayerKilled>().none { it.victim == blue.id }) {
                    red.rightClick()
                    advance(10)
                }
            }
            assertThat(eventsOf<PlayerKilled>().any { it.victim == blue.id }).isTrue()
            assertThat(heard(red, SoundCues.COMBAT_KILL)).`as`("killer hears the kill cue").isGreaterThanOrEqualTo(1)
            assertThat(heard(red, SoundCues.COMBAT_HIT)).`as`("hit marker cue").isGreaterThanOrEqualTo(1)
            assertThat(heard(blue, SoundCues.MATCH_DEATH)).`as`("victim hears the death cue").isEqualTo(1)
            assertThat(heard(red, SoundCues.MATCH_DEATH)).isZero()
            advance(110)
            assertThat(eventsOf<PlayerRespawned>().any { it.playerId == blue.id }).isTrue()
            assertThat(heard(blue, SoundCues.MATCH_RESPAWN)).`as`("respawn cue").isEqualTo(1)

            // Result: win / lose cues and the victory / defeat BGM.
            sim.playerSounds.clear()
            sim.services.getOrThrow<IMatchLifecycle>().end(matchId, EndReason.TeamVictory(TeamId("red")))
            advance(5)
            assertThat(heard(red, SoundCues.MATCH_WIN)).`as`("winner cue").isEqualTo(1)
            assertThat(heard(blue, SoundCues.MATCH_LOSE)).`as`("loser cue").isEqualTo(1)
            assertThat(heard(red, SoundCues.MATCH_LOSE)).isZero()
            assertThat(bgm.moodOf(red.id)).isEqualTo(MusicMood.VICTORY)
            assertThat(bgm.moodOf(blue.id)).isEqualTo(MusicMood.DEFEAT)
        }
    }

    @Test
    fun `star burst and star pickup play their cues`() {
        scenario("milestone-sounds-stars") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val matchId = startMatch("skirmish", mapOf(red to "red", blue to "blue"), stage = "example-arena")
            val stars = sim.services.getOrThrow<com.astarworks.astera.match.application.port.outbound.IMatchStarStore>()

            stars.add(matchId, blue.id, 6)
            sim.setMaxHealth(blue, 1.0)
            val deathSpot = blue.position
            red.moveTo(deathSpot + Vec3(0.0, 0.0, 6.0))
            red.holds("pistol")
            red.faces(blue)
            sim.playerSounds.clear()
            repeat(20) {
                if (eventsOf<PlayerKilled>().none { it.victim == blue.id }) {
                    red.rightClick()
                    advance(10)
                }
            }
            assertThat(heard(blue, SoundCues.STAR_BURST)).`as`("victim hears the burst").isEqualTo(1)
            assertThat(heard(red, SoundCues.STAR_BURST)).`as`("killer hears the burst").isEqualTo(1)

            // Red walks over the dropped stars.
            red.moveTo(deathSpot)
            advance(10)
            for (i in 0 until 12) {
                val a = i * Math.PI / 6
                red.moveTo(Vec3(deathSpot.x + Math.cos(a) * 1.5, deathSpot.y, deathSpot.z + Math.sin(a) * 1.5))
                advance(10)
            }
            assertThat(heard(red, SoundCues.STAR_PICKUP)).`as`("pickup cue").isGreaterThanOrEqualTo(1)
        }
    }

    @Test
    fun `login bonus, quest complete, purchase and player level up play their cues`() {
        scenario("milestone-sounds-ui") {
            val alice = player("alice")
            advance(110) // the daily login bonus is claimed shortly after joining
            assertThat(eventsOf<LoginBonusClaimed>()).isNotEmpty()
            assertThat(heard(alice, SoundCues.UI_LOGIN_BONUS)).`as`("login bonus cue").isEqualTo(1)

            val bus = sim.services.getOrThrow<IDomainEventBus>()
            bus.publish(QuestChainCompleted(alice.id, QuestChainId("tutorial-chain"), 1L))
            assertThat(heard(alice, SoundCues.UI_QUEST_COMPLETE)).`as`("quest complete cue").isEqualTo(1)

            sim.playerSounds.clear()
            bus.publish(LeveledUp(alice.id, Level(1), Level(2), Xp(100L)))
            assertThat(heard(alice, SoundCues.UI_LEVEL_UP)).`as`("player level up cue").isEqualTo(1)

            // A real shop purchase (knight-sword, confirmed by a second click).
            sim.services.getOrThrow<ICurrencyLedger>().credit(alice.id, Currency.GOLD, 150L)
            val view = checkNotNull(sim.server.view(alice.id))
            val router = sim.services.getOrThrow<IGuiIntentRouter>()
            router.dispatch(view, GuiIntent("lobby.shop.open", emptyMap()))
            sim.playerSounds.clear()
            repeat(2) { router.dispatch(view, GuiIntent("shop.purchase", mapOf("shop_id" to "example-shop", "offer_id" to "knight-sword"))) }
            assertThat(heard(alice, SoundCues.UI_PURCHASE)).`as`("purchase cue").isEqualTo(1)
        }
    }

    @Test
    fun `the sound and music volume settings mute cues and BGM`() {
        scenario("milestone-sounds-muted") {
            val alice = player("alice")
            val settings = sim.services.getOrThrow<IPlayerSettingsRepository>()
            settings.upsert(settings.findOrDefault(alice.id).copy(soundVolume = 0, musicVolume = 0))
            sim.playerSounds.clear()
            val bus = sim.services.getOrThrow<IDomainEventBus>()
            bus.publish(QuestChainCompleted(alice.id, QuestChainId("tutorial-chain"), 1L))
            assertThat(sim.playerSounds.calls).`as`("sound volume 0 plays nothing").isEmpty()

            val bgm = sim.services.getOrThrow<IPlayerBgm>()
            bgm.switchTo(alice.id, MusicMood.MATCH)
            assertThat(bgm.trackOf(alice.id)).`as`("music volume 0 plays no BGM").isNull()

            settings.upsert(settings.findOrDefault(alice.id).copy(soundVolume = 50, musicVolume = 100))
            bus.publish(QuestChainCompleted(alice.id, QuestChainId("tutorial-chain"), 2L))
            val played = sim.playerSounds.calls.single().sound
            assertThat(played.volume).`as`("sound volume 50% halves the cue volume").isEqualTo(0.45)
            bgm.switchTo(alice.id, MusicMood.LOBBY)
            assertThat(bgm.trackOf(alice.id)).isEqualTo(MusicTrackId("lobby-loop"))
        }
    }
}
