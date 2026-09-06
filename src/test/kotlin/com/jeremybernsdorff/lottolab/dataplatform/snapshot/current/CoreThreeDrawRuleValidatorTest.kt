package com.jeremybernsdorff.lottolab.dataplatform.snapshot.current

import com.jeremybernsdorff.lottolab.dataplatform.model.DrawResultRuleRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CoreThreeDrawRuleValidatorTest {
    private fun rule(order: Boolean = false, bonusMin: Int = 1, bonusMax: Int = 1, bonusCountMin: Int = 1, bonusCountMax: Int = 1, overlap: Boolean = false, repeat: Boolean = false) = DrawResultRuleRecord(5, 1, 69, true, order, bonusCountMin, bonusCountMax, bonusMin, bonusMax, repeat, overlap)
    @Test fun bonusOverlapRejectedWhenRuleDisallowsIt() { val e=assertFailsWith<IllegalArgumentException>{CoreThreeDrawRuleValidator.validate(listOf(1,2,3,4,5),listOf(5),rule(bonusMax=26),"overlap")};assertEquals("BONUS_MAIN_OVERLAP_MISMATCH: overlap",e.message) }
    @Test fun bonusRepeatRejectedWhenRuleDisallowsIt() { val e=assertFailsWith<IllegalArgumentException>{CoreThreeDrawRuleValidator.validate(listOf(1,2,3,4,5),listOf(7,7),rule(bonusCountMin=2,bonusCountMax=2,bonusMax=26),"repeat")};assertEquals("BONUS_REPEAT_MISMATCH: repeat",e.message) }
    @Test fun unorderedMainCanonicalizesAscending() { assertEquals(listOf(1,2,5,7,9),CoreThreeDrawRuleValidator.canonicalMainValues(listOf(9,2,7,1,5),rule())) }
    @Test fun orderedMainPreservesInputOrder() { assertEquals(listOf(9,2,7,1,5),CoreThreeDrawRuleValidator.canonicalMainValues(listOf(9,2,7,1,5),rule(order=true))) }
}
