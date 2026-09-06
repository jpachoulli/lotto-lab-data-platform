package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import com.fasterxml.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.format.DateTimeParseException

internal val CORE_THREE_PUBLICATION_GAMES =
    listOf("powerball", "mega_millions", "lotto_america")

private val PUBLICATION_HASH =
    Regex("[0-9a-f]{64}")

private val PUBLICATION_COMMIT =
    Regex("[0-9a-f]{40}")

private const val MAX_PUBLICATION_ASSET_BYTES =
    50L * 1024L * 1024L

internal data class CoreThreePublicationCatchUpState(
    val matureThrough: LocalDate,
    val candidateRequired: Boolean,
    val baseCutoffs: Map<String, LocalDate>,
    val throughDates: Map<String, LocalDate>,
    val newlyWrittenLedgerCount: Int,
    val reusedLedgerCount: Int
)

internal data class CoreThreePublicationBuildMetadata(
    val snapshotVersion: String,
    val assetSha256: String,
    val assetByteSize: Long,
    val bundleContentId: String,
    val snapshotManifestSha256: String,
    val recordCounts: Map<String, Int>,
    val cutoffs: Map<String, LocalDate>,
    val sourceRepositoryCommit: String
)

internal fun parseCoreThreePublicationCatchUpSummary(
    bytes: ByteArray
): CoreThreePublicationCatchUpState {
    val root =
        publicationObject(
            B2_JSON.readTree(bytes),
            "catchUp"
        )

    publicationExactKeys(
        root,
        setOf(
            "matureThrough",
            "candidateRequired",
            "newlyWrittenLedgerCount",
            "reusedLedgerCount",
            "games"
        ),
        "catchUp"
    )

    val matureThrough =
        publicationDate(
            publicationText(
                root,
                "matureThrough",
                "catchUp"
            ),
            "catchUp.matureThrough"
        )

    val candidateRequired =
        publicationBoolean(
            root,
            "candidateRequired",
            "catchUp"
        )

    val newlyWrittenLedgerCount =
        publicationInt(
            root,
            "newlyWrittenLedgerCount",
            "catchUp"
        )

    val reusedLedgerCount =
        publicationInt(
            root,
            "reusedLedgerCount",
            "catchUp"
        )

    require(newlyWrittenLedgerCount >= 0) {
        "NEGATIVE_NEWLY_WRITTEN_LEDGER_COUNT"
    }

    require(reusedLedgerCount >= 0) {
        "NEGATIVE_REUSED_LEDGER_COUNT"
    }

    val gamesNode =
        publicationObject(
            root["games"],
            "catchUp.games"
        )

    require(
        gamesNode
            .fieldNames()
            .asSequence()
            .toSet() ==
            CORE_THREE_PUBLICATION_GAMES.toSet()
    ) {
        "CATCH_UP_GAME_SET_MISMATCH"
    }

    val baseCutoffs =
        linkedMapOf<String, LocalDate>()

    val throughDates =
        linkedMapOf<String, LocalDate>()

    var calculatedNewlyWritten =
        0

    var calculatedReused =
        0

    CORE_THREE_PUBLICATION_GAMES.forEach { game ->
        val context =
            "catchUp.games.$game"

        val node =
            publicationObject(
                gamesNode[game],
                context
            )

        publicationExactKeys(
            node,
            setOf(
                "baseCutoff",
                "matureThrough",
                "verifiedFrontier",
                "state",
                "waitingOnDate",
                "waitingReason",
                "newlyWrittenLedgerDates",
                "reusedLedgerDates"
            ),
            context
        )

        val baseCutoff =
            publicationDate(
                publicationText(
                    node,
                    "baseCutoff",
                    context
                ),
                "$context.baseCutoff"
            )

        val gameMatureThrough =
            publicationDate(
                publicationText(
                    node,
                    "matureThrough",
                    context
                ),
                "$context.matureThrough"
            )

        val frontier =
            publicationDate(
                publicationText(
                    node,
                    "verifiedFrontier",
                    context
                ),
                "$context.verifiedFrontier"
            )

        val state =
            publicationText(
                node,
                "state",
                context
            )

        val waitingOnDate =
            publicationNullableText(
                node,
                "waitingOnDate",
                context
            )?.let {
                publicationDate(
                    it,
                    "$context.waitingOnDate"
                )
            }

        val waitingReason =
            publicationNullableText(
                node,
                "waitingReason",
                context
            )

        val newlyWritten =
            publicationDateArray(
                node["newlyWrittenLedgerDates"],
                "$context.newlyWrittenLedgerDates"
            )

        val reused =
            publicationDateArray(
                node["reusedLedgerDates"],
                "$context.reusedLedgerDates"
            )

        require(
            gameMatureThrough ==
                matureThrough
        ) {
            "CATCH_UP_MATURE_THROUGH_MISMATCH: $game"
        }

        require(
            frontier >=
                baseCutoff
        ) {
            "CATCH_UP_FRONTIER_BEFORE_BASE: $game"
        }

        if (
            matureThrough >=
            baseCutoff
        ) {
            require(
                frontier <=
                    matureThrough
            ) {
                "CATCH_UP_FRONTIER_BEYOND_MATURE_WINDOW: $game"
            }
        }

        val expectedMatureWindow =
            if (
                matureThrough <=
                baseCutoff
            ) {
                emptyList()
            } else {
                CoreThreeCurrentSchedule
                    .expectedDrawDates(
                        game,
                        baseCutoff,
                        matureThrough
                    )
            }

        val processed =
            newlyWritten +
                reused

        require(
            processed
                .distinct()
                .size ==
                processed.size
        ) {
            "CATCH_UP_LEDGER_DATE_OVERLAP: $game"
        }

        val expectedProcessed =
            if (
                frontier ==
                baseCutoff
            ) {
                emptyList()
            } else {
                CoreThreeCurrentSchedule
                    .expectedDrawDates(
                        game,
                        baseCutoff,
                        frontier
                    )
            }

        require(
            processed.toSet() ==
                expectedProcessed.toSet() &&
                processed.size ==
                expectedProcessed.size
        ) {
            "CATCH_UP_PROCESSED_LEDGER_PREFIX_MISMATCH: $game"
        }

        if (
            frontier >
            baseCutoff
        ) {
            require(
                expectedProcessed
                    .lastOrNull() ==
                    frontier
            ) {
                "CATCH_UP_FRONTIER_OFF_SCHEDULE: $game"
            }
        }

        when (state) {
            "CURRENT" -> {
                require(
                    frontier ==
                        baseCutoff
                ) {
                    "CURRENT_FRONTIER_MISMATCH: $game"
                }

                require(
                    waitingOnDate ==
                        null &&
                        waitingReason ==
                        null
                ) {
                    "CURRENT_WAITING_FIELDS_PRESENT: $game"
                }

                require(
                    expectedMatureWindow
                        .isEmpty()
                ) {
                    "CURRENT_MATURE_DRAW_SKIPPED: $game"
                }
            }

            "ADVANCED" -> {
                require(
                    frontier >
                        baseCutoff
                ) {
                    "ADVANCED_FRONTIER_NOT_ADVANCED: $game"
                }

                require(
                    waitingOnDate ==
                        null &&
                        waitingReason ==
                        null
                ) {
                    "ADVANCED_WAITING_FIELDS_PRESENT: $game"
                }

                require(
                    expectedMatureWindow
                        .lastOrNull() ==
                        frontier
                ) {
                    "ADVANCED_MATURE_DRAW_SKIPPED: $game"
                }
            }

            "WAITING_FOR_OFFICIAL_RESULT" -> {
                require(
                    waitingOnDate !=
                        null
                ) {
                    "WAITING_DATE_MISSING: $game"
                }

                require(
                    waitingReason ==
                        "NOT_YET_AVAILABLE" ||
                        waitingReason ==
                        "AWAITING_SECOND_SOURCE"
                ) {
                    "WAITING_REASON_INVALID: $game"
                }

                val nextExpected =
                    expectedMatureWindow
                        .firstOrNull {
                            it >
                                frontier
                        }

                require(
                    waitingOnDate ==
                        nextExpected
                ) {
                    "WAITING_DATE_NOT_NEXT_EXPECTED_DRAW: $game"
                }
            }

            else ->
                error(
                    "UNSUPPORTED_CATCH_UP_STATE: $game/$state"
                )
        }

        calculatedNewlyWritten +=
            newlyWritten.size

        calculatedReused +=
            reused.size

        baseCutoffs[game] =
            baseCutoff

        throughDates[game] =
            frontier
    }

    require(
        calculatedNewlyWritten ==
            newlyWrittenLedgerCount
    ) {
        "NEWLY_WRITTEN_LEDGER_COUNT_MISMATCH"
    }

    require(
        calculatedReused ==
            reusedLedgerCount
    ) {
        "REUSED_LEDGER_COUNT_MISMATCH"
    }

    val calculatedCandidateRequired =
        CORE_THREE_PUBLICATION_GAMES
            .any { game ->
                throughDates
                    .getValue(game) >
                    baseCutoffs
                        .getValue(game)
            }

    require(
        calculatedCandidateRequired ==
            candidateRequired
    ) {
        "CANDIDATE_REQUIRED_MISMATCH"
    }

    return CoreThreePublicationCatchUpState(
        matureThrough =
            matureThrough,
        candidateRequired =
            candidateRequired,
        baseCutoffs =
            baseCutoffs.toMap(),
        throughDates =
            throughDates.toMap(),
        newlyWrittenLedgerCount =
            newlyWrittenLedgerCount,
        reusedLedgerCount =
            reusedLedgerCount
    )
}

internal fun parseCoreThreePublicationBuildMetadata(
    bytes: ByteArray
): CoreThreePublicationBuildMetadata {
    val node =
        publicationObject(
            B2_JSON.readTree(bytes),
            "buildMetadata"
        )

    publicationExactKeys(
        node,
        setOf(
            "snapshotVersion",
            "assetSha256",
            "assetByteSize",
            "bundleContentId",
            "snapshotManifestSha256",
            "recordCounts",
            "cutoffs",
            "buildMode",
            "sourceRepositoryCommit",
            "publishable"
        ),
        "buildMetadata"
    )

    val assetSha256 =
        publicationText(
            node,
            "assetSha256",
            "buildMetadata"
        )

    val bundleContentId =
        publicationText(
            node,
            "bundleContentId",
            "buildMetadata"
        )

    val snapshotManifestSha256 =
        publicationText(
            node,
            "snapshotManifestSha256",
            "buildMetadata"
        )

    require(
        PUBLICATION_HASH.matches(
            assetSha256
        )
    ) {
        "INVALID_PUBLICATION_ASSET_SHA"
    }

    require(
        PUBLICATION_HASH.matches(
            bundleContentId
        )
    ) {
        "INVALID_PUBLICATION_BUNDLE_ID"
    }

    require(
        PUBLICATION_HASH.matches(
            snapshotManifestSha256
        )
    ) {
        "INVALID_PUBLICATION_MANIFEST_SHA"
    }

    val assetByteSize =
        publicationLong(
            node,
            "assetByteSize",
            "buildMetadata"
        )

    require(
        assetByteSize in
            1..MAX_PUBLICATION_ASSET_BYTES
    ) {
        "INVALID_PUBLICATION_ASSET_SIZE"
    }

    val recordCountsNode =
        publicationObject(
            node["recordCounts"],
            "buildMetadata.recordCounts"
        )

    require(
        recordCountsNode
            .fieldNames()
            .asSequence()
            .toSet() ==
            CORE_THREE_PUBLICATION_GAMES
                .toSet()
    ) {
        "BUILD_METADATA_RECORD_COUNT_GAME_SET_MISMATCH"
    }

    val recordCounts =
        CORE_THREE_PUBLICATION_GAMES
            .associateWith { game ->
                publicationInt(
                    recordCountsNode,
                    game,
                    "buildMetadata.recordCounts"
                ).also {
                    require(
                        it >
                            0
                    ) {
                        "INVALID_PUBLICATION_RECORD_COUNT: $game"
                    }
                }
            }

    val cutoffsNode =
        publicationObject(
            node["cutoffs"],
            "buildMetadata.cutoffs"
        )

    require(
        cutoffsNode
            .fieldNames()
            .asSequence()
            .toSet() ==
            CORE_THREE_PUBLICATION_GAMES
                .toSet()
    ) {
        "BUILD_METADATA_CUTOFF_GAME_SET_MISMATCH"
    }

    val cutoffs =
        CORE_THREE_PUBLICATION_GAMES
            .associateWith { game ->
                publicationDate(
                    publicationText(
                        cutoffsNode,
                        game,
                        "buildMetadata.cutoffs"
                    ),
                    "buildMetadata.cutoffs.$game"
                )
            }

    val snapshotVersion =
        publicationText(
            node,
            "snapshotVersion",
            "buildMetadata"
        )

    val expectedSnapshotVersion =
        "core-three-pb-${cutoffs.getValue("powerball")}" +
            "-mm-${cutoffs.getValue("mega_millions")}" +
            "-la-${cutoffs.getValue("lotto_america")}-v1"

    require(
        snapshotVersion ==
            expectedSnapshotVersion
    ) {
        "BUILD_METADATA_SNAPSHOT_CUTOFF_MISMATCH"
    }

    require(
        publicationText(
            node,
            "buildMode",
            "buildMetadata"
        ) ==
            "PUBLISHABLE"
    ) {
        "BUILD_METADATA_MODE_NOT_PUBLISHABLE"
    }

    require(
        publicationBoolean(
            node,
            "publishable",
            "buildMetadata"
        )
    ) {
        "BUILD_METADATA_PUBLISHABLE_FALSE"
    }

    val sourceRepositoryCommit =
        publicationText(
            node,
            "sourceRepositoryCommit",
            "buildMetadata"
        )

    require(
        PUBLICATION_COMMIT.matches(
            sourceRepositoryCommit
        ) &&
            sourceRepositoryCommit
                .any {
                    it !=
                        '0'
                }
    ) {
        "INVALID_PUBLICATION_SOURCE_COMMIT"
    }

    return CoreThreePublicationBuildMetadata(
        snapshotVersion =
            snapshotVersion,
        assetSha256 =
            assetSha256,
        assetByteSize =
            assetByteSize,
        bundleContentId =
            bundleContentId,
        snapshotManifestSha256 =
            snapshotManifestSha256,
        recordCounts =
            recordCounts,
        cutoffs =
            cutoffs,
        sourceRepositoryCommit =
            sourceRepositoryCommit
    )
}

private fun publicationObject(
    node: JsonNode?,
    context: String
): JsonNode {
    require(
        node !=
            null &&
            node.isObject
    ) {
        "$context must be a JSON object"
    }

    return node
}

private fun publicationExactKeys(
    node: JsonNode,
    expected: Set<String>,
    context: String
) {
    val actual =
        node
            .fieldNames()
            .asSequence()
            .toSet()

    require(
        actual ==
            expected
    ) {
        "$context key mismatch: " +
            "expected=${expected.sorted()} " +
            "actual=${actual.sorted()}"
    }
}

private fun publicationText(
    node: JsonNode,
    field: String,
    context: String
): String {
    val value =
        requireNotNull(
            node[field]
        ) {
            "$context missing field: $field"
        }

    require(
        value.isTextual
    ) {
        "$context.$field must be a JSON string"
    }

    return value
        .textValue()
        .also {
            require(
                it.isNotBlank()
            ) {
                "$context.$field must not be blank"
            }
        }
}

private fun publicationNullableText(
    node: JsonNode,
    field: String,
    context: String
): String? {
    val value =
        requireNotNull(
            node[field]
        ) {
            "$context missing field: $field"
        }

    if (
        value.isNull
    ) {
        return null
    }

    require(
        value.isTextual
    ) {
        "$context.$field must be null or JSON string"
    }

    return value
        .textValue()
        .also {
            require(
                it.isNotBlank()
            ) {
                "$context.$field must not be blank"
            }
        }
}

private fun publicationBoolean(
    node: JsonNode,
    field: String,
    context: String
): Boolean {
    val value =
        requireNotNull(
            node[field]
        ) {
            "$context missing field: $field"
        }

    require(
        value.isBoolean
    ) {
        "$context.$field must be a JSON boolean"
    }

    return value
        .booleanValue()
}

private fun publicationInt(
    node: JsonNode,
    field: String,
    context: String
): Int {
    val value =
        requireNotNull(
            node[field]
        ) {
            "$context missing field: $field"
        }

    require(
        value.isIntegralNumber &&
            value.canConvertToInt()
    ) {
        "$context.$field must be an integral JSON number"
    }

    return value
        .intValue()
}

private fun publicationLong(
    node: JsonNode,
    field: String,
    context: String
): Long {
    val value =
        requireNotNull(
            node[field]
        ) {
            "$context missing field: $field"
        }

    require(
        value.isIntegralNumber &&
            value.canConvertToLong()
    ) {
        "$context.$field must be an integral JSON number"
    }

    return value
        .longValue()
}

private fun publicationDate(
    value: String,
    context: String
): LocalDate =
    try {
        LocalDate.parse(
            value
        )
    } catch (
        e: DateTimeParseException
    ) {
        throw IllegalArgumentException(
            "$context must be ISO LocalDate: $value",
            e
        )
    }

private fun publicationDateArray(
    node: JsonNode?,
    context: String
): List<LocalDate> {
    require(
        node !=
            null &&
            node.isArray
    ) {
        "$context must be a JSON array"
    }

    val dates =
        node.mapIndexed {
                index,
                value ->
            require(
                value.isTextual
            ) {
                "$context[$index] must be a JSON string"
            }

            publicationDate(
                value.textValue(),
                "$context[$index]"
            )
        }

    require(
        dates ==
            dates.sorted()
    ) {
        "$context must be ascending"
    }

    require(
        dates
            .distinct()
            .size ==
            dates.size
    ) {
        "$context contains duplicates"
    }

    return dates
}
