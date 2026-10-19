package io.github.matiyaaa.fuse.integrations.retroachievements

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.TestHttp
import io.github.matiyaaa.fuse.integrations.json
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Signing in to RetroAchievements for a login token, as rcheevos asks for one. */
class RaConnectTest {
    @Test
    fun theLoginIsTheFormRcheevosPostsAndTheTokenComesBack() = runTest {
        val http = TestHttp { json("""{"Success":true,"User":"Ana","Token":"AbCdEf0123456789","Score":1200,"SoftcoreScore":30,"Messages":0}""") }
        val r = RaConnect(http.client, "Fuse/0.4.3").login(" ana ", "hunter2 with spaces")
        val login = assertIs<ApiResult.Success<RaLogin>>(r).value
        // The name as RetroAchievements has it, and its token.
        assertEquals("Ana", login.user)
        assertEquals("AbCdEf0123456789", login.token.reveal())
        assertFalse("AbCdEf0123456789" in login.toString())
        val req = http.last
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("https://retroachievements.org/dorequest.php", req.url.toString())
        assertEquals("Fuse/0.4.3", req.headers[HttpHeaders.UserAgent])
        val form = (req.body as FormDataContent).formData
        assertEquals("login2", form["r"])
        assertEquals("ana", form["u"])
        assertEquals("hunter2 with spaces", form["p"])
        // Nothing of it in the address, where logs and proxies would see it.
        assertTrue("hunter2" !in req.url.toString())
    }

    @Test
    fun aRefusalSaysWhyWithoutThePassword() = runTest {
        val http = TestHttp { json("""{"Success":false,"Error":"Invalid User/Password combination. Please try again"}""", HttpStatusCode.Unauthorized) }
        val r = RaConnect(http.client, "Fuse/0.4.3").login("Ana", "hunter2")
        val fail = assertIs<ApiResult.AuthError>(r)
        assertTrue(fail.message.contains("Invalid User/Password"))
        assertFalse(fail.message.contains("hunter2"))
    }

    @Test
    fun anAnswerWithoutATokenIsNoSignIn() = runTest {
        val http = TestHttp { json("""{"Success":true,"User":"Ana"}""") }
        assertIs<ApiResult.Failure>(RaConnect(http.client, "Fuse/0.4.3").login("Ana", "hunter2"))
        val garbled = TestHttp { json("<html>maintenance</html>", HttpStatusCode.ServiceUnavailable) }
        assertIs<ApiResult.Failure>(RaConnect(garbled.client, "Fuse/0.4.3").login("Ana", "hunter2"))
    }

    @Test
    fun aPasswordEchoedInAnErrorIsBlankedOut() = runTest {
        val http = TestHttp { json("""{"Success":false,"Error":"hunter2 is not right"}""") }
        val fail = assertIs<ApiResult.AuthError>(RaConnect(http.client, "Fuse/0.4.3").login("Ana", "hunter2"))
        assertFalse(fail.message.contains("hunter2"))
    }
}
