package io.github.mangi.eta.agent.model

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.mangi.eta.agent.runtime.AgentRunController

class AgentTimeContextProjectionTest {
    private val fixedClock: Clock =
        Clock.fixed(Instant.parse("2026-10-03T06:23:05Z"), ZoneId.of("Asia/Shanghai"))

    @Test
    fun timeMessageIsInsertedAfterLeadingSystemBlockWithoutTouchingExistingMessages() {
        val system = JSONObject().put("role", "system").put("content", "你是 Eta。")
        val secondSystem = JSONObject().put("role", "system").put("content", "补充指令。")
        val user = AgentConversationCodec.userTextMessage("现在几点？")
        val assistant = JSONObject().put("role", "assistant").put("content", "你好")
        val messages = JSONArray().put(system).put(secondSystem).put(user).put(assistant)
        val original = messages.toString()

        val projected = AgentTimeContextProjection.project(messages, fixedClock)

        assertEquals(5, projected.length())
        val injected = projected.getJSONObject(2)
        assertEquals("system", injected.optString("role"))
        assertTrue(injected.optBoolean(AgentTimeContextProjection.MARKER))
        assertTrue(injected.optString("content").contains("[设备当前时间] 2026-10-03（星期六）14:23:05，时区 Asia/Shanghai。"))
        assertSame(system, projected.getJSONObject(0))
        assertSame(secondSystem, projected.getJSONObject(1))
        assertSame(user, projected.getJSONObject(3))
        assertSame(assistant, projected.getJSONObject(4))
        assertEquals(original, messages.toString())
    }

    @Test
    fun injectedContentTellsTheModelToUseItDirectlyAndStayInCharacter() {
        val projected = AgentTimeContextProjection.project(JSONArray(), fixedClock)
        val content = projected.getJSONObject(0).optString("content")

        assertTrue(content.contains("无需调用 get_current_context"))
        assertTrue(content.contains("保持人设"))
    }

    @Test
    fun projectionReflectsTheClockSoLongRunsStayFresh() {
        val later = Clock.fixed(Instant.parse("2026-10-04T07:00:00Z"), ZoneId.of("Asia/Shanghai"))
        val first = AgentTimeContextProjection.project(JSONArray(), fixedClock).toString()
        val second = AgentTimeContextProjection.project(JSONArray(), later).toString()

        assertTrue(first.contains("2026-10-03"))
        assertTrue(second.contains("2026-10-04"))
        assertFalse(first == second)
    }

    @Test
    fun timeReachesProviderRequestButNeverTheTranscript() {
        var received = false
        val provider = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                received = true
                val text = request.messages.toString()
                assertTrue(text.contains("[设备当前时间]"))
                // 标记允许存在于 loop 层；关键不变量是 wire 序列化后不携带元数据键。
                val wire = OpenAiRequestMessages.forChatCompletions(request.messages)
                assertTrue(wire.toString().contains("[设备当前时间]"))
                assertFalse(wire.toString().contains(AgentTimeContextProjection.MARKER))
                return ProviderResponse(JSONObject().put("role", "assistant").put("content", "现在时刻已提供").put("finish_reason", "stop"))
            }
        }
        val result = AgentModelClient.complete(
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.invalid",
                apiKey = "fixture",
                model = "fixture",
                contextWindow = 128_000,
                systemPrompt = "",
                timeInjection = true,
            ),
            prompt = "现在几点了？",
            provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor { error("不应执行工具") },
        )
        assertTrue(received)
        assertFalse(AgentConversationCodec.encodeTranscriptForStorage(result.transcript).contains("设备当前时间"))
    }

    @Test
    fun disabledTimeInjectionKeepsRequestUntouched() {
        val provider = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                assertFalse(request.messages.toString().contains("[设备当前时间]"))
                return ProviderResponse(JSONObject().put("role", "assistant").put("content", "你好").put("finish_reason", "stop"))
            }
        }
        AgentModelClient.complete(
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.invalid",
                apiKey = "fixture",
                model = "fixture",
                contextWindow = 128_000,
                systemPrompt = "",
                timeInjection = false,
            ),
            prompt = "现在几点了？",
            provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor { error("不应执行工具") },
        )
    }
}
