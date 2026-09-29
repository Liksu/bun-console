package dev.bunconsole

import dev.bunconsole.runtime.InspectorEndpoint
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InspectorEndpointTest {
    @Test fun acceptsOnlyOwnLoopbackAddressAcrossChunkBoundariesOnce() {
        val endpoint = InspectorEndpoint("own-token")
        assertNull(endpoint.accept("ws://127.0.0.1:1234/some-other-token\n"))
        assertNull(endpoint.accept("Listening:\n  ws://127.0.0.1:49"))
        assertNull(endpoint.accept("123/own-to"))
        assertEquals("ws://127.0.0.1:49123/own-token", endpoint.accept("ken\r\nInspect in browser:"))
        assertNull(endpoint.accept("ws://127.0.0.1:49123/own-token\n"))
    }
}
