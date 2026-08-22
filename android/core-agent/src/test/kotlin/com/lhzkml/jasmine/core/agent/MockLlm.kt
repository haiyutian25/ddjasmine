package com.lhzkml.jasmine.core.agent

/**
 * Test-only provider: echoes the last user message so the loop can be tested
 * without network. This class never enters production source sets or the APK.
 */
class MockLlmService : LlmService {
    override suspend fun complete(request: LlmRequest): LlmResponse {
        val lastUser = request.messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        return LlmResponse(content = "echo: $lastUser")
    }
}
