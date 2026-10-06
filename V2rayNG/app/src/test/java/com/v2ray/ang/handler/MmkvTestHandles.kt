package com.v2ray.ang.handler

import com.tencent.mmkv.MMKV
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.mock

/**
 * Shared MMKV handles for JVM tests.
 *
 * `MmkvManager` binds every storage once through `by lazy`, so the first test class
 * that touches it decides which `MMKV.mmkvWithID` result the whole JVM run reads.
 * A class-local mock is therefore ignored once another class has already bound the
 * storages, and every class must use these instances and bind them only once.
 */
object MmkvTestHandles {
    val main: MMKV = mock()
    val subs: MMKV = mock()
    val settings: MMKV = mock()

    private var bound = false

    /**
     * Binds the [MmkvManager] storages to the shared mocks. Repeated calls after the
     * first one are no-ops so a later test class cannot rebind a storage that a
     * running test already holds.
     */
    fun bind() {
        if (bound) return
        mockStatic(MMKV::class.java).use {
            it.`when`<MMKV> { MMKV.mmkvWithID("MAIN", MMKV.MULTI_PROCESS_MODE) }.thenReturn(main)
            it.`when`<MMKV> { MMKV.mmkvWithID("SUB", MMKV.MULTI_PROCESS_MODE) }.thenReturn(subs)
            it.`when`<MMKV> { MMKV.mmkvWithID("SETTING", MMKV.MULTI_PROCESS_MODE) }.thenReturn(settings)
            MmkvManager.decodeSubscriptions()
            MmkvManager.decodeSettingsString("test-initialize")
        }
        bound = true
    }
}