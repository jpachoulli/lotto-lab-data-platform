package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import com.jeremybernsdorff.lottolab.dataplatform.acquisition.current.CurrentDrawAcquisitionResult
import com.jeremybernsdorff.lottolab.dataplatform.acquisition.current.CurrentDrawSourceRegistry
import com.jeremybernsdorff.lottolab.dataplatform.acquisition.current.DualOfficialCurrentDrawAcquirer
import com.jeremybernsdorff.lottolab.dataplatform.acquisition.current.VerifiedCurrentDraw
import com.jeremybernsdorff.lottolab.dataplatform.catalog.NationalGameEraCatalog
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate

internal class CoreThreeCatchUpService(
    private val baseLoader: (Path) -> PublishedBaseSnapshot = { PublishedCoreThreeSnapshotReader().read(it) },
    private val acquire: (String, LocalDate, Instant) -> CurrentDrawAcquisitionResult = { game, date, retrieved ->
        DualOfficialCurrentDrawAcquirer(CurrentDrawSourceRegistry.production()).acquire(game, date, retrieved)
    },
    private val matureThrough: (Instant) -> LocalDate = CoreThreeDrawMaturityPolicy::matureThrough
) {
    private data class BaseDraw(val gameId: String, val eraId: String, val drawDate: LocalDate, val mainValues: List<Int>, val bonusValues: List<Int>)
    private data class BaseGame(val gameId: String, val draws: Map<LocalDate, BaseDraw>) { val cutoff get() = draws.keys.maxOrNull() ?: error("EMPTY_BASE_GAME: $gameId") }
    private data class LedgerInventory(val draws: Map<Pair<String, LocalDate>, VerifiedCurrentDraw>) {
        operator fun get(gameId: String, date: LocalDate) = draws[gameId to date]
        fun datesFor(gameId: String) = draws.keys.filter { it.first == gameId }.map { it.second }.sorted()
    }

    fun catchUp(descriptorPath: Path, ledgerRoot: Path, nowUtc: Instant): CoreThreeCatchUpSummary {
        val maturityDate = matureThrough(nowUtc)
        val base = parseBase(baseLoader(descriptorPath))
        val initialLedger = inventoryLedger(ledgerRoot)
        CoreThreePermanentLedgerPolicy.games.forEach { game ->
            val bg = base.getValue(game)
            requireSupportedBootstrapBoundary(game, bg)
            requireMaterializedContinuity(game, bg, initialLedger)
            requirePendingLedgerPrefix(game, bg.cutoff, maturityDate, initialLedger)
        }
        val summaries = linkedMapOf<String, GameCatchUpSummary>()
        for (game in CoreThreePermanentLedgerPolicy.games) summaries[game] = catchUpGame(game, base.getValue(game), ledgerRoot, initialLedger, maturityDate, nowUtc)
        return CoreThreeCatchUpSummary(maturityDate, summaries.toMap())
    }

    private fun catchUpGame(gameId: String, base: BaseGame, ledgerRoot: Path, initialLedger: LedgerInventory, matureThrough: LocalDate, nowUtc: Instant): GameCatchUpSummary {
        val cutoff = base.cutoff
        val inception = CoreThreePermanentLedgerPolicy.inceptionDate(gameId)
        val acquisitionAfterExclusive = if (cutoff.isBefore(inception)) inception.minusDays(1) else cutoff
        val expected = if (matureThrough <= acquisitionAfterExclusive) emptyList() else CoreThreeCurrentSchedule.expectedDrawDates(gameId, acquisitionAfterExclusive, matureThrough)
        var frontier = cutoff
        val written = mutableListOf<LocalDate>(); val reused = mutableListOf<LocalDate>()
        var waitingDate: LocalDate? = null; var waitingReason: String? = null
        for (date in expected) {
            require(!date.isBefore(inception)) {
                "ACQUISITION_BEFORE_PERMANENT_LEDGER_INCEPTION: $gameId/$date"
            }
            val path = CoreThreePermanentLedgerPolicy.ledgerPath(ledgerRoot, gameId, date)
            val existing = if (Files.exists(path)) VerifiedCurrentDrawLedger.read(Files.readAllBytes(path)) else initialLedger[gameId, date]
            if (existing != null) { validatePersistedDraw(existing, gameId, date); reused += date; frontier = date; continue }
            when (val result = acquire(gameId, date, nowUtc)) {
                is CurrentDrawAcquisitionResult.Verified -> {
                    val acquired = result.draw; validateAcquiredDraw(acquired, gameId, date)
                    val canonicalAcquired = VerifiedCurrentDraw(acquired.stableId, acquired.gameId, acquired.eraId, acquired.drawDate, acquired.mainValues, acquired.bonusValues, acquired.observations.sortedBy { it.sourceId })
                    val writeResult = VerifiedCurrentDrawLedger.writeImmutable(path, canonicalAcquired)
                    require(writeResult == "WRITTEN" || writeResult == "ALREADY_PRESENT") { "UNEXPECTED_LEDGER_WRITE_RESULT: $writeResult" }
                    val persisted = VerifiedCurrentDrawLedger.read(Files.readAllBytes(path)); validatePersistedDraw(persisted, gameId, date)
                    require(persisted == canonicalAcquired) { "PERSISTED_LEDGER_READBACK_MISMATCH: $gameId/$date" }
                    if (writeResult == "WRITTEN") written += date else reused += date
                    frontier = date
                }
                CurrentDrawAcquisitionResult.NotYetAvailable -> { waitingDate = date; waitingReason = "NOT_YET_AVAILABLE"; break }
                is CurrentDrawAcquisitionResult.AwaitingSecondSource -> { waitingDate = date; waitingReason = "AWAITING_SECOND_SOURCE"; break }
                is CurrentDrawAcquisitionResult.Conflict -> error("OFFICIAL_DRAW_CONFLICT: $gameId/$date: ${result.reason}")
                is CurrentDrawAcquisitionResult.Unavailable -> error("OFFICIAL_DRAW_UNAVAILABLE: $gameId/$date: ${result.reason}")
            }
        }
        val state = when { waitingDate != null -> GameCatchUpState.WAITING_FOR_OFFICIAL_RESULT; frontier > cutoff -> GameCatchUpState.ADVANCED; else -> GameCatchUpState.CURRENT }
        return GameCatchUpSummary(gameId, cutoff, matureThrough, frontier, written.toList(), reused.toList(), waitingDate, waitingReason, state)
    }

    private fun requireSupportedBootstrapBoundary(game: String, base: BaseGame) {
        val bootstrapCutoff = CoreThreePermanentLedgerPolicy.bootstrapBaseCutoff(game)
        val inception = CoreThreePermanentLedgerPolicy.inceptionDate(game)

        require(!base.cutoff.isBefore(bootstrapCutoff)) {
            "BASE_CUTOFF_BEFORE_BOOTSTRAP_BOUNDARY: $game/${base.cutoff}; minimum=$bootstrapCutoff"
        }

        if (base.cutoff.isBefore(inception)) {
            require(base.cutoff == bootstrapCutoff) {
                "PRE_INCEPTION_BASE_CUTOFF_MISMATCH: $game/${base.cutoff}; expected=$bootstrapCutoff"
            }
        }
    }

    private fun parseBase(snapshot: PublishedBaseSnapshot): Map<String, BaseGame> = CoreThreePermanentLedgerPolicy.games.associateWith { game ->
        val path = "draws/$game.csv"; val rows = StrictCsvCodec.parse(snapshot.members[path]!!.toString(Charsets.UTF_8)); require(rows.size >= 2) { "EMPTY_BASE_GAME: $game" }
        val draws = linkedMapOf<LocalDate, BaseDraw>()
        rows.drop(1).forEach { row -> require(row.size >= 12) { "MALFORMED_BASE_DRAW_ROW: $game" }; val date = LocalDate.parse(row[3]); require(row[1] == game) { "BASE_GAME_ID_MISMATCH: $game/$date" }; require(draws.put(date, BaseDraw(game, row[2], date, row.subList(6, 11).map(String::toInt), listOf(row[11].toInt()))) == null) { "DUPLICATE_BASE_DRAW_DATE: $game/$date" } }
        BaseGame(game, draws.toMap())
    }

    private fun inventoryLedger(root: Path): LedgerInventory {
        if (!Files.exists(root)) return LedgerInventory(emptyMap())
        require(Files.isDirectory(root)) { "UNEXPECTED_LEDGER_PATH" }
        val result = linkedMapOf<Pair<String, LocalDate>, VerifiedCurrentDraw>()
        Files.walk(root).use { stream -> stream.filter(Files::isRegularFile).forEach { path ->
            val rel = root.relativize(path); require(rel.nameCount == 2) { "UNEXPECTED_LEDGER_PATH: $rel" }
            val game = rel.getName(0).toString(); require(game in CoreThreePermanentLedgerPolicy.games) { "UNSUPPORTED_LEDGER_GAME: $game" }
            val file = rel.getName(1).toString(); require(file.endsWith(".json")) { "UNEXPECTED_LEDGER_FILE: $rel" }
            val date = runCatching { LocalDate.parse(file.removeSuffix(".json")) }.getOrElse { throw IllegalArgumentException("INVALID_LEDGER_DATE: $rel", it) }
            val inception = CoreThreePermanentLedgerPolicy.inceptionDate(game)
            require(!date.isBefore(inception)) { "LEDGER_BEFORE_INCEPTION: $game/$date" }
            val draw = VerifiedCurrentDrawLedger.read(Files.readAllBytes(path)); require(draw.gameId == game && draw.drawDate == date) { "LEDGER_PATH_IDENTITY_MISMATCH: $rel" }; validatePersistedDraw(draw, game, date)
            require(result.put(game to date, draw) == null) { "DUPLICATE_LEDGER_IDENTITY: $game/$date" }
        } }
        return LedgerInventory(result.toMap())
    }

    private fun validatePersistedDraw(draw: VerifiedCurrentDraw, game: String, date: LocalDate) {
        CoreThreeVerifiedDrawValidator.validate(
            draw = draw,
            expectedGameId = game,
            expectedDrawDate = date
        )
    }

    private fun validateAcquiredDraw(draw: VerifiedCurrentDraw, game: String, date: LocalDate) {
        CoreThreeVerifiedDrawValidator.validate(
            draw = draw,
            expectedGameId = game,
            expectedDrawDate = date
        )
    }

    private fun requireMatchesBase(persisted: VerifiedCurrentDraw, base: BaseDraw) {
        require(persisted.gameId == base.gameId && persisted.drawDate == base.drawDate && persisted.eraId == base.eraId) { "MATERIALIZED_LEDGER_CANONICAL_MISMATCH: ${base.gameId}/${base.drawDate}" }
        val rule = NationalGameEraCatalog.resolve(base.gameId, base.drawDate).single().drawResultRule
        require(CoreThreeDrawRuleValidator.canonicalMainValues(persisted.mainValues, rule) == CoreThreeDrawRuleValidator.canonicalMainValues(base.mainValues, rule) && persisted.bonusValues == base.bonusValues) { "MATERIALIZED_LEDGER_CANONICAL_MISMATCH: ${base.gameId}/${base.drawDate}" }
    }
    private fun requireMaterializedContinuity(game: String, base: BaseGame, ledger: LedgerInventory) {
        val inception =
            CoreThreePermanentLedgerPolicy.inceptionDate(game)

        if (base.cutoff < inception) {
            return
        }

        val expected =
            CoreThreeCurrentSchedule.expectedDrawDates(
                game,
                inception.minusDays(1),
                base.cutoff
            )

        val expectedSet = expected.toSet()

        val materializedLedgerDates =
            ledger.datesFor(game).filter {
                !it.isBefore(inception) && !it.isAfter(base.cutoff)
            }

        val offScheduleLedgerDates =
            materializedLedgerDates.filter { it !in expectedSet }

        require(offScheduleLedgerDates.isEmpty()) {
            "OFF_SCHEDULE_MATERIALIZED_LEDGER_DRAW: " +
                "$game/${offScheduleLedgerDates.first()}"
        }

        val materializedBaseDates =
            base.draws.keys.filter {
                !it.isBefore(inception) && !it.isAfter(base.cutoff)
            }.sorted()

        val offScheduleBaseDates =
            materializedBaseDates.filter { it !in expectedSet }

        require(offScheduleBaseDates.isEmpty()) {
            "OFF_SCHEDULE_MATERIALIZED_BASE_DRAW: " +
                "$game/${offScheduleBaseDates.first()}"
        }

        val missingLedgerDates = expected.filter { ledger[game, it] == null }
        require(missingLedgerDates.isEmpty()) {
            "MISSING_MATERIALIZED_PERMANENT_LEDGER: " +
                "$game/${missingLedgerDates.first()}"
        }

        val missingBaseDates = expected.filter { base.draws[it] == null }
        require(missingBaseDates.isEmpty()) {
            "MATERIALIZED_LEDGER_DATE_NOT_IN_BASE: " +
                "$game/${missingBaseDates.first()}"
        }

        expected.forEach { date ->
            val draw = ledger[game, date] ?: error(
                "MISSING_MATERIALIZED_PERMANENT_LEDGER: $game/$date"
            )
            val baseDraw = base.draws[date] ?: error(
                "MATERIALIZED_LEDGER_DATE_NOT_IN_BASE: $game/$date"
            )
            validatePersistedDraw(draw, game, date)
            requireMatchesBase(draw, baseDraw)
        }
    }
    private fun requirePendingLedgerPrefix(
        game: String,
        cutoff: LocalDate,
        mature: LocalDate,
        ledger: LedgerInventory
    ) {
        val expected = if (mature <= cutoff) {
            emptyList()
        } else {
            CoreThreeCurrentSchedule.expectedDrawDates(game, cutoff, mature)
        }

        val pending = ledger.datesFor(game).filter { it > cutoff }
        val beyond = pending.filter { it > mature }
        require(beyond.isEmpty()) {
            "LEDGER_BEYOND_MATURE_WINDOW: $game/${beyond.first()}"
        }

        val maturePending = pending.filter { it <= mature }
        val expectedSet = expected.toSet()
        val offSchedule = maturePending.filter { it !in expectedSet }
        require(offSchedule.isEmpty()) {
            "OFF_SCHEDULE_LEDGER_DRAW: $game/${offSchedule.first()}"
        }

        require(maturePending == expected.take(maturePending.size)) {
            "LEDGER_NONCONTIGUOUS_PREFIX: $game"
        }
    }
}
