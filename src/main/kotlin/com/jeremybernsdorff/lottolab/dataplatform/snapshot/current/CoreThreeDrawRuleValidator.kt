package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import com.jeremybernsdorff.lottolab.dataplatform.model.DrawResultRuleRecord

internal object CoreThreeDrawRuleValidator {
    fun validate(mainValues: List<Int>, bonusValues: List<Int>, rule: DrawResultRuleRecord, context: String) {
        require(mainValues.size == rule.mainNumberCount) { "MAIN_COUNT_MISMATCH: $context" }
        require(mainValues.all { it in rule.mainMinimum..rule.mainMaximum }) { "MAIN_RANGE_MISMATCH: $context" }
        if (rule.mainNumbersUnique) require(mainValues.distinct().size == mainValues.size) { "MAIN_UNIQUENESS_MISMATCH: $context" }
        require(bonusValues.size in rule.bonusNumberCountMinimum..rule.bonusNumberCountMaximum) { "BONUS_COUNT_MISMATCH: $context" }
        if (bonusValues.isNotEmpty()) {
            val minimum = requireNotNull(rule.bonusMinimum) { "BONUS_MINIMUM_REQUIRED: $context" }
            val maximum = requireNotNull(rule.bonusMaximum) { "BONUS_MAXIMUM_REQUIRED: $context" }
            require(minimum <= maximum) { "BONUS_RANGE_RULE_INVALID: $context" }
            require(bonusValues.all { it in minimum..maximum }) { "BONUS_RANGE_MISMATCH: $context" }
        }
        if (!rule.bonusMayRepeat) require(bonusValues.distinct().size == bonusValues.size) { "BONUS_REPEAT_MISMATCH: $context" }
        if (!rule.bonusMayOverlapMain) require(bonusValues.none { it in mainValues }) { "BONUS_MAIN_OVERLAP_MISMATCH: $context" }
    }

    fun canonicalMainValues(mainValues: List<Int>, rule: DrawResultRuleRecord): List<Int> = if (rule.orderMatters) mainValues.toList() else mainValues.sorted()
}
