package com.lhzkml.jasmine.core.agent

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CustomLlmServiceTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun config(
        protocol: ProviderProtocol,
        address: String = server.url("/v1").toString().trimEnd('/'),
    ) = CustomProviderConfig(
        apiAddress = address,
        apiKey = "secret-token",
        model = "custom-model",
        protocol = protocol,
        contextLength = 32768,
        maxOutputTokens = 2048,
    )

    private fun response(body: String, code: Int = 200, contentType: String = "application/json") =
        MockResponse.Builder().code(code).addHeader("Content-Type", contentType).body(body).build()

    @Test
    fun `chat completions uses its endpoint and max_tokens`() = runBlocking {
        server.enqueue(response("""{"choices":[{"message":{"content":"chat reply"}}]}"""))

        val result = CustomLlmService(config(ProviderProtocol.CHAT_COMPLETIONS))
            .complete(LlmRequest(listOf(LlmMessage("user", "hello"))))

        assertEquals("chat reply", result.content)
        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.url!!.encodedPath)
        val body = request.body?.utf8().orEmpty()
        assertTrue(body.contains("\"max_tokens\":2048"))
        assertTrue(!body.contains("max_output_tokens"))
    }

    @Test
    fun `responses uses its endpoint and max_output_tokens`() = runBlocking {
        server.enqueue(response("""{"output":[{"content":[{"type":"output_text","text":"response reply"}]}]}"""))

        val result = CustomLlmService(config(ProviderProtocol.RESPONSES))
            .complete(LlmRequest(listOf(LlmMessage("user", "hello"))))

        assertEquals("response reply", result.content)
        val request = server.takeRequest()
        assertEquals("/v1/responses", request.url!!.encodedPath)
        val body = request.body?.utf8().orEmpty()
        assertTrue(body.contains("\"max_output_tokens\":2048"))
        assertTrue(!body.contains("\"max_tokens\""))
    }

    @Test
    fun `no output limit omits both protocol limit fields`() = runBlocking {
        server.enqueue(response("""{"choices":[{"message":{"content":"ok"}}]}"""))
        CustomLlmService(
            config(ProviderProtocol.CHAT_COMPLETIONS).copy(maxOutputTokens = null)
        ).complete(LlmRequest(listOf(LlmMessage("user", "hello"))))
        val chatBody = server.takeRequest().body?.utf8().orEmpty()
        assertTrue(!chatBody.contains("max_tokens"))
        assertTrue(!chatBody.contains("max_output_tokens"))

        server.enqueue(response("""{"output_text":"ok"}"""))
        CustomLlmService(
            config(ProviderProtocol.RESPONSES).copy(maxOutputTokens = null)
        ).complete(LlmRequest(listOf(LlmMessage("user", "hello"))))
        val responseBody = server.takeRequest().body?.utf8().orEmpty()
        assertTrue(!responseBody.contains("max_tokens"))
        assertTrue(!responseBody.contains("max_output_tokens"))
    }

    @Test
    fun `full protocol endpoint is normalized without duplication`() = runBlocking {
        server.enqueue(response("""{"output_text":"ok"}"""))
        val full = server.url("/v1/responses").toString()

        CustomLlmService(config(ProviderProtocol.RESPONSES, full))
            .complete(LlmRequest(listOf(LlmMessage("user", "hello"))))

        assertEquals("/v1/responses", server.takeRequest().url!!.encodedPath)
    }

    @Test
    fun `models endpoint is derived from the api root`() = runBlocking {
        server.enqueue(response("""{"data":[{"id":"model-a"},{"id":"model-b"}]}"""))

        val models = CustomLlmService(config(ProviderProtocol.CHAT_COMPLETIONS)).listModels()

        assertEquals(listOf("model-a", "model-b"), models)
        assertEquals("/v1/models", server.takeRequest().url!!.encodedPath)
    }

    @Test
    fun `provider errors never expose the api key`() = runBlocking {
        server.enqueue(response("invalid secret-token", code = 401))

        val failure = assertFailsWith<LlmProviderException> {
            CustomLlmService(config(ProviderProtocol.CHAT_COMPLETIONS))
                .complete(LlmRequest(emptyList()))
        }
        assertTrue(!failure.message!!.contains("secret-token"))
        assertTrue(failure.message!!.contains("401"))
    }

    @Test
    fun `configuration enforces context and output constraints`() {
        assertFailsWith<IllegalArgumentException> {
            config(ProviderProtocol.CHAT_COMPLETIONS).copy(
                contextLength = 1024,
                maxOutputTokens = 1024,
            )
        }
    }

    @Test
    fun `chat completions streams delta content over SSE`() = runBlocking {
        server.enqueue(
            response(
                "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n" +
                    "data: [DONE]\n\n",
                contentType = "text/event-stream",
            )
        )

        val deltas = mutableListOf<String>()
        val result = CustomLlmService(config(ProviderProtocol.CHAT_COMPLETIONS))
            .stream(LlmRequest(listOf(LlmMessage("user", "hi"))), onDelta = { deltas.add(it) })

        assertEquals(listOf("你", "好"), deltas)
        assertEquals("你好", result.content)
        assertTrue(server.takeRequest().body?.utf8().orEmpty().contains("\"stream\":true"))
    }

    @Test
    fun `responses streams output_text delta events`() = runBlocking {
        server.enqueue(
            response(
                "event: response.output_text.delta\n" +
                    "data: {\"type\":\"response.output_text.delta\",\"delta\":\"流\"}\n\n" +
                    "event: response.output_text.delta\n" +
                    "data: {\"type\":\"response.output_text.delta\",\"delta\":\"式\"}\n\n" +
                    "event: response.completed\n" +
                    "data: {}\n\n",
                contentType = "text/event-stream",
            )
        )

        val deltas = mutableListOf<String>()
        val result = CustomLlmService(config(ProviderProtocol.RESPONSES))
            .stream(LlmRequest(listOf(LlmMessage("user", "hi"))), onDelta = { deltas.add(it) })

        assertEquals(listOf("流", "式"), deltas)
        assertEquals("流式", result.content)
    }

    @Test
    fun `chat streams reasoning then content separately`() = runBlocking {
        server.enqueue(
            response(
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"思\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"考\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"答\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"案\"}}]}\n\n" +
                    "data: [DONE]\n\n",
                contentType = "text/event-stream",
            )
        )

        val deltas = mutableListOf<String>()
        val reasoning = mutableListOf<String>()
        CustomLlmService(config(ProviderProtocol.CHAT_COMPLETIONS)).stream(
            LlmRequest(listOf(LlmMessage("user", "hi"))),
            onDelta = { deltas.add(it) },
            onReasoning = { reasoning.add(it) },
        )

        assertEquals(listOf("思", "考"), reasoning)
        assertEquals(listOf("答", "案"), deltas)
    }

    @Test
    fun `responses streams reasoning summary deltas`() = runBlocking {
        server.enqueue(
            response(
                "event: response.reasoning_summary_text.delta\n" +
                    "data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"推\"}\n\n" +
                    "event: response.output_text.delta\n" +
                    "data: {\"type\":\"response.output_text.delta\",\"delta\":\"理\"}\n\n" +
                    "event: response.completed\n" +
                    "data: {}\n\n",
                contentType = "text/event-stream",
            )
        )

        val deltas = mutableListOf<String>()
        val reasoning = mutableListOf<String>()
        CustomLlmService(config(ProviderProtocol.RESPONSES)).stream(
            LlmRequest(listOf(LlmMessage("user", "hi"))),
            onDelta = { deltas.add(it) },
            onReasoning = { reasoning.add(it) },
        )

        assertEquals(listOf("推"), reasoning)
        assertEquals(listOf("理"), deltas)
    }

    @Test
    fun `jsonl streaming without data prefix is also streamed`() = runBlocking {
        // Some OpenAI-compatible gateways emit one JSON chunk per line with
        // no `data:` prefix. It is still streaming.
        server.enqueue(
            response(
                "{\"choices\":[{\"delta\":{\"reasoning_content\":\"r1\"}}]}\n" +
                    "{\"choices\":[{\"delta\":{\"content\":\"c1\"}}]}\n" +
                    "{\"choices\":[{\"delta\":{\"content\":\"c2\"}}]}\n",
                contentType = "application/x-ndjson",
            )
        )

        val deltas = mutableListOf<String>()
        val reasoning = mutableListOf<String>()
        CustomLlmService(config(ProviderProtocol.CHAT_COMPLETIONS)).stream(
            LlmRequest(listOf(LlmMessage("user", "hi"))),
            onDelta = { deltas.add(it) },
            onReasoning = { reasoning.add(it) },
        )

        assertEquals(listOf("r1"), reasoning)
        assertEquals(listOf("c1", "c2"), deltas)
    }

    @Test
    fun `a provider that ignores stream fails loud`() = runBlocking {
        server.enqueue(response("""{"choices":[{"message":{"content":"json"}}]}"""))

        val failure = assertFailsWith<LlmProviderException> {
            CustomLlmService(config(ProviderProtocol.CHAT_COMPLETIONS)).stream(
                LlmRequest(emptyList()),
                onDelta = {},
            )
        }
        assertTrue(failure.message!!.contains("流式"))
    }
}
