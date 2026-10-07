package io.github.mangi.eta.agent.model

import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * 系统时间只投影到当前请求：以独立 system 消息插到开头 system 块之后，
 * 不修改任何既有消息对象，也不进入 messages、transcript 或压缩快照。
 */
internal object AgentTimeContextProjection {
    const val MARKER = "_eta_time_context"

    fun project(messages: JSONArray, clock: Clock = Clock.systemDefaultZone()): JSONArray {
        val localTime = ZonedDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS)
        val timeMessage = JSONObject()
            .put("role", "system")
            .put(MARKER, true)
            .put("content", content(localTime))
        val projected = JSONArray()
        var inserted = false
        for (index in 0 until messages.length()) {
            if (!inserted && messages.getJSONObject(index).optString("role") != "system") {
                projected.put(timeMessage)
                inserted = true
            }
            projected.put(messages.getJSONObject(index))
        }
        if (!inserted) projected.put(timeMessage)
        return projected
    }

    private fun content(localTime: ZonedDateTime): String =
        "[设备当前时间] ${localTime.format(DateTimeFormatter.ISO_LOCAL_DATE)}" +
            "（${localTime.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.SIMPLIFIED_CHINESE)}）" +
            "${localTime.format(DateTimeFormatter.ISO_LOCAL_TIME)}，时区 ${localTime.zone.id}。\n" +
            "这是运行环境提供的事实信息，涉及现实时间时可据此回答，无需调用 get_current_context。\n" +
            "除用户询问或任务必需外，不要提及本行信息或其来源；角色扮演中保持人设，不要跳出角色。"
}
