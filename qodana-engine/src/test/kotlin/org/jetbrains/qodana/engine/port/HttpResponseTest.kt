package org.jetbrains.qodana.engine.port

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpResponseTest {

    @Test
    fun `isSuccess for 200`() {
        assertTrue(HttpResponse(200, "ok").isSuccess)
    }

    @Test
    fun `isSuccess for 201`() {
        assertTrue(HttpResponse(201, "created").isSuccess)
    }

    @Test
    fun `isSuccess for 299`() {
        assertTrue(HttpResponse(299, "").isSuccess)
    }

    @Test
    fun `not success for 300`() {
        assertFalse(HttpResponse(300, "").isSuccess)
    }

    @Test
    fun `not success for 400`() {
        assertFalse(HttpResponse(400, "bad request").isSuccess)
    }

    @Test
    fun `not success for 500`() {
        assertFalse(HttpResponse(500, "server error").isSuccess)
    }

    @Test
    fun `not success for 199`() {
        assertFalse(HttpResponse(199, "").isSuccess)
    }
}
