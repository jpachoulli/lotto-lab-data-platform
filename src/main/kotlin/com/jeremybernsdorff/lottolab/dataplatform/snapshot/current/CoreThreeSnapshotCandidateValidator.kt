package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import com.fasterxml.jackson.databind.JsonNode
import com.jeremybernsdorff.lottolab.dataplatform.catalog.NationalGameEraCatalog
import com.jeremybernsdorff.lottolab.dataplatform.model.DrawResultRuleRecord
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.zip.ZipFile

data class CandidateValidationSummary(val recordTotal: Int, val gameCounts: Map<String, Int>, val coverageRows: Int, val memberCount: Int, val bundleContentId: String)

object CoreThreeSnapshotCandidateValidator {
    private val games = listOf("powerball", "mega_millions", "lotto_america")
    private const val algorithm = "sha256(path + NUL + memberSha256 + LF; lexicographic path order; manifest.json excluded)"
    private val hash = Regex("[0-9a-f]{64}")
    private val requiredMembers = PublishedCoreThreeSnapshotReader.REQUIRED_MEMBERS
    private val drawPrefix = listOf("stable_id", "game_id", "game_era_id", "draw_date", "draw_timestamp_millis", "draw_session", "main_1", "main_2", "main_3", "main_4", "main_5")
    private val metadataHeader = listOf("stable_draw_id", "advertised_jackpot_minor_units", "jackpot_winner_count", "rollover", "multiplier", "extra_draw_data", "prize_tiers_json")
    private val standardHeader = listOf("stable_draw_id", "game_id", "game_era_id", "draw_date", "source_evidence_id", "source_url", "raw_response_sha256", "retrieved_at_utc", "parser_version", "source_organization", "source_classification", "source_limitation")
    private val lottoHeader = listOf("game_id", "draw_date", "musl_source_id", "musl_source_locator", "musl_evidence_sha256", "musl_response_sha256", "iowa_source_id", "iowa_source_locator", "iowa_evidence_sha256", "iowa_response_sha256")
    private val eraRootKeys = setOf("schemaId", "eras")
    private val eraKeys = setOf("gameId", "eraId", "effectiveFrom", "effectiveUntil", "drawResultRule")
    private val ruleKeys = setOf("mainNumberCount", "mainMinimum", "mainMaximum", "mainNumbersUnique", "orderMatters", "bonusNumberCountMinimum", "bonusNumberCountMaximum", "bonusMinimum", "bonusMaximum", "bonusMayRepeat", "bonusMayOverlapMain")
    private val evidenceKeys = setOf("evidenceId", "sourceId", "sourceOrganization", "canonicalSourceUrl", "sourceDatasetId", "retrievedAtUtc", "rawSha256", "parserVersion", "termsReference")
    private val coverageKeys = setOf("gameId", "eraId", "earliestKnownDraw", "latestKnownDraw", "expectedDrawCount", "validatedDrawCount", "missingDrawCount", "conflictingDrawCount", "coveragePercent", "sourceStatus", "automationStatus")

    fun validate(zipPath: java.nio.file.Path): CandidateValidationSummary = ZipFile(zipPath.toFile()).use { zip ->
        val entries = zip.entries().asSequence().toList()
        require(entries.size == requiredMembers.size) { "EXPECTED_13_MEMBERS" }
        require(entries.none { it.isDirectory } && entries.all { safe(it.name) })
        require(entries.map { it.name }.distinct().size == entries.size)
        require(entries.all { (it.extra ?: ByteArray(0)).isEmpty() })
        require(entries.map { it.name }.toSet() == requiredMembers)
        val members = entries.associate { it.name to zip.getInputStream(it).use { input -> input.readBytes() } }
        val manifest = json(members.getValue("manifest.json")); require(manifest.isObject)
        requireExactObjectKeys(manifest, setOf("schemaId", "schemaVersion", "snapshotVersion", "createdAtUtc", "immutable", "coreGameCount", "totalRecordCount", "installedHistoryScope", "sourceRepositoryCommit", "eraCatalog", "games", "sourceEvidence", "coverage", "bundleContentIdAlgorithm", "bundleContentId"), "manifest")
        require(requireText(manifest, "schemaId", "manifest") == "lotto-lab-core-three-snapshot-manifest-v2")
        require(requireInt(manifest, "schemaVersion", "manifest") == 2)
        require(requireBoolean(manifest, "immutable", "manifest"))
        require(requireInt(manifest, "coreGameCount", "manifest") == 3)
        require(requireText(manifest, "installedHistoryScope", "manifest") == "ALL_ACCEPTED_HISTORY")
        requireText(manifest, "snapshotVersion", "manifest"); requireInstant(requireText(manifest, "createdAtUtc", "manifest"), "manifest.createdAtUtc")
        require(requireText(manifest, "sourceRepositoryCommit", "manifest").matches(Regex("[0-9a-f]{40}")))
        require(requireText(manifest, "bundleContentIdAlgorithm", "manifest") == algorithm) { "BUNDLE_CONTENT_ID_ALGORITHM_UNSUPPORTED" }
        require(requireText(manifest, "bundleContentId", "manifest").matches(hash))
        require(manifest["games"]?.isArray == true && manifest["games"].size() == 3)
        require(manifest["games"].map { requireText(it, "gameId", "manifest.game"); it["gameId"].textValue() } == games) { "MANIFEST_GAME_ORDER_OR_SET_MISMATCH" }
        require(bundleId(members) == requireText(manifest, "bundleContentId", "manifest"))
        verifyManifest(manifest, members)
        val eras = parseEras(members.getValue("eras.json")); require(eras.size == 15); verifyEraCatalog(eras)
        val evidence = parseEvidence(members.getValue("source_evidence.json")); require(evidence.size == requireInt(manifest["sourceEvidence"], "recordCount", "manifest.sourceEvidence")); require(evidence.map { it.first }.toSet().size == evidence.size)
        val evidenceIds = evidence.map { it.first }.toSet()
        val allDrawIds = linkedSetOf<String>(); val logical = HashSet<LogicalDrawIdentity>(); val actualByEra = mutableMapOf<Pair<String, String>, MutableList<LocalDate>>(); val counts = linkedMapOf<String, Int>()
        games.forEach { game ->
            val drawRows = parseDraws(members.getValue("draws/$game.csv"), game, evidenceIds)
            drawRows.forEach { draw -> require(allDrawIds.add(draw.id)) { "DUPLICATE_STABLE_ID" }; require(logical.add(LogicalDrawIdentity(draw.game, draw.date, normalizeDrawSession(draw.session)))) { "DUPLICATE_LOGICAL_DRAW_IDENTITY" }; actualByEra.getOrPut(game to draw.era) { mutableListOf() }.add(draw.date) }
            counts[game] = drawRows.size
            require(drawRows.size == requireInt(manifest["games"].single { it["gameId"].textValue() == game }, "recordCount", "manifest.game.$game"))
            verifyMetadata(members.getValue("public_metadata/$game.csv"), drawRows.map { it.id }.toSet())
            verifyProvenance(members.getValue("provenance/$game.csv"), game, drawRows, evidenceIds)
        }
        require(requireInt(manifest, "totalRecordCount", "manifest") == counts.values.sum())
        verifyCoverage(members.getValue("coverage.json"), actualByEra, manifest)
        validateCoverageStrict(members.getValue("coverage.json"), actualByEra, manifest)
        CandidateValidationSummary(counts.values.sum(), counts, 15, entries.size, requireText(manifest, "bundleContentId", "manifest"))
    }

    private data class LogicalDrawIdentity(val gameId: String, val drawDate: LocalDate, val normalizedSession: String)
    private data class DrawRow(val id: String, val game: String, val era: String, val date: LocalDate, val session: String, val main: List<Int>, val bonus: List<Int>)
    private data class Era(val game: String, val id: String, val from: String?, val until: String?, val rule: JsonNode)
    private fun normalizeDrawSession(value: String?): String = value?.trim()?.uppercase(Locale.US).orEmpty()

    private fun parseDraws(bytes: ByteArray, game: String, evidenceIds: Set<String>): List<DrawRow> {
        val rows = csv(bytes); val bonus = when (game) { "powerball" -> "powerball"; "mega_millions" -> "mega_ball"; else -> "star_ball" }
        require(rows.firstOrNull() == drawPrefix + bonus + listOf("source_reference", "validation_status", "origin", "source_evidence_id", "is_sample"))
        return rows.drop(1).map { row ->
            require(row.size == 17); val id = nonblank(row[0]); require(row[1] == game); val eraId = nonblank(row[2]); val date = requireLocalDate(row[3], "draw.date"); nullableLong(row[4], "draw_timestamp_millis"); val session = row[5]
            val main = row.subList(6, 11).map(::integer); val b = listOf(integer(row[11])); nonblank(row[12]); require(row[13] == "OFFICIAL_VERIFIED"); nonblank(row[14]); require(row[15] in evidenceIds); require(boolean(row[16]) == false)
            val eras = NationalGameEraCatalog.resolve(game, date); require(eras.size == 1) { "AMBIGUOUS_ERA" }; require(eras.single().eraId == eraId); CoreThreeDrawRuleValidator.validate(main, b, eras.single().drawResultRule, id); require(id.endsWith("-$date")); DrawRow(id, game, eraId, date, session, main, b)
        }
    }
    private fun verifyManifest(m: JsonNode, members: Map<String, ByteArray>) { checkRef(m["eraCatalog"], "eras.json", members, 15); checkRef(m["sourceEvidence"], "source_evidence.json", members, requireInt(m["sourceEvidence"], "recordCount", "manifest.sourceEvidence")); checkRef(m["coverage"], "coverage.json", members, 15); games.forEach { game -> val g=m["games"].single{it["gameId"].textValue()==game}; val count=requireInt(g,"recordCount","manifest.game.$game");require(count>0);checkFile(g["files"]["draws"],"draws/$game.csv",members);checkFile(g["files"]["publicMetadata"],"public_metadata/$game.csv",members);checkFile(g["files"]["provenance"],"provenance/$game.csv",members);listOf("draws","publicMetadata","provenance").forEach{require(requireInt(g["files"][it],"recordCount","manifest.$game.$it")==count)} } }
    private fun parseEras(bytes:ByteArray):List<Era>{val root=json(bytes);requireExactObjectKeys(root,eraRootKeys,"eras root");require(requireText(root,"schemaId","eras root")=="lotto-lab-core-three-era-catalog-v1");require(root["eras"].isArray);return root["eras"].mapIndexed{i,e->requireExactObjectKeys(e,eraKeys,"era[$i]");val rule=e["drawResultRule"];requireExactObjectKeys(rule,ruleKeys,"era[$i].drawResultRule");requireText(e,"gameId","era[$i]");requireText(e,"eraId","era[$i]");nullableDate(e,"effectiveFrom","era[$i]");nullableDate(e,"effectiveUntil","era[$i]");ruleFields(rule,"era[$i].drawResultRule");Era(e["gameId"].textValue(),e["eraId"].textValue(),nullableText(e,"effectiveFrom","era[$i]"),nullableText(e,"effectiveUntil","era[$i]"),rule)}}
    private fun ruleFields(n:JsonNode,c:String){requireInt(n,"mainNumberCount",c);requireInt(n,"mainMinimum",c);requireInt(n,"mainMaximum",c);requireBoolean(n,"mainNumbersUnique",c);requireBoolean(n,"orderMatters",c);requireInt(n,"bonusNumberCountMinimum",c);requireInt(n,"bonusNumberCountMaximum",c);requireInt(n,"bonusMinimum",c);requireInt(n,"bonusMaximum",c);requireBoolean(n,"bonusMayRepeat",c);requireBoolean(n,"bonusMayOverlapMain",c)}
    private fun verifyEraCatalog(actual:List<Era>){require(actual.map{it.game to it.id}.toSet()==NationalGameEraCatalog.eras.map{it.gameId to it.eraId}.toSet()){"ERA_CATALOG_KEY_SET_MISMATCH"};actual.forEach{a->val e=NationalGameEraCatalog.eras.single{it.gameId==a.game&&it.eraId==a.id};require(a.from==e.effectiveFrom?.toString()&&a.until==e.effectiveUntil?.toString()){"ERA_CATALOG_BOUNDARY_MISMATCH"};val r=e.drawResultRule;val expected=mapOf("mainNumberCount" to r.mainNumberCount,"mainMinimum" to r.mainMinimum,"mainMaximum" to r.mainMaximum,"mainNumbersUnique" to r.mainNumbersUnique,"orderMatters" to r.orderMatters,"bonusNumberCountMinimum" to r.bonusNumberCountMinimum,"bonusNumberCountMaximum" to r.bonusNumberCountMaximum,"bonusMinimum" to r.bonusMinimum,"bonusMaximum" to r.bonusMaximum,"bonusMayRepeat" to r.bonusMayRepeat,"bonusMayOverlapMain" to r.bonusMayOverlapMain);expected.forEach{(k,v)->val n=a.rule[k];if(v is Boolean)require(n.isBoolean&&n.booleanValue()==v){"ERA_CATALOG_RULE_MISMATCH"}else require(n.isIntegralNumber&&n.intValue()==v){"ERA_CATALOG_RULE_MISMATCH"}}}}
    private fun parseEvidence(bytes:ByteArray):List<Pair<String,JsonNode>>{val n=json(bytes);require(n.isArray);return n.mapIndexed{i,e->requireExactObjectKeys(e,evidenceKeys,"evidence[$i]");val id=requireText(e,"evidenceId","evidence[$i]");requireText(e,"sourceId","evidence[$i]");requireText(e,"sourceOrganization","evidence[$i]");requireText(e,"canonicalSourceUrl","evidence[$i]");nullableText(e,"sourceDatasetId","evidence[$i]");requireInstant(requireText(e,"retrievedAtUtc","evidence[$i]"),"evidence[$i].retrievedAtUtc");require(requireText(e,"rawSha256","evidence[$i]").matches(hash));requireText(e,"parserVersion","evidence[$i]");nullableText(e,"termsReference","evidence[$i]");id to e}}
    private fun verifyMetadata(bytes:ByteArray,ids:Set<String>){val rows=csv(bytes);require(rows.firstOrNull()==metadataHeader);val data=rows.drop(1);require(data.size==ids.size&&data.map{nonblank(it[0])}.toSet()==ids);data.forEach{require(it.size==7);nullableLong(it[1],"metadata.advertised");nullableLong(it[2],"metadata.winners");nullableBoolean(it[3],"metadata.rollover");nonblankOrEmpty(it[4]);nonblankOrEmpty(it[5]);nonblank(it[6])}}
    private fun verifyProvenance(bytes:ByteArray,game:String,draws:List<DrawRow>,evidenceIds:Set<String>){val rows=csv(bytes);val data=rows.drop(1);if(game!="lotto_america"){require(rows.firstOrNull()==standardHeader);require(data.size==draws.size);val byId=draws.associateBy{it.id};require(data.map{it[0]}.toSet()==byId.keys);data.forEach{require(it.size==12);val d=byId.getValue(it[0]);require(it[1]==d.game){"PROVENANCE_GAME_MISMATCH: ${d.id}"};require(it[2]==d.era){"PROVENANCE_ERA_MISMATCH: ${d.id}"};require(requireLocalDate(it[3],"provenance.date")==d.date){"PROVENANCE_DATE_MISMATCH: ${d.id}"};require(it[4] in evidenceIds){"PROVENANCE_EVIDENCE_MISSING: ${d.id}"};requireHttps(it[5]);require(it[6].matches(hash));requireInstant(it[7],"provenance.instant");nonblank(it[8]);nonblank(it[9]);nonblank(it[10]);nonblank(it[11])}}else{require(rows.firstOrNull()==lottoHeader);require(data.size==draws.size);val keys=draws.map{it.game to it.date}.toSet();require(data.map{it[0] to requireLocalDate(it[1],"lotto provenance date")}.toSet()==keys);data.forEach{require(it.size==10&&it[0]==game);require(it[2]=="musl_lotto_america"&&it[6]=="iowa_lottery_lotto_america");requireHttps(it[3]);requireHttps(it[7]);require(it[4].matches(hash)&&it[5].matches(hash)&&it[8].matches(hash)&&it[9].matches(hash))}}}
    private fun verifyCoverage(bytes:ByteArray,actual:Map<Pair<String,String>,MutableList<LocalDate>>,manifest:JsonNode){val root=json(bytes);require(root.isArray){"COVERAGE_ROOT_NOT_ARRAY"};require(root.size()==requireInt(manifest["coverage"],"recordCount","manifest.coverage")){"COVERAGE_MANIFEST_COUNT_MISMATCH"};val keys=root.mapIndexed{i,row->requireExactObjectKeys(row,coverageKeys,"coverage[$i]");requireText(row,"gameId","coverage[$i]") to requireText(row,"eraId","coverage[$i]")};require(keys.toSet()==NationalGameEraCatalog.eras.map{it.gameId to it.eraId}.toSet()){"COVERAGE_ERA_KEY_SET_MISMATCH"};root.forEachIndexed{i,x->{val game=requireText(x,"gameId","coverage[$i]");val era=requireText(x,"eraId","coverage[$i]");val dates=actual[game to era].orEmpty();val validated=requireInt(x,"validatedDrawCount","coverage[$i]");require(validated==dates.size){"COVERAGE_VALIDATED_COUNT_MISMATCH: $game/$era expected=${dates.size} actual=$validated"};require(requireNullableInt(x,"expectedDrawCount","coverage[$i]")==dates.size){"COVERAGE_EXPECTED_COUNT_MISMATCH: $game/$era"};require(requireNullableInt(x,"missingDrawCount","coverage[$i]")==0){"COVERAGE_MISSING_COUNT_NONZERO: $game/$era"};require(requireInt(x,"conflictingDrawCount","coverage[$i]")==0){"COVERAGE_CONFLICT_COUNT_NONZERO: $game/$era"};require(requireNullableDouble(x,"coveragePercent","coverage[$i]")==100.0){"COVERAGE_PERCENT_MISMATCH: $game/$era"};requireText(x,"sourceStatus","coverage[$i]");requireText(x,"automationStatus","coverage[$i]");val first=nullableDate(x,"earliestKnownDraw","coverage[$i]");val last=nullableDate(x,"latestKnownDraw","coverage[$i]");if(dates.isEmpty())require(first==null&&last==null){"COVERAGE_EMPTY_BOUNDARY_MISMATCH: $game/$era"}else{require(first==dates.minOrNull()){"COVERAGE_EARLIEST_MISMATCH: $game/$era"};require(last==dates.maxOrNull()){"COVERAGE_LATEST_MISMATCH: $game/$era"}}}}
    }
    private fun checkFile(n:JsonNode,expected:String,m:Map<String,ByteArray>){require(n.isObject);require(requireText(n,"path","manifest.file")==expected);require(m.containsKey(expected));require(requireText(n,"sha256","manifest.file")==sha256(m.getValue(expected)));require(requireLong(n,"byteSize","manifest.file")==m.getValue(expected).size.toLong())}
    private fun checkRef(n:JsonNode,expected:String,m:Map<String,ByteArray>,count:Int){checkFile(n,expected,m);require(requireInt(n,"recordCount","manifest.ref")==count)}
    private fun requireExactObjectKeys(node:JsonNode,expected:Set<String>,context:String){require(node.isObject){"$context must be a JSON object"};val actual=node.fieldNames().asSequence().toSet();require(actual==expected){"$context key mismatch: expected=${expected.sorted()} actual=${actual.sorted()}"}}
    private fun requireText(node:JsonNode,field:String,context:String,allowBlank:Boolean=false):String{val v=node.get(field)?:error("$context missing field: $field");require(v.isTextual){"$context.$field must be a JSON string"};return v.textValue().also{if(!allowBlank)require(it.isNotBlank())}}
    private fun nullableText(node:JsonNode,field:String,context:String):String?{val v=node.get(field)?:error("$context missing field: $field");if(v.isNull)return null;require(v.isTextual);return v.textValue()}
    private fun requireInt(node:JsonNode,field:String,context:String):Int{val v=node.get(field)?:error("$context missing field: $field");require(v.isIntegralNumber&&v.canConvertToInt()){ "$context.$field must be an integral JSON number" };return v.intValue()}
    private fun requireNullableInt(node:JsonNode,field:String,context:String):Int?{val v=node.get(field)?:error("$context missing field: $field");if(v.isNull)return null;require(v.isIntegralNumber&&v.canConvertToInt());return v.intValue()}
    private fun requireLong(node:JsonNode,field:String,context:String):Long{val v=node.get(field)?:error("$context missing field: $field");require(v.isIntegralNumber&&v.canConvertToLong());return v.longValue()}
    private fun requireBoolean(node:JsonNode,field:String,context:String):Boolean{val v=node.get(field)?:error("$context missing field: $field");require(v.isBoolean);return v.booleanValue()}
    private fun requireNullableDouble(node:JsonNode,field:String,context:String):Double?{val v=node.get(field)?:error("$context missing field: $field");if(v.isNull)return null;require(v.isNumber);return v.doubleValue()}
    private fun requireLocalDate(value:String,context:String):LocalDate=try{LocalDate.parse(value)}catch(e:DateTimeParseException){throw IllegalArgumentException("$context must be ISO LocalDate: $value",e)}
    private fun requireInstant(value:String,context:String):Instant=try{Instant.parse(value)}catch(e:DateTimeParseException){throw IllegalArgumentException("$context must be ISO Instant: $value",e)}
    private fun nullableDate(node:JsonNode,field:String,context:String):LocalDate?=nullableText(node,field,context)?.let{requireLocalDate(it,"$context.$field")}
    private fun csv(b:ByteArray)=StrictCsvCodec.parse(decode(b));private fun json(b:ByteArray)=B2_JSON.readTree(decode(b));private fun decode(b:ByteArray)=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString()
    private fun integer(s:String)=s.toInt().also{require(s.matches(Regex("-?\\d+")))};private fun nullableLong(s:String,c:String){if(s.isNotEmpty())require(s.matches(Regex("-?\\d+")));if(s.isNotEmpty())s.toLong()};private fun nullableBoolean(s:String,c:String):Boolean?{if(s.isEmpty())return null;require(s=="true"||s=="false"){"INVALID_BOOLEAN: $c"};return s=="true"};private fun boolean(s:String):Boolean{require(s=="true"||s=="false");return s=="true"};private fun nonblank(s:String)=s.also{require(it.isNotBlank())};private fun nonblankOrEmpty(s:String)=s;private fun requireHttps(s:String){require(runCatching{java.net.URI(s)}.getOrNull()?.let{it.scheme.equals("https",true)&&!it.host.isNullOrBlank()}==true)};private fun safe(s:String)=s.isNotBlank()&&!s.startsWith('/')&&!s.contains('\\')&&!s.split('/').any{it.isBlank()||it==".."};private fun bundleId(m:Map<String,ByteArray>)=sha256(m.filterKeys{it!="manifest.json"}.toSortedMap().entries.joinToString(""){"${it.key}\u0000${sha256(it.value)}\n"}.toByteArray())
}

private fun validateCoverageStrict(bytes: ByteArray, actual: Map<Pair<String, String>, MutableList<LocalDate>>, manifest: JsonNode) {
    val root = B2_JSON.readTree(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString())
    require(root.isArray) { "COVERAGE_ROOT_NOT_ARRAY" }
    require(root.size() == manifest["coverage"]["recordCount"].intValue()) { "COVERAGE_MANIFEST_COUNT_MISMATCH" }
    val keys = setOf("gameId", "eraId", "earliestKnownDraw", "latestKnownDraw", "expectedDrawCount", "validatedDrawCount", "missingDrawCount", "conflictingDrawCount", "coveragePercent", "sourceStatus", "automationStatus")
    root.forEachIndexed { index, row ->
        require(row.isObject && row.fieldNames().asSequence().toSet() == keys) { "coverage[$index] key mismatch" }
        fun text(field: String): String { val n = row[field]; require(n != null && n.isTextual && n.textValue().isNotBlank()) { "coverage[$index].$field must be a JSON string" }; return n.textValue() }
        fun nullableInt(field: String): Int? { val n = row[field] ?: error("coverage[$index] missing field: $field"); if (n.isNull) return null; require(n.isIntegralNumber && n.canConvertToInt()) { "coverage[$index].$field must be an integral JSON number" }; return n.intValue() }
        fun date(field: String): LocalDate? { val value = row[field] ?: error("coverage[$index] missing field: $field"); if (value.isNull) return null; require(value.isTextual) { "coverage[$index].$field must be null or JSON string" }; return try { LocalDate.parse(value.textValue()) } catch (e: DateTimeParseException) { throw IllegalArgumentException("coverage[$index].$field must be ISO LocalDate: ${value.textValue()}", e) } }
        val game = text("gameId"); val era = text("eraId"); val dates = actual[game to era].orEmpty()
        require(nullableInt("validatedDrawCount") == dates.size) { "COVERAGE_VALIDATED_COUNT_MISMATCH: $game/$era expected=${dates.size} actual=${row["validatedDrawCount"]}" }
        require(nullableInt("expectedDrawCount") == dates.size) { "COVERAGE_EXPECTED_COUNT_MISMATCH: $game/$era" }
        require(nullableInt("missingDrawCount") == 0) { "COVERAGE_MISSING_COUNT_NONZERO: $game/$era" }
        val conflicting = nullableInt("conflictingDrawCount"); require(conflicting == 0) { "COVERAGE_CONFLICT_COUNT_NONZERO: $game/$era" }
        val percent = row["coveragePercent"]; require(percent != null && percent.isNumber && percent.doubleValue() == 100.0) { "COVERAGE_PERCENT_MISMATCH: $game/$era" }
        text("sourceStatus"); text("automationStatus"); val earliest = date("earliestKnownDraw"); val latest = date("latestKnownDraw")
        if (dates.isEmpty()) require(earliest == null && latest == null) { "COVERAGE_EMPTY_BOUNDARY_MISMATCH: $game/$era" }
        else { require(earliest == dates.minOrNull()) { "COVERAGE_EARLIEST_MISMATCH: $game/$era" }; require(latest == dates.maxOrNull()) { "COVERAGE_LATEST_MISMATCH: $game/$era" } }
    }
}
