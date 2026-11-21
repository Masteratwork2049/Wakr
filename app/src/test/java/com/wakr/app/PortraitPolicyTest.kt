package com.wakr.app

import org.junit.Assert.*
import org.junit.Test

class PortraitPolicyTest {
    @Test fun onlySlabPhonesLockTheirActivity() {
        assertTrue(lockSlabPortrait(false, 411))
        assertFalse(lockSlabPortrait(true, 443))
        assertFalse(lockSlabPortrait(true, 840))
        assertFalse(lockSlabPortrait(false, 800))
    }
}
