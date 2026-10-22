package com.astarworks.astera.core.application.admin.panel

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.admin.panel.AdminPanelUi.pager
import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandActor
import com.astarworks.astera.core.application.command.CommandAuthorizer
import com.astarworks.astera.core.application.command.CommandConfirm
import com.astarworks.astera.core.application.command.CommandInvoker
import com.astarworks.astera.core.application.command.CommandPaths
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IServerTransferGateway
import com.astarworks.astera.core.application.port.outbound.admin.IAdminPlayerGateway
import com.astarworks.astera.core.application.port.outbound.admin.IAdminTextPrompt
import com.astarworks.astera.core.application.port.outbound.admin.ILobbyRepaster
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSize
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.gui.GuiText
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.proxy.ServerName
import com.astarworks.astera.core.domain.proxy.TransferReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The command console of the admin panel (ADR-0233): a GUI path for every operator command.
 *
 * Instead of one hand-written page per command, the console reads the live merged `/astera` tree
 * ([root]) and derives the argument form from the command node itself (names, types, optionality,
 * suggestion providers), so GUI and syntax cannot drift. [AdminCommandCatalog] adds what a spec
 * cannot say (category, confirm, preset buttons, target argument). Running goes through the very
 * [CommandInvoker] that the Brigadier bridge uses, so the per-node permission gate, the executor, the
 * localized replies and the internal-error mapping are identical to typing the command; the replies
 * are captured into a result page and echoed to chat.
 *
 * Flow: hub tile `Commands` -> categories -> actions -> form (one row per argument) -> picker
 * (player heads, choices, presets, recent values, completer suggestions, paging, search through a chat
 * prompt) -> optional confirm -> run -> result page. Every click goes through
 * [AdminPanelGuard.guarded] (permission re-check per click) and every run is audited.
 */
public class AdminCommandConsole(
    private val guard: AdminPanelGuard,
    private val registry: IAdminPanelRegistry,
    private val guiAdapter: IGuiAdapter,
    private val players: IAdminPlayerGateway,
    private val messages: IMessageRenderer,
    private val prompt: () -> IAdminTextPrompt,
    private val root: () -> CommandSpec.Literal,
    private val invoker: () -> CommandInvoker,
    private val authorizer: () -> CommandAuthorizer,
    scheduler: IScheduler,
    private val transfers: () -> IServerTransferGateway?,
    private val lobbyRepaster: () -> ILobbyRepaster?,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + scheduler.mainDispatcher)

    private class Form(val action: AdminPanelAction) {
        val values: MutableMap<String, String> = LinkedHashMap()
    }

    private class Outcome(val path: String, val ok: Boolean, val lines: List<String>)

    private class FormArg(
        val name: String,
        val type: ArgumentType,
        val optional: Boolean,
        val completer: ((CommandSpec.PartialInput) -> List<String>)?,
    )

    private class Candidate(
        val value: String,
        val label: GuiText,
        val head: PlayerId? = null,
        val rendered: Boolean = false,
    )

    private val forms: MutableMap<PlayerId, Form> = ConcurrentHashMap()
    private val results: MutableMap<PlayerId, Outcome> = ConcurrentHashMap()
    private val recent: MutableMap<String, List<String>> = ConcurrentHashMap()

    /** Register the hub tile, the per-player tile, the default actions and the `admin.cmd.` handler. */
    public fun install(router: IGuiIntentRouter): List<ServiceRegistry.Registration> {
        val regs = mutableListOf<ServiceRegistry.Registration>()
        regs += registry.register(
            AdminPanelTile(
                id = "core.commands",
                point = AdminPanelPoint.HUB,
                order = 25,
                icon = ICON_COMMANDS,
                name = GuiText(k("hub")),
                lore = listOf(GuiText(k("hub.lore"))),
                intent = GuiIntent(OPEN),
            ),
        )
        regs += registry.register(
            AdminPanelTile(
                id = "core.player.commands",
                point = AdminPanelPoint.PLAYER,
                order = 5,
                icon = ICON_COMMANDS,
                name = GuiText(k("tile.player")),
                lore = listOf(GuiText(k("tile.player.lore"))),
                intent = GuiIntent(PLAYER),
            ),
        )
        AdminCommandCatalog.defaults().forEach { regs += registry.registerAction(it) }
        regs += router.register(AdminIntents.CMD_PREFIX, guard.guarded(::onIntent))
        return regs
    }

    // ---- Dispatch -------------------------------------------------------

    private fun onIntent(intent: GuiIntent, viewer: IMcPlayer) {
        val p = intent.payload
        when (intent.key) {
            OPEN -> showCategories(viewer)
            CATEGORY -> categoryOf(p["cat"])?.let { showCategory(viewer, it, p["page"]?.toIntOrNull() ?: 0) } ?: showCategories(viewer)
            FORM -> openForm(viewer, p["a"], p["target"])
            SHOW -> showForm(viewer)
            PICK -> showPicker(viewer, p["arg"], p["page"]?.toIntOrNull() ?: 0, p["q"].orEmpty())
            SET -> onSet(viewer, p)
            CLEAR -> onClear(viewer, p["arg"])
            TYPE -> onType(viewer, p["arg"], p["mode"] == "search", p["q"].orEmpty())
            RUN -> onRun(viewer, p["confirm"] == "1")
            RESULT -> showResult(viewer, p["page"]?.toIntOrNull() ?: 0)
            PLAYER -> showPlayerPage(viewer, p[AdminPanelUi.PAYLOAD_TARGET])
            else -> log.warn("Unknown admin.cmd intent '{}' from {}", intent.key, viewer.name)
        }
    }

    // ---- Availability ---------------------------------------------------

    private fun actorOf(viewer: IMcPlayer): CommandActor =
        CommandActor(playerId = viewer.id, isOperator = players.isOperator(viewer.id))

    private fun nodeOf(action: AdminPanelAction): CommandSpec.Literal? =
        if (action.isSpecial) null else CommandPaths.find(root(), action.segments)?.takeIf { it.executor != null }

    private fun isAvailable(actor: CommandActor, action: AdminPanelAction): Boolean = when {
        action.path == AdminCommandCatalog.SEND_PLAYER -> transfers() != null
        action.path == AdminCommandCatalog.LOBBY_REPASTE -> lobbyRepaster() != null
        action.isSpecial -> false
        else -> nodeOf(action)?.let { authorizer().canUse(actor, it) } == true
    }

    private fun visibleActions(actor: CommandActor): List<AdminPanelAction> =
        registry.actions().filter { isAvailable(actor, it) }

    private fun formArgs(action: AdminPanelAction): List<FormArg> = when {
        action.path == AdminCommandCatalog.SEND_PLAYER -> listOf(
            FormArg("player", ArgumentType.PlayerSelector, false, null),
            FormArg("server", ArgumentType.Choice("lobby", "game"), false, null),
        )
        action.isSpecial -> emptyList()
        else -> nodeOf(action)?.arguments.orEmpty().map { FormArg(it.name, it.type, it.optional, it.completer) }
    }

    // ---- Categories and actions ----------------------------------------

    private fun categoryOf(key: String?): AdminCategory? = AdminCategory.entries.firstOrNull { it.key == key }

    /** The category page: one tile per category that has something the viewer may run. */
    public fun buildCategories(viewer: IMcPlayer): GuiSpec {
        val visible = visibleActions(actorOf(viewer))
        return GuiSpec.build(CATS_ID, k("title"), GuiSize.CHEST_27) {
            var i = 0
            AdminCategory.entries.forEach { cat ->
                val count = visible.count { it.category == cat }
                if (count > 0 && i < CATEGORY_SLOTS.size) {
                    slot(
                        SlotIndex(CATEGORY_SLOTS[i++]),
                        GuiSlot.Static(
                            itemId = cat.icon,
                            onLeftClick = GuiIntent(CATEGORY, mapOf("cat" to cat.key)),
                            displayName = GuiText(k("cat.${cat.key}")),
                            lore = listOf(GuiText(k("cat.${cat.key}.lore")), GuiText(k("cat.count"), mapOf("count" to count.toString()))),
                        ),
                    )
                }
            }
            slot(SlotIndex(18), AdminPanelUi.back())
            slot(SlotIndex(26), AdminPanelUi.close())
        }
    }

    private fun showCategories(viewer: IMcPlayer) = show(viewer, buildCategories(viewer))

    /** The action list of [category] (45 per page). */
    public fun buildCategory(viewer: IMcPlayer, category: AdminCategory, requestedPage: Int): GuiSpec {
        val actions = visibleActions(actorOf(viewer)).filter { it.category == category }
        val pages = AdminPanelUi.pageCount(actions.size, ACTIONS_PER_PAGE)
        val page = AdminPanelUi.clampPage(requestedPage, pages)
        return GuiSpec.build(CATEGORY_ID, k("cat.${category.key}.title"), GuiSize.CHEST_54) {
            actions.drop(page * ACTIONS_PER_PAGE).take(ACTIONS_PER_PAGE).forEachIndexed { i, action ->
                slot(SlotIndex(i), actionSlot(action, emptyMap()))
            }
            slot(SlotIndex(45), backToCategories())
            slot(SlotIndex(46), AdminPanelUi.back())
            pager(CATEGORY, mapOf("cat" to category.key), page, pages, prevSlot = 48, pageSlot = 49, nextSlot = 50)
            slot(SlotIndex(53), AdminPanelUi.close())
        }
    }

    private fun showCategory(viewer: IMcPlayer, category: AdminCategory, page: Int) =
        show(viewer, buildCategory(viewer, category, page))

    private fun actionSlot(action: AdminPanelAction, extra: Map<String, String>): GuiSlot.Static {
        val lore = mutableListOf<GuiText>()
        val name: GuiText
        if (action.isSpecial) {
            val id = action.path.removePrefix("@")
            name = GuiText(k("special.$id"))
            lore += GuiText(k("special.$id.desc"))
        } else {
            name = GuiText(k("action.row"), mapOf("command" to "/astera ${action.path}"))
            lore += GuiText(CommandPaths.descriptionKey(action.segments))
        }
        if (action.destructive) lore += GuiText(k("action.destructive"))
        lore += GuiText(k("action.hint"))
        return GuiSlot.Static(
            itemId = action.icon ?: action.category.icon,
            onLeftClick = GuiIntent(FORM, mapOf("a" to action.path) + extra),
            displayName = name,
            lore = lore,
        )
    }

    private fun backToCategories(): GuiSlot.Static = GuiSlot.Static(
        itemId = AdminPanelUi.BACK_ICON,
        onLeftClick = GuiIntent(OPEN),
        displayName = GuiText(k("nav.commands")),
    )

    // ---- Per-player page ------------------------------------------------

    private fun showPlayerPage(viewer: IMcPlayer, targetRaw: String?) {
        val target = targetRaw?.let { raw -> players.onlinePlayers().firstOrNull { it.id.toString() == raw } }
        if (target == null) {
            guard.tell(viewer, k("player.gone"))
            return
        }
        val actions = visibleActions(actorOf(viewer)).filter { it.playerArg != null }
        val payload = mapOf("target" to target.id.toString())
        show(
            viewer,
            GuiSpec.build(PLAYER_PAGE_ID, k("player.title"), GuiSize.CHEST_54) {
                slot(
                    SlotIndex(4),
                    GuiSlot.Static(
                        itemId = ICON_HEAD,
                        displayName = GuiText(k("player.row"), mapOf("player" to target.name)),
                        headOf = target.id,
                    ),
                )
                actions.take(PLAYER_PAGE_SLOTS.size).forEachIndexed { i, action ->
                    slot(SlotIndex(PLAYER_PAGE_SLOTS[i]), actionSlot(action, payload))
                }
                slot(SlotIndex(45), AdminPanelUi.back())
                slot(SlotIndex(53), AdminPanelUi.close())
            },
        )
    }

    // ---- Form -----------------------------------------------------------

    private fun openForm(viewer: IMcPlayer, path: String?, target: String?) {
        val action = registry.actionFor(path.orEmpty())
        if (action == null || !isAvailable(actorOf(viewer), action)) {
            guard.tell(viewer, k("unavailable"))
            return
        }
        val form = Form(action)
        forms[viewer.id] = form
        val playerArg = action.playerArg
        if (target != null && playerArg != null) {
            val who = players.onlinePlayers().firstOrNull { it.id.toString() == target }
            val arg = formArgs(action).firstOrNull { it.name == playerArg }
            if (who != null && arg != null) {
                form.values[playerArg] = if (arg.type is ArgumentType.PlayerSelector) who.id.toString() else who.name
            }
        }
        showForm(viewer)
    }

    private fun showForm(viewer: IMcPlayer) {
        val form = forms[viewer.id]
        if (form == null) {
            showCategories(viewer)
            return
        }
        show(viewer, buildForm(viewer, form))
    }

    private fun buildForm(viewer: IMcPlayer, form: Form): GuiSpec {
        val action = form.action
        val args = formArgs(action)
        val missing = args.filter { !it.optional && form.values[it.name] == null }.map { it.name }
        return GuiSpec.build(FORM_ID, k("form.title"), GuiSize.CHEST_36) {
            val header = mutableListOf<GuiText>()
            if (action.isSpecial) {
                header += GuiText(k("special.${action.path.removePrefix("@")}.desc"))
            } else {
                header += GuiText(CommandPaths.descriptionKey(action.segments))
                nodeOf(action)?.let { header += GuiText(k("form.usage"), mapOf("usage" to CommandPaths.usage(action.segments, it))) }
            }
            slot(
                SlotIndex(4),
                GuiSlot.Static(
                    itemId = action.icon ?: action.category.icon,
                    displayName = if (action.isSpecial) {
                        GuiText(k("special.${action.path.removePrefix("@")}"))
                    } else {
                        GuiText(k("form.header"), mapOf("command" to "/astera ${action.path}"))
                    },
                    lore = header,
                ),
            )
            args.take(FORM_SLOTS.size).forEachIndexed { i, arg ->
                slot(SlotIndex(FORM_SLOTS[i]), argSlot(viewer, form, arg))
            }
            slot(SlotIndex(27), backToCategories())
            slot(SlotIndex(28), AdminPanelUi.back())
            slot(
                SlotIndex(31),
                GuiSlot.Static(
                    itemId = ICON_RUN,
                    onLeftClick = GuiIntent(RUN),
                    displayName = if (missing.isEmpty()) {
                        GuiText(k("form.run"))
                    } else {
                        GuiText(k("form.run.missing"), mapOf("missing" to missing.joinToString(", ")))
                    },
                    lore = listOf(GuiText(k("form.run.lore"), mapOf("command" to preview(form)))),
                ),
            )
            slot(SlotIndex(35), AdminPanelUi.close())
        }
    }

    private fun argSlot(viewer: IMcPlayer, form: Form, arg: FormArg): GuiSlot.Static {
        val value = form.values[arg.name]
        val shown = value?.let { display(arg, it) }
        val open = if (needsPicker(form.action, arg)) {
            GuiIntent(PICK, mapOf("arg" to arg.name))
        } else {
            GuiIntent(TYPE, mapOf("arg" to arg.name))
        }
        return GuiSlot.Static(
            itemId = iconFor(arg.type),
            onLeftClick = open,
            onRightClick = if (value != null) GuiIntent(CLEAR, mapOf("arg" to arg.name)) else null,
            displayName = GuiText(if (arg.optional) k("arg.optional") else k("arg.required"), mapOf("name" to arg.name)),
            lore = listOf(
                if (shown != null) GuiText(k("arg.value"), mapOf("value" to shown)) else GuiText(k("arg.unset")),
                GuiText(k("arg.hint")),
            ),
            headOf = if (arg.type is ArgumentType.PlayerSelector) value?.let(::playerIdOrNull) else null,
        )
    }

    private fun iconFor(type: ArgumentType): ItemId = when (type) {
        ArgumentType.PlayerSelector, ArgumentType.PlayerName -> ICON_HEAD
        is ArgumentType.Choice -> ICON_CHOICE
        ArgumentType.ItemIdArg -> ICON_ITEM
        is ArgumentType.IntegerArg, is ArgumentType.DoubleArg -> ICON_NUMBER
        is ArgumentType.StringArg -> ICON_TEXT
    }

    private fun needsPicker(action: AdminPanelAction, arg: FormArg): Boolean =
        arg.type is ArgumentType.PlayerSelector || arg.type is ArgumentType.PlayerName ||
            arg.type is ArgumentType.Choice || arg.completer != null ||
            action.presets[arg.name].orEmpty().isNotEmpty() || recent[recentKey(action, arg)].orEmpty().isNotEmpty()

    private fun display(arg: FormArg, raw: String): String =
        if (arg.type is ArgumentType.PlayerSelector) {
            playerIdOrNull(raw)?.let { id -> players.onlinePlayers().firstOrNull { it.id == id }?.name } ?: raw
        } else {
            raw
        }

    private fun playerIdOrNull(raw: String): PlayerId? = runCatching { PlayerId(UUID.fromString(raw)) }.getOrNull()

    private fun preview(form: Form): String {
        val parts = formArgs(form.action).mapNotNull { arg -> form.values[arg.name]?.let { display(arg, it) } }
        return ("/astera " + form.action.path + " " + parts.joinToString(" ")).trim()
    }

    private fun onClear(viewer: IMcPlayer, argName: String?) {
        forms[viewer.id]?.values?.remove(argName)
        showForm(viewer)
    }

    // ---- Value parsing --------------------------------------------------

    /** Typed value for [raw], or `null` when it is not valid for [type]. Mirrors `CommandTreeRunner`. */
    private fun parse(type: ArgumentType, raw: String): Any? = when (type) {
        is ArgumentType.IntegerArg ->
            raw.toIntOrNull()?.takeIf { (type.min == null || it >= type.min) && (type.max == null || it <= type.max) }
        is ArgumentType.DoubleArg ->
            raw.toDoubleOrNull()?.takeIf { (type.min == null || it >= type.min) && (type.max == null || it <= type.max) }
        is ArgumentType.StringArg, ArgumentType.ItemIdArg, ArgumentType.PlayerName -> raw.takeIf { it.isNotBlank() }
        is ArgumentType.Choice -> type.values.firstOrNull { it.equals(raw, ignoreCase = true) }
        ArgumentType.PlayerSelector -> playerIdOrNull(raw)
    }

    private fun assign(viewer: IMcPlayer, form: Form, arg: FormArg, input: String): Boolean {
        var raw = input.trim()
        if (arg.type is ArgumentType.PlayerSelector && playerIdOrNull(raw) == null) {
            val who = players.onlinePlayers().firstOrNull { it.name.equals(raw, ignoreCase = true) }
            if (who == null) {
                guard.tell(viewer, k("player.gone"))
                return false
            }
            raw = who.id.toString()
        }
        val parsed = parse(arg.type, raw)
        if (parsed == null) {
            guard.tell(viewer, k("invalid"), mapOf("value" to raw, "arg" to arg.name))
            return false
        }
        val stored = (parsed as? String) ?: raw
        form.values[arg.name] = stored
        if (arg.type is ArgumentType.StringArg || arg.type is ArgumentType.IntegerArg || arg.type is ArgumentType.DoubleArg) {
            val key = recentKey(form.action, arg)
            recent[key] = (listOf(stored) + recent[key].orEmpty()).distinct().take(RECENT_KEEP)
        }
        return true
    }

    private fun recentKey(action: AdminPanelAction, arg: FormArg): String = "${action.path}:${arg.name}"

    private fun typedArgs(viewer: IMcPlayer, form: Form): Map<String, Any>? {
        val out = LinkedHashMap<String, Any>()
        for (arg in formArgs(form.action)) {
            val raw = form.values[arg.name] ?: continue
            val value = parse(arg.type, raw)
            if (value == null) {
                guard.tell(viewer, k("invalid"), mapOf("value" to raw, "arg" to arg.name))
                return null
            }
            out[arg.name] = value
        }
        return out
    }

    private fun typedSoFar(form: Form): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for (arg in formArgs(form.action)) {
            val raw = form.values[arg.name] ?: continue
            parse(arg.type, raw)?.let { out[arg.name] = it }
        }
        return out
    }

    // ---- Picker ---------------------------------------------------------

    private fun candidates(viewer: IMcPlayer, form: Form, arg: FormArg, query: String): List<Candidate> {
        val needle = query.trim()
        fun matches(s: String) = needle.isEmpty() || s.contains(needle, ignoreCase = true)
        val out = mutableListOf<Candidate>()
        when (val t = arg.type) {
            ArgumentType.PlayerSelector -> players.onlinePlayers().filter { matches(it.name) }.forEach {
                out += Candidate(it.id.toString(), GuiText(k("player.row"), mapOf("player" to it.name)), head = it.id)
            }
            ArgumentType.PlayerName -> players.onlinePlayers().filter { matches(it.name) }.forEach {
                out += Candidate(it.name, GuiText(k("player.row"), mapOf("player" to it.name)), head = it.id)
            }
            is ArgumentType.Choice -> t.values.filter { matches(it) }.forEach {
                out += Candidate(it, GuiText(k("pick.value"), mapOf("value" to it)))
            }
            else -> Unit
        }
        form.action.presets[arg.name].orEmpty().forEach { preset ->
            val label = preset.labelKey?.let { messages.render(viewer.id, it) } ?: preset.value
            if (matches(label) || matches(preset.value)) {
                val value = if (preset.sendRendered) preset.labelKey?.value ?: preset.value else preset.value
                out += Candidate(value, GuiText(k("pick.preset"), mapOf("label" to label)), rendered = preset.sendRendered)
            }
        }
        recent[recentKey(form.action, arg)].orEmpty().filter { matches(it) }.forEach {
            out += Candidate(it, GuiText(k("pick.recent"), mapOf("value" to it)))
        }
        arg.completer?.let { completer ->
            val partial = CommandSpec.PartialInput(needle, typedSoFar(form), actorOf(viewer))
            runCatching { completer(partial) }.getOrDefault(emptyList()).filter { matches(it) }.forEach {
                out += Candidate(it, GuiText(k("pick.value"), mapOf("value" to it)))
            }
        }
        return out.distinctBy { it.value }
    }

    /** The picker page for [arg] of the viewer's current form. */
    public fun buildPicker(viewer: IMcPlayer, argName: String, requestedPage: Int, query: String): GuiSpec? {
        val form = forms[viewer.id] ?: return null
        val arg = formArgs(form.action).firstOrNull { it.name == argName } ?: return null
        val list = candidates(viewer, form, arg, query)
        val pages = AdminPanelUi.pageCount(list.size, PICK_PER_PAGE)
        val page = AdminPanelUi.clampPage(requestedPage, pages)
        val freeText = arg.type !is ArgumentType.Choice && arg.type !is ArgumentType.PlayerSelector
        return GuiSpec.build(PICK_ID, k("pick.title"), GuiSize.CHEST_54) {
            list.drop(page * PICK_PER_PAGE).take(PICK_PER_PAGE).forEachIndexed { i, c ->
                slot(
                    SlotIndex(i),
                    GuiSlot.Static(
                        itemId = if (c.head != null) ICON_HEAD else ICON_ENTRY,
                        onLeftClick = GuiIntent(
                            SET,
                            mapOf("arg" to arg.name, "v" to c.value) + (if (c.rendered) mapOf("r" to "1") else emptyMap()),
                        ),
                        displayName = c.label,
                        headOf = c.head,
                    ),
                )
            }
            if (list.isEmpty()) {
                slot(SlotIndex(22), GuiSlot.Static(itemId = ICON_ENTRY, displayName = GuiText(k("pick.empty"))))
            }
            slot(
                SlotIndex(45),
                GuiSlot.Static(
                    itemId = AdminPanelUi.BACK_ICON,
                    onLeftClick = GuiIntent(SHOW),
                    displayName = GuiText(k("nav.form")),
                ),
            )
            if (query.isNotBlank()) {
                slot(
                    SlotIndex(46),
                    GuiSlot.Static(
                        itemId = AdminPanelUi.REFRESH_ICON,
                        onLeftClick = GuiIntent(PICK, mapOf("arg" to arg.name)),
                        displayName = GuiText(k("pick.clear-filter"), mapOf("q" to query)),
                    ),
                )
            }
            pager(PICK, mapOf("arg" to arg.name, "q" to query), page, pages, prevSlot = 47, pageSlot = 49, nextSlot = 51)
            slot(
                SlotIndex(48),
                GuiSlot.Static(
                    itemId = ICON_SEARCH,
                    onLeftClick = GuiIntent(TYPE, mapOf("arg" to arg.name, "mode" to "search")),
                    displayName = GuiText(k("pick.search")),
                    lore = listOf(GuiText(k("pick.search.lore"))),
                ),
            )
            if (freeText) {
                slot(
                    SlotIndex(50),
                    GuiSlot.Static(
                        itemId = ICON_TEXT,
                        onLeftClick = GuiIntent(TYPE, mapOf("arg" to arg.name)),
                        displayName = GuiText(k("pick.type")),
                        lore = listOf(GuiText(k("pick.type.lore"))),
                    ),
                )
            }
            slot(SlotIndex(53), AdminPanelUi.close())
        }
    }

    private fun showPicker(viewer: IMcPlayer, argName: String?, page: Int, query: String) {
        val spec = argName?.let { buildPicker(viewer, it, page, query) }
        if (spec == null) showForm(viewer) else show(viewer, spec)
    }

    private fun onSet(viewer: IMcPlayer, payload: Map<String, String>) {
        val form = forms[viewer.id] ?: return showCategories(viewer)
        val arg = formArgs(form.action).firstOrNull { it.name == payload["arg"] } ?: return showForm(viewer)
        var value = payload["v"] ?: return showForm(viewer)
        if (payload["r"] == "1") value = messages.render(viewer.id, MessageKey(value))
        assign(viewer, form, arg, value)
        showForm(viewer)
    }

    /** Close the GUI, ask for text in chat, then come back to the form (or the filtered picker). */
    private fun onType(viewer: IMcPlayer, argName: String?, search: Boolean, currentQuery: String) {
        val form = forms[viewer.id] ?: return showCategories(viewer)
        val arg = formArgs(form.action).firstOrNull { it.name == argName } ?: return showForm(viewer)
        guiAdapter.close(viewer)
        guard.tell(viewer, if (search) k("prompt.search") else k("prompt.value"), mapOf("arg" to arg.name, "q" to currentQuery))
        prompt().ask(viewer.id) { text ->
            val line = text?.trim()
            if (!guard.canUse(viewer.id)) {
                // Permission was revoked while the prompt was pending: drop silently, like a denied click.
            } else if (line.isNullOrEmpty() || line.equals("cancel", ignoreCase = true)) {
                guard.tell(viewer, k("prompt.cancelled"))
                showForm(viewer)
            } else if (search) {
                showPicker(viewer, arg.name, 0, line)
            } else {
                assign(viewer, form, arg, line)
                showForm(viewer)
            }
        }
    }

    // ---- Run ------------------------------------------------------------

    private fun onRun(viewer: IMcPlayer, confirmed: Boolean) {
        val form = forms[viewer.id] ?: return showCategories(viewer)
        val missing = formArgs(form.action).filter { !it.optional && form.values[it.name] == null }.map { it.name }
        if (missing.isNotEmpty()) {
            guard.tell(viewer, k("form.run.missing"), mapOf("missing" to missing.joinToString(", ")))
            return showForm(viewer)
        }
        if (form.action.destructive && !confirmed) return showConfirm(viewer, form)
        val typed = typedArgs(viewer, form) ?: return showForm(viewer)
        execute(viewer, form, typed)
    }

    private fun showConfirm(viewer: IMcPlayer, form: Form) {
        show(
            viewer,
            GuiSpec.build(CONFIRM_ID, k("confirm.title"), GuiSize.CHEST_27) {
                slot(
                    SlotIndex(4),
                    GuiSlot.Static(
                        itemId = ICON_RUN,
                        displayName = GuiText(k("confirm.summary"), mapOf("command" to preview(form))),
                        lore = listOf(GuiText(k("action.destructive"))),
                    ),
                )
                slot(
                    SlotIndex(11),
                    GuiSlot.Static(
                        itemId = ICON_YES,
                        onLeftClick = GuiIntent(RUN, mapOf("confirm" to "1")),
                        displayName = GuiText(k("confirm.yes")),
                    ),
                )
                slot(
                    SlotIndex(15),
                    GuiSlot.Static(
                        itemId = ICON_NO,
                        onLeftClick = GuiIntent(SHOW),
                        displayName = GuiText(k("confirm.no")),
                    ),
                )
                slot(SlotIndex(26), AdminPanelUi.close())
            },
        )
    }

    private fun execute(viewer: IMcPlayer, form: Form, typed: Map<String, Any>) {
        val action = form.action
        val actor = actorOf(viewer)
        val lines = mutableListOf<String>()
        val reply: (MessageKey, Map<String, String>) -> Unit = { key, placeholders ->
            val text = messages.render(viewer.id, key, placeholders)
            lines += text
            viewer.sendMessage(text)
        }
        val targetId = action.playerArg?.let { form.values[it] }
        val summary = preview(form)
        scope.launch {
            val ok = try {
                runAction(actor, action, typed, reply)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                log.warn("Admin console action {} failed", action.path, t)
                false
            }
            guard.record(viewer, "cmd:${action.path}", targetId, "$summary -> ${if (ok) "ok" else "failed"}")
            results[viewer.id] = Outcome(action.path, ok, lines.toList())
            showResult(viewer, 0)
        }
    }

    private suspend fun runAction(
        actor: CommandActor,
        action: AdminPanelAction,
        typed: Map<String, Any>,
        reply: (MessageKey, Map<String, String>) -> Unit,
    ): Boolean {
        if (action.isSpecial) return runSpecial(action, typed, reply)
        val node = nodeOf(action)
        if (node == null) {
            reply(k("unavailable"), emptyMap())
            return false
        }
        // The console has its own confirm page (shown before any destructive run), so a command-level
        // `--confirm` guard (ADR-0234) must not ask a second time.
        val args = if (node.arguments.any { it.name == CommandConfirm.ARGUMENT_NAME }) {
            typed + (CommandConfirm.ARGUMENT_NAME to CommandConfirm.FLAG)
        } else {
            typed
        }
        return invoker().invoke(node, actor, args, reply) is CommandResult.Success
    }

    private fun runSpecial(
        action: AdminPanelAction,
        typed: Map<String, Any>,
        reply: (MessageKey, Map<String, String>) -> Unit,
    ): Boolean {
        when (action.path) {
            AdminCommandCatalog.SEND_PLAYER -> {
                val gateway = transfers()
                val who = typed["player"] as? PlayerId
                val server = typed["server"] as? String
                if (gateway == null || who == null || server == null) {
                    reply(k("send.unavailable"), emptyMap())
                    return false
                }
                val name = players.onlinePlayers().firstOrNull { it.id == who }?.name ?: who.toString()
                return when (val r = gateway.transfer(who, ServerName(server), TransferReason.AdminMove)) {
                    is Result.Success -> {
                        reply(k("send.done"), mapOf("player" to name, "server" to server))
                        true
                    }
                    is Result.Failure -> {
                        reply(r.error.messageKey, emptyMap())
                        false
                    }
                }
            }
            AdminCommandCatalog.LOBBY_REPASTE -> {
                val repaster = lobbyRepaster()
                if (repaster == null) {
                    reply(k("repaste.unavailable"), emptyMap())
                    return false
                }
                val ok = repaster.repaste()
                reply(if (ok) k("repaste.done") else k("repaste.failed"), emptyMap())
                return ok
            }
            else -> {
                reply(k("unavailable"), emptyMap())
                return false
            }
        }
    }

    // ---- Result ---------------------------------------------------------

    /** The result page: every captured reply line, 45 per page. */
    public fun buildResult(viewer: IMcPlayer, requestedPage: Int): GuiSpec? {
        val outcome = results[viewer.id] ?: return null
        val lines = outcome.lines.map { TAGS.replace(it, "").take(MAX_LINE) }.ifEmpty { listOf("") }
        val pages = AdminPanelUi.pageCount(lines.size, RESULT_PER_PAGE)
        val page = AdminPanelUi.clampPage(requestedPage, pages)
        return GuiSpec.build(RESULT_ID, k("result.title"), GuiSize.CHEST_54) {
            lines.drop(page * RESULT_PER_PAGE).take(RESULT_PER_PAGE).forEachIndexed { i, line ->
                slot(
                    SlotIndex(i),
                    GuiSlot.Static(
                        itemId = ICON_ENTRY,
                        displayName = if (line.isEmpty()) GuiText(k("result.silent")) else GuiText(k("result.line"), mapOf("line" to line)),
                    ),
                )
            }
            slot(
                SlotIndex(45),
                GuiSlot.Static(
                    itemId = AdminPanelUi.BACK_ICON,
                    onLeftClick = GuiIntent(SHOW),
                    displayName = GuiText(k("result.again")),
                ),
            )
            slot(SlotIndex(46), backToCategories())
            slot(SlotIndex(47), AdminPanelUi.back())
            slot(
                SlotIndex(52),
                GuiSlot.Static(
                    itemId = if (outcome.ok) ICON_YES else ICON_NO,
                    displayName = GuiText(
                        if (outcome.ok) k("result.ok") else k("result.fail"),
                        mapOf("command" to "/astera ${outcome.path}"),
                    ),
                ),
            )
            pager(RESULT, emptyMap(), page, pages, prevSlot = 48, pageSlot = 49, nextSlot = 50)
            slot(SlotIndex(53), AdminPanelUi.close())
        }
    }

    private fun showResult(viewer: IMcPlayer, page: Int) {
        val spec = buildResult(viewer, page)
        if (spec == null) showCategories(viewer) else show(viewer, spec)
    }

    // ---- helpers --------------------------------------------------------

    private fun show(viewer: IMcPlayer, spec: GuiSpec) {
        if (!AdminPanelUi.show(guiAdapter, viewer, spec)) guard.tell(viewer, OPEN_REJECTED)
    }

    private fun k(s: String): MessageKey = MessageKey("astera.admin.panel.cmd.$s")

    public companion object {
        public const val OPEN: String = "admin.cmd.open"
        public const val CATEGORY: String = "admin.cmd.cat"
        public const val FORM: String = "admin.cmd.form"
        public const val SHOW: String = "admin.cmd.show"
        public const val PICK: String = "admin.cmd.pick"
        public const val SET: String = "admin.cmd.set"
        public const val CLEAR: String = "admin.cmd.clear"
        public const val TYPE: String = "admin.cmd.type"
        public const val RUN: String = "admin.cmd.run"
        public const val RESULT: String = "admin.cmd.result"
        public const val PLAYER: String = "admin.cmd.player"

        private const val ACTIONS_PER_PAGE: Int = 45
        private const val PICK_PER_PAGE: Int = 36
        private const val RESULT_PER_PAGE: Int = 45
        private const val MAX_LINE: Int = 160
        private const val RECENT_KEEP: Int = 8

        private val CATEGORY_SLOTS: List<Int> = (9..17).toList()
        private val FORM_SLOTS: List<Int> = (9..17).toList()
        private val PLAYER_PAGE_SLOTS: List<Int> = (9..44).toList()
        private val TAGS: Regex = Regex("<[^>]*>")

        public val CATS_ID: GuiId = GuiId("admin-cmd-categories")
        public val CATEGORY_ID: GuiId = GuiId("admin-cmd-category")
        public val FORM_ID: GuiId = GuiId("admin-cmd-form")
        public val PICK_ID: GuiId = GuiId("admin-cmd-pick")
        public val CONFIRM_ID: GuiId = GuiId("admin-cmd-confirm")
        public val RESULT_ID: GuiId = GuiId("admin-cmd-result")
        public val PLAYER_PAGE_ID: GuiId = GuiId("admin-cmd-player")

        private val ICON_COMMANDS = ItemId("gui-admin-lanes")
        private val ICON_HEAD = ItemId("gui-admin-player-head")
        private val ICON_RUN = ItemId("gui-admin-validate")
        private val ICON_ENTRY = ItemId("gui-admin-lane")
        private val ICON_CHOICE = ItemId("gui-admin-lanes")
        private val ICON_ITEM = ItemId("gui-admin-weapons")
        private val ICON_NUMBER = ItemId("gui-admin-currency")
        private val ICON_TEXT = ItemId("gui-admin-page")
        private val ICON_SEARCH = ItemId("gui-admin-refresh")
        private val ICON_YES = ItemId("indicator-on")
        private val ICON_NO = ItemId("indicator-off")

        private val OPEN_REJECTED = MessageKey("astera.gui.error.open-rejected")
    }
}
