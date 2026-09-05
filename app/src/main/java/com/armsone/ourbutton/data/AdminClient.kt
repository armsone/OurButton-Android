package com.armsone.ourbutton.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AdminMember(val deviceID: String, val name: String, val role: String)
data class AdminSpace(val spaceID: String, val name: String, val members: List<AdminMember>)
class AdminRequestException(val status: Int, message: String) : Exception(message)

class AdminClient(private val configuration: BackendConfiguration) {
    suspend fun login(username: String, password: String): String =
        JSONObject(request("sessions", "POST", body = JSONObject().put("username", username).put("password", password)))
            .getString("token")

    suspend fun spaces(token: String): List<AdminSpace> {
        val items = JSONObject(request("spaces", "GET", token)).getJSONArray("spaces")
        return List(items.length()) { index ->
            val room = items.getJSONObject(index)
            val members = room.getJSONArray("members")
            AdminSpace(room.getString("spaceID"), room.getString("name"), List(members.length()) { i ->
                val member = members.getJSONObject(i)
                AdminMember(member.getString("deviceID"), member.getString("name"), member.getString("role"))
            })
        }
    }

    suspend fun rename(token: String, spaceID: String, name: String) {
        request("spaces/$spaceID", "PATCH", token, JSONObject().put("name", name))
    }
    suspend fun role(token: String, spaceID: String, deviceID: String, role: String) {
        request("spaces/$spaceID/members/$deviceID", "PATCH", token, JSONObject().put("role", role))
    }
    suspend fun delete(token: String, spaceID: String) { request("spaces/$spaceID", "DELETE", token) }
    suspend fun logout(token: String) { request("sessions/current", "DELETE", token) }

    private suspend fun request(path: String, method: String, token: String? = null, body: JSONObject? = null): String =
        withContext(Dispatchers.IO) {
            val base = configuration.baseUrl?.takeIf { it.isNotBlank() } ?: throw BackendException.NotConfigured
            require(URL(base).protocol == "https") { "관리자 로그인에는 HTTPS 서버가 필요해요." }
            val connection = URL(base.trimEnd('/') + "/v1/admin/" + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                connection.setRequestProperty("Accept", "application/json")
                token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                body?.let {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { output -> output.write(it.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                if (code !in 200..299) throw AdminRequestException(code, when (code) {
                    401 -> if (token == null) "아이디 또는 비밀번호를 확인해 주세요." else "관리자 로그인이 만료되었어요. 다시 로그인해 주세요."
                    429 -> "로그인 시도가 많아요. 잠시 후 다시 시도해 주세요."
                    503 -> "서버 관리자 계정이 아직 준비되지 않았어요."
                    404 -> "공간 또는 참여자를 찾을 수 없어요. 새로고침해 주세요."
                    else -> "관리 요청을 처리하지 못했어요. (HTTP $code)"
                })
                if (code == 204) "" else connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally { connection.disconnect() }
        }
}
